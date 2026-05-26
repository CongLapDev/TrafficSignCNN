package com.example.trafficsigncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capture Photo recognition screen.
 *
 * Flow:
 *  1. Live camera preview with guide box
 *  2. User presses Capture → freeze frame, show captured image
 *  3. Independent inference runs 3× (majority vote)
 *  4. Result shown in result card
 *  5. User presses Retake → back to live preview
 */
public class CapturePhotoActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 3001;

    private TFLiteHelper tfliteHelper;
    private PreviewView capturePreviewView;
    private ImageView capturedImageView;
    private TextView resultText;
    private TextView analysingText;
    private MaterialCardView resultCard;
    private FloatingActionButton btnCapture;
    private FloatingActionButton btnRetake;
    private View guideBox;

    private ExecutorService inferenceExecutor;
    private FirestoreRepository firestoreRepository;

    // Capture sync
    private volatile boolean captureRequested = false;
    private final Object captureLock = new Object();
    private boolean isCapturedMode = false;

    // Displayed bitmap (to recycle on retake)
    private Bitmap displayedBitmap = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_photo);

        capturePreviewView = findViewById(R.id.capturePreviewView);
        capturedImageView = findViewById(R.id.capturedImageView);
        resultText = findViewById(R.id.resultText);
        analysingText = findViewById(R.id.analysingText);
        resultCard = findViewById(R.id.resultCard);
        btnCapture = findViewById(R.id.btnCapture);
        btnRetake = findViewById(R.id.btnRetake);
        guideBox = findViewById(R.id.guideBox);

        inferenceExecutor = Executors.newSingleThreadExecutor();
        firestoreRepository = FirestoreRepository.getInstance();

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        btnCapture.setOnClickListener(v -> {
            synchronized (captureLock) {
                captureRequested = true;
            }
        });

        btnRetake.setOnClickListener(v -> resetToPreviewMode());

        try {
            tfliteHelper = new TFLiteHelper(this);
        } catch (Exception e) {
            Toast.makeText(this, "Tải mô hình thất bại", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindCamera(future.get());
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Không thể khởi động camera", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(capturePreviewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(inferenceExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                // In captured mode — skip all frames to save resources
                if (isCapturedMode) {
                    image.close();
                    return;
                }

                boolean shouldCapture = false;
                synchronized (captureLock) {
                    if (captureRequested) {
                        captureRequested = false;
                        shouldCapture = true;
                    }
                }

                if (shouldCapture) {
                    isCapturedMode = true;
                    try {
                        Bitmap raw = image.toBitmap();
                        if (raw != null) {
                            int rotation = image.getImageInfo().getRotationDegrees();
                            Bitmap rotated = InferenceUtils.rotateBitmap(raw, rotation);

                            // UI copy — safe to display, NOT recycled by inference
                            Bitmap uiCopy = rotated.copy(Bitmap.Config.ARGB_8888, false);

                            runOnUiThread(() -> showCapturedMode(uiCopy));

                            // Inference + upload on rotated (a separate copy for upload safety)
                            Bitmap uploadCopy = rotated.copy(Bitmap.Config.ARGB_8888, false);
                            runCaptureInference(rotated, uploadCopy);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        isCapturedMode = false;
                    } finally {
                        image.close();
                    }
                    return;
                }

                image.close();
            }
        });

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis);
        } catch (Exception e) {
            Toast.makeText(this, "Không thể liên kết Camera", Toast.LENGTH_LONG).show();
        }
    }

    private void showCapturedMode(Bitmap uiBitmap) {
        // Recycle previous display bitmap
        if (displayedBitmap != null) {
            displayedBitmap.recycle();
        }
        displayedBitmap = uiBitmap;

        capturedImageView.setImageBitmap(displayedBitmap);
        capturedImageView.setVisibility(View.VISIBLE);
        guideBox.setVisibility(View.GONE);
        btnCapture.setVisibility(View.GONE);
        btnRetake.setVisibility(View.VISIBLE);

        // Show result card in "analysing" state
        resultText.setText("");
        analysingText.setVisibility(View.VISIBLE);
        resultCard.setVisibility(View.VISIBLE);
    }

    private void runCaptureInference(Bitmap capturedFrame, Bitmap uploadCopy) {
        // Crop on the background thread
        Bitmap cropped = InferenceUtils.cropCenterSquare(capturedFrame, true);

        // Majority vote inference (blocking — already on background thread)
        InferenceResult result = InferenceUtils.runMajorityVoteInference(tfliteHelper, cropped);
        cropped.recycle();

        final String displayMsg = result != null
                ? String.format("%s\n%.1f%% do chac chan", result.getLabel(), result.getConfidence() * 100)
                : "Khong xac dinh duoc bien bao";

        // Upload to Cloudinary then save Firestore — must NOT block UI thread
        if (result != null) {
            final InferenceResult finalResult = result;
            CloudinaryUploader.uploadBitmap(uploadCopy, new CloudinaryUploader.UploadCallback() {
                @Override
                public void onSuccess(String imageUrl) {
                    uploadCopy.recycle();
                    firestoreRepository.saveScanResult(
                            finalResult.getLabel(), finalResult.getConfidence(),
                            "capture", imageUrl,
                            new FirestoreRepository.SaveCallback() {
                                @Override
                                public void onSuccess() {
                                    runOnUiThread(() ->
                                            Toast.makeText(CapturePhotoActivity.this,
                                                    "Da luu", Toast.LENGTH_SHORT).show());
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    String msg = e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh";
                                    runOnUiThread(() ->
                                            Toast.makeText(CapturePhotoActivity.this,
                                                    "Luu that bai: " + msg, Toast.LENGTH_LONG).show());
                                }
                            });
                }

                @Override
                public void onFailure(Exception e) {
                    uploadCopy.recycle();
                    runOnUiThread(() ->
                            Toast.makeText(CapturePhotoActivity.this,
                                    "Upload that bai: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        } else {
            // No valid result — recycle the upload copy
            uploadCopy.recycle();
        }

        runOnUiThread(() -> {
            resultText.setText(displayMsg);
            analysingText.setVisibility(View.GONE);
        });
    }

    private void resetToPreviewMode() {
        isCapturedMode = false;
        capturedImageView.setVisibility(View.GONE);
        capturedImageView.setImageBitmap(null);

        if (displayedBitmap != null) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }

        guideBox.setVisibility(View.VISIBLE);
        btnCapture.setVisibility(View.VISIBLE);
        btnRetake.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        resultText.setText("");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Cần quyền camera để chụp ảnh", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tfliteHelper != null) tfliteHelper.close();
        if (inferenceExecutor != null) inferenceExecutor.shutdown();
        if (displayedBitmap != null) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
    }
}

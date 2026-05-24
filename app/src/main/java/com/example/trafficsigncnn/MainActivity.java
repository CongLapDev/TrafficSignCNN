package com.example.trafficsigncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.content.Intent;
import android.net.Uri;
import java.io.InputStream;
import android.graphics.BitmapFactory;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

    private TFLiteHelper tfliteHelper;
    private PreviewView previewView;
    private TextView resultText;
    private ExecutorService analysisExecutor;

    private FloatingActionButton btnCapture;
    private FloatingActionButton btnGallery;
    private FloatingActionButton btnClosePreview;
    private ImageView capturedFullScreenPreview;

    private Bitmap activePreviewBitmap;
    private boolean captureRequested = false;
    private final Object captureLock = new Object();

    // Fields for visual smoothing and out-of-sign state tracking
    private int lowConfidenceCount = 0;
    private String lastLabel = "";
    private int sameLabelCount = 0;

    private boolean isCapturedMode = false;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            processGalleryImage(selectedImageUri);
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        previewView = findViewById(R.id.previewView);
        resultText = findViewById(R.id.resultText);
        analysisExecutor = Executors.newSingleThreadExecutor();

        try {
            printViewTree(findViewById(R.id.main), 0);
        } catch (Exception e) {
            Log.e("TrafficSignCNN", "Error printing view tree", e);
        }

        btnCapture = findViewById(R.id.btnCapture);
        btnGallery = findViewById(R.id.btnGallery);
        btnClosePreview = findViewById(R.id.btnClosePreview);
        capturedFullScreenPreview = findViewById(R.id.capturedFullScreenPreview);

        Log.d("TrafficSignCNN", "btnCapture initialized: " + btnCapture);
        Log.d("TrafficSignCNN", "btnGallery initialized: " + btnGallery);
        Log.d("TrafficSignCNN", "btnClosePreview initialized: " + btnClosePreview);
        Log.d("TrafficSignCNN", "capturedFullScreenPreview initialized: " + capturedFullScreenPreview);

        btnCapture.setOnClickListener(v -> {
            synchronized (captureLock) {
                captureRequested = true;
            }
        });

        btnGallery.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            galleryLauncher.launch(intent);
        });

        btnClosePreview.setOnClickListener(v -> resetToLiveMode());

        try {
            tfliteHelper = new TFLiteHelper(this);
            Toast.makeText(this, "Mô hình đã tải thành công!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Tải mô hình thất bại", Toast.LENGTH_LONG).show();
            e.printStackTrace();
            resultText.setText("Không thể tải mô hình TFLite");
            return;
        }

        // Request runtime Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[] { Manifest.permission.CAMERA },
                    CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Không thể khởi động camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        // Preview usecase
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ImageAnalysis usecase - STRATEGY_KEEP_ONLY_LATEST prevents buffering lag
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, new ImageAnalysis.Analyzer() {
            private long lastAnalyzedTimestamp = 0L;

            @Override
            public void analyze(@NonNull ImageProxy image) {
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
                        Bitmap bitmap = image.toBitmap();
                        if (bitmap != null) {
                            int rotationDegrees = image.getImageInfo().getRotationDegrees();
                            Bitmap rotatedBitmap = rotateBitmap(bitmap, rotationDegrees);

                            Bitmap uiBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, false);
                            runOnUiThread(() -> {
                                Bitmap oldBitmap = activePreviewBitmap;
                                activePreviewBitmap = uiBitmap;
                                capturedFullScreenPreview.setImageBitmap(activePreviewBitmap);
                                if (oldBitmap != null) {
                                    oldBitmap.recycle();
                                }
                                capturedFullScreenPreview.setVisibility(View.VISIBLE);
                                btnCapture.setVisibility(View.GONE);
                                btnGallery.setVisibility(View.GONE);
                                btnClosePreview.setVisibility(View.VISIBLE);
                            });

                            runCaptureInference(rotatedBitmap, true);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                    }
                    return;
                }

                long currentTimestamp = System.currentTimeMillis();
                // 2 frames per second => 500ms interval
                if (currentTimestamp - lastAnalyzedTimestamp >= 500) {
                    lastAnalyzedTimestamp = currentTimestamp;

                    try {
                        // Convert YUV_420_888 frame to RGB Bitmap
                        Bitmap bitmap = image.toBitmap();
                        if (bitmap != null) {
                            // Rotate target bitmap relative to Exif metadata
                            int rotationDegrees = image.getImageInfo().getRotationDegrees();
                            Bitmap rotatedBitmap = rotateBitmap(bitmap, rotationDegrees);

                            // Crop center square before inference for live mode
                            Bitmap croppedBitmap = cropCenterSquare(rotatedBitmap);

                            // Run TFLite inference
                            InferenceResult result = tfliteHelper.classifyImage(croppedBitmap);

                            if (result != null) {
                                if (result.getConfidence() < 0.85f) {
                                    lowConfidenceCount++;
                                    // Reset label smoothing state
                                    lastLabel = "";
                                    sameLabelCount = 0;

                                    final String warningMsg;
                                    if (lowConfidenceCount >= 5) {
                                        warningMsg = "Không thấy biển báo\nĐưa biển vào khung";
                                    } else {
                                        warningMsg = "Không nhận diện rõ\nHãy đưa biển gần hơn";
                                    }

                                    runOnUiThread(() -> resultText.setText(warningMsg));
                                } else {
                                    // Reset low confidence counter
                                    lowConfidenceCount = 0;

                                    // Label smoothing logic
                                    String currentLabel = result.getLabel();
                                    if (currentLabel.equals(lastLabel)) {
                                        sameLabelCount++;
                                    } else {
                                        lastLabel = currentLabel;
                                        sameLabelCount = 1;
                                    }

                                    if (sameLabelCount >= 3) {
                                        final String successMsg = String.format("%s (%.2f%%)",
                                                currentLabel, result.getConfidence() * 100);
                                        runOnUiThread(() -> resultText.setText(successMsg));
                                    }
                                }
                            }

                            croppedBitmap.recycle();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                // ALWAYS close the ImageProxy to continue streaming frames
                image.close();
            }
        });

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis);
        } catch (Exception e) {
            Toast.makeText(this, "Không thể liên kết Camera use cases: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void processGalleryImage(Uri uri) {
        try {
            InputStream imageStream = getContentResolver().openInputStream(uri);
            Bitmap selectedBitmap = BitmapFactory.decodeStream(imageStream);
            if (selectedBitmap != null) {
                isCapturedMode = true;

                Bitmap oldBitmap = activePreviewBitmap;
                activePreviewBitmap = selectedBitmap;
                capturedFullScreenPreview.setImageBitmap(activePreviewBitmap);
                if (oldBitmap != null) {
                    oldBitmap.recycle();
                }

                capturedFullScreenPreview.setVisibility(View.VISIBLE);

                btnCapture.setVisibility(View.GONE);
                btnGallery.setVisibility(View.GONE);
                btnClosePreview.setVisibility(View.VISIBLE);

                runCaptureInference(selectedBitmap, false);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Không thể tải ảnh từ thư viện", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void runCaptureInference(Bitmap capturedFrame, boolean recycleSrc) {
        if (capturedFrame == null) return;

        // Crop center guide box
        Bitmap cropped = cropCenterSquare(capturedFrame, recycleSrc);

        // Run inference in background
        analysisExecutor.execute(() -> {
            // Run inference 3 times on the cropped bitmap
            String[] labels = new String[3];
            float[] confidences = new float[3];
            for (int i = 0; i < 3; i++) {
                InferenceResult res = tfliteHelper.classifyImage(cropped);
                if (res != null) {
                    labels[i] = res.getLabel();
                    confidences[i] = res.getConfidence();
                } else {
                    labels[i] = "Không xác định";
                    confidences[i] = 0.0f;
                }
            }
            cropped.recycle();

            // Count frequencies
            java.util.Map<String, Integer> freqMap = new java.util.HashMap<>();
            for (String label : labels) {
                freqMap.put(label, freqMap.getOrDefault(label, 0) + 1);
            }

            // Find majority label
            String majorityLabel = labels[0];
            int maxCount = 0;
            for (java.util.Map.Entry<String, Integer> entry : freqMap.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    majorityLabel = entry.getKey();
                }
            }

            // Average confidence for the majority label
            float sumConfidence = 0.0f;
            int countConfidence = 0;
            for (int i = 0; i < 3; i++) {
                if (labels[i].equals(majorityLabel)) {
                    sumConfidence += confidences[i];
                    countConfidence++;
                }
            }
            float avgConfidence = countConfidence > 0 ? (sumConfidence / countConfidence) : 0.0f;

            // Display final result on UI thread
            final String finalLabel = majorityLabel;
            final float finalConfidence = avgConfidence;
            runOnUiThread(() -> {
                resultText.setText(String.format("%s (%.2f%%)", finalLabel, finalConfidence * 100));
            });
        });
    }

    private void resetToLiveMode() {
        isCapturedMode = false;
        capturedFullScreenPreview.setVisibility(View.GONE);
        capturedFullScreenPreview.setImageBitmap(null);

        if (activePreviewBitmap != null) {
            activePreviewBitmap.recycle();
            activePreviewBitmap = null;
        }

        btnCapture.setVisibility(View.VISIBLE);
        btnGallery.setVisibility(View.VISIBLE);
        btnClosePreview.setVisibility(View.GONE);

        resultText.setText("Đang khởi tạo camera...");
    }

    private void printViewTree(View view, int depth) {
        if (view == null) return;
        StringBuilder space = new StringBuilder();
        for (int i = 0; i < depth; i++) space.append("  ");
        String idName = "";
        try {
            if (view.getId() != View.NO_ID) {
                idName = view.getResources().getResourceEntryName(view.getId());
            }
        } catch (Exception e) {}
        Log.d("TrafficSignCNN", space.toString() + view.getClass().getSimpleName() + " (id: " + idName + ")");
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                printViewTree(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0)
            return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.recycle();
        return rotated;
    }

    private Bitmap cropCenterSquare(Bitmap src) {
        return cropCenterSquare(src, true);
    }

    private Bitmap cropCenterSquare(Bitmap src, boolean recycleSrc) {
        int width = src.getWidth();
        int height = src.getHeight();
        int cropSize = Math.min(width, height) / 2;
        int x = (width - cropSize) / 2;
        int y = (height - cropSize) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropSize, cropSize);
        if (recycleSrc) {
            src.recycle();
        }
        return cropped;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Yêu cầu quyền truy cập camera để chạy nhận diện", Toast.LENGTH_LONG).show();
                resultText.setText("Không có quyền truy cập camera");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tfliteHelper != null) {
            tfliteHelper.close();
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        if (activePreviewBitmap != null) {
            activePreviewBitmap.recycle();
            activePreviewBitmap = null;
        }
    }
}
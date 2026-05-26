package com.example.trafficsigncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live camera scan screen.
 *
 * Save flow (v2):
 * - Auto-save removed to prevent unreliable silent failures.
 * - When confidence >= SAVE_THRESHOLD (0.75): btnSaveHistory is ENABLED.
 * - User taps "Lưu vào lịch sử" → explicit Firestore save via SaveCallback.
 * - On success: Toast + disable button for SAVE_COOLDOWN_MS (2 000 ms).
 * - On failure: Toast with error message.
 */
public class LiveScanActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 2001;

    // Confidence thresholds
    private static final float THRESHOLD_HIGH = 0.85f;
    private static final float CONFIDENCE_GREEN = 0.80f;
    private static final float CONFIDENCE_ORANGE = 0.75f;

    /** Minimum confidence required to enable the save button. */
    private static final float SAVE_THRESHOLD = 0.75f;

    /** Cooldown after a successful save — prevents double-taps. */
    private static final long SAVE_COOLDOWN_MS = 2_000L;

    // Colors
    private static final int COLOR_GREEN = Color.parseColor("#4CAF50");
    private static final int COLOR_ORANGE = Color.parseColor("#FF9800");
    private static final int COLOR_RED = Color.parseColor("#F44336");
    private static final int COLOR_IDLE = Color.parseColor("#BDBDBD");

    private TFLiteHelper tfliteHelper;
    private PreviewView previewView;
    private TextView tvSignName;
    private TextView tvConfidencePercent;
    private ProgressBar confidenceBar;
    private MaterialButton btnSaveHistory;
    private ExecutorService analysisExecutor;
    private FirestoreRepository firestoreRepository;
    private Handler mainHandler;

    // Label smoothing state
    private int lowConfidenceCount = 0;
    private String lastLabel = "";
    private int sameLabelCount = 0;

    // Current stable prediction — updated only after smoothing passes
    private volatile String currentStableLabel = null;
    private volatile float currentStableConfidence = 0f;

    // Last rendered frame — kept for Cloudinary upload on save
    private Bitmap currentDisplayBitmap = null;
    private final Object bitmapLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_scan);

        previewView = findViewById(R.id.previewView);
        tvSignName = findViewById(R.id.tvSignName);
        tvConfidencePercent = findViewById(R.id.tvConfidencePercent);
        confidenceBar = findViewById(R.id.confidenceBar);
        btnSaveHistory = findViewById(R.id.btnSaveHistory);
        analysisExecutor = Executors.newSingleThreadExecutor();
        firestoreRepository = FirestoreRepository.getInstance();
        mainHandler = new Handler(Looper.getMainLooper());

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        btnSaveHistory.setOnClickListener(v -> onSaveClicked());

        // Idle state: gray bar, no value, save disabled
        setProgressBarColor(COLOR_IDLE);
        confidenceBar.setProgress(0);
        btnSaveHistory.setEnabled(false);

        try {
            tfliteHelper = new TFLiteHelper(this);
        } catch (Exception e) {
            Toast.makeText(this, "Tải mô hình thất bại", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.CAMERA },
                    CAMERA_PERMISSION_CODE);
        }
    }

    // ─────────────────────────────────────────────
    // Save button handler
    // ─────────────────────────────────────────────

    private void onSaveClicked() {
        String label = currentStableLabel;
        float confidence = currentStableConfidence;

        if (label == null || confidence < SAVE_THRESHOLD) {
            Toast.makeText(this, "Chua co ket qua du tin cay de luu", Toast.LENGTH_SHORT).show();
            return;
        }

        // Freeze a copy of the last live frame for upload securely
        Bitmap snapshot = null;
        synchronized (bitmapLock) {
            if (currentDisplayBitmap != null && !currentDisplayBitmap.isRecycled()) {
                snapshot = currentDisplayBitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
        }

        if (snapshot == null) {
            Toast.makeText(this, "Khong the lay anh tu camera, vui long thu lai", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveHistory.setEnabled(false);
        btnSaveHistory.setText("Dang tai anh...");

        final Bitmap finalSnapshot = snapshot;

        // Step 1: Upload to Cloudinary (OkHttp background thread)
        CloudinaryUploader.uploadBitmap(finalSnapshot, new CloudinaryUploader.UploadCallback() {
            @Override
            public void onSuccess(String imageUrl) {
                // Step 2: Save Firestore with imageUrl
                firestoreRepository.saveScanResult(
                        label, confidence, "live", imageUrl,
                        new FirestoreRepository.SaveCallback() {
                            @Override
                            public void onSuccess() {
                                runOnUiThread(() -> {
                                    if (finalSnapshot != null) finalSnapshot.recycle();
                                    Toast.makeText(LiveScanActivity.this,
                                            "Da luu vao lich su", Toast.LENGTH_SHORT).show();
                                    btnSaveHistory.setText("Luu vao lich su");
                                    mainHandler.postDelayed(() -> {
                                        if (!isFinishing()) {
                                            btnSaveHistory.setEnabled(
                                                    currentStableConfidence >= SAVE_THRESHOLD);
                                        }
                                    }, SAVE_COOLDOWN_MS);
                                });
                            }

                            @Override
                            public void onFailure(Exception e) {
                                runOnUiThread(() -> {
                                    if (finalSnapshot != null) finalSnapshot.recycle();
                                    String msg = e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh";
                                    Toast.makeText(LiveScanActivity.this,
                                            "Luu that bai: " + msg, Toast.LENGTH_LONG).show();
                                    btnSaveHistory.setText("Luu vao lich su");
                                    btnSaveHistory.setEnabled(currentStableConfidence >= SAVE_THRESHOLD);
                                });
                            }
                        });
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(() -> {
                    if (finalSnapshot != null) finalSnapshot.recycle();
                    Toast.makeText(LiveScanActivity.this,
                            "Upload that bai: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    btnSaveHistory.setText("Luu vao lich su");
                    btnSaveHistory.setEnabled(currentStableConfidence >= SAVE_THRESHOLD);
                });
            }
        });
    }

    // ─────────────────────────────────────────────
    // Camera binding
    // ─────────────────────────────────────────────

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
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, new ImageAnalysis.Analyzer() {
            private long lastTimestamp = 0L;

            @Override
            public void analyze(@NonNull ImageProxy image) {
                long now = System.currentTimeMillis();
                // ~2 FPS throttle (500ms)
                if (now - lastTimestamp < 500) {
                    image.close();
                    return;
                }
                lastTimestamp = now;

                try {
                    Bitmap bitmap = image.toBitmap();
                    if (bitmap != null) {
                        int rotation = image.getImageInfo().getRotationDegrees();
                        Bitmap rotated = InferenceUtils.rotateBitmap(bitmap, rotation);

                        // Keep a copy for snapshot on save securely
                        Bitmap newCopy = rotated.copy(Bitmap.Config.ARGB_8888, false);
                        Bitmap prev;
                        synchronized (bitmapLock) {
                            prev = currentDisplayBitmap;
                            currentDisplayBitmap = newCopy;
                        }
                        if (prev != null) {
                            prev.recycle();
                        }

                        Bitmap cropped = InferenceUtils.cropCenterSquare(rotated);

                        InferenceResult result = tfliteHelper.classifyImage(cropped);
                        cropped.recycle();

                        if (result != null) {
                            processLiveResult(result);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    image.close();
                }
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

    // ─────────────────────────────────────────────
    // Inference result → UI
    // ─────────────────────────────────────────────

    private void processLiveResult(@NonNull InferenceResult result) {
        final float confidence = result.getConfidence();
        final int pct = Math.round(confidence * 100f);

        if (confidence < THRESHOLD_HIGH) {
            lowConfidenceCount++;
            lastLabel = "";
            sameLabelCount = 0;

            final String warning = lowConfidenceCount >= 5
                    ? "Không thấy biển báo\nĐưa biển vào khung"
                    : "Không nhận diện rõ\nHãy đưa biển gần hơn";

            runOnUiThread(() -> {
                updateResultCard(warning, pct, COLOR_RED);
                // No stable prediction — disable save
                currentStableLabel = null;
                currentStableConfidence = 0f;
                btnSaveHistory.setEnabled(false);
            });

        } else {
            lowConfidenceCount = 0;

            String currentLabel = result.getLabel();
            if (currentLabel.equals(lastLabel)) {
                sameLabelCount++;
            } else {
                lastLabel = currentLabel;
                sameLabelCount = 1;
            }

            // Only update UI after 3 consecutive same labels (smoothing)
            if (sameLabelCount >= 3) {
                final int barColor = confidence >= CONFIDENCE_GREEN ? COLOR_GREEN
                        : confidence >= CONFIDENCE_ORANGE ? COLOR_ORANGE
                                : COLOR_RED;

                final String finalLabel = currentLabel;
                final float finalConf = confidence;

                runOnUiThread(() -> {
                    updateResultCard(finalLabel, pct, barColor);
                    // Store stable prediction for save button
                    currentStableLabel = finalLabel;
                    currentStableConfidence = finalConf;
                    // Enable save when confidence >= threshold
                    btnSaveHistory.setEnabled(finalConf >= SAVE_THRESHOLD
                            && "Lưu vào lịch sử".equals(btnSaveHistory.getText().toString()));
                });
            }
        }
    }

    private void updateResultCard(String signName, int pct, int barColor) {
        tvSignName.setText(signName);
        tvConfidencePercent.setText(pct + "%");
        confidenceBar.setProgress(pct);
        setProgressBarColor(barColor);
    }

    private void setProgressBarColor(int color) {
        Drawable drawable = confidenceBar.getProgressDrawable();
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            Drawable progressLayer = layers.findDrawableByLayerId(android.R.id.progress);
            if (progressLayer != null) {
                progressLayer.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
        } else if (drawable != null) {
            drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    // ─────────────────────────────────────────────
    // Permissions + lifecycle
    // ─────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Cần quyền camera để quét trực tiếp", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (tfliteHelper != null)
            tfliteHelper.close();
        if (analysisExecutor != null)
            analysisExecutor.shutdown();
            
        synchronized (bitmapLock) {
            if (currentDisplayBitmap != null) {
                currentDisplayBitmap.recycle();
                currentDisplayBitmap = null;
            }
        }
    }
}

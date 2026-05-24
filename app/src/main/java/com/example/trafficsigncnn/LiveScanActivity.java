package com.example.trafficsigncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
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

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live camera scan screen — minimalist redesign.
 *
 * UI:
 *  - Full-screen PreviewView with ViewfinderView corner brackets
 *  - Floating white card at bottom with:
 *      · tvSignName       — detected sign label
 *      · tvConfidencePercent — confidence as "XX%"
 *      · confidenceBar    — ProgressBar, color-coded:
 *            > 80%  → #4CAF50 (green)
 *            50–80% → #FF9800 (orange)
 *            < 50%  → #F44336 (red)
 *
 * Logic:
 *  - Smoothing: 3 consecutive same labels required before showing
 *  - Low-confidence guard (< 0.85): shows warning text in sign name view
 *  - ProgressBar always reflects raw confidence from last inference
 */
public class LiveScanActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 2001;

    // Confidence thresholds (matching existing smoothing logic)
    private static final float THRESHOLD_HIGH    = 0.85f;
    private static final float CONFIDENCE_GREEN  = 0.80f;
    private static final float CONFIDENCE_ORANGE = 0.50f;

    // Colors
    private static final int COLOR_GREEN  = Color.parseColor("#4CAF50");
    private static final int COLOR_ORANGE = Color.parseColor("#FF9800");
    private static final int COLOR_RED    = Color.parseColor("#F44336");
    private static final int COLOR_IDLE   = Color.parseColor("#BDBDBD");

    private TFLiteHelper tfliteHelper;
    private PreviewView previewView;
    private TextView tvSignName;
    private TextView tvConfidencePercent;
    private ProgressBar confidenceBar;
    private ExecutorService analysisExecutor;

    // Label smoothing state
    private int    lowConfidenceCount = 0;
    private String lastLabel          = "";
    private int    sameLabelCount     = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_scan);

        previewView         = findViewById(R.id.previewView);
        tvSignName          = findViewById(R.id.tvSignName);
        tvConfidencePercent = findViewById(R.id.tvConfidencePercent);
        confidenceBar       = findViewById(R.id.confidenceBar);
        analysisExecutor    = Executors.newSingleThreadExecutor();

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Idle state: gray bar, no value
        setProgressBarColor(COLOR_IDLE);
        confidenceBar.setProgress(0);

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
        final int   pct        = Math.round(confidence * 100f);

        if (confidence < THRESHOLD_HIGH) {
            lowConfidenceCount++;
            lastLabel      = "";
            sameLabelCount = 0;

            final String warning = lowConfidenceCount >= 5
                    ? "Không thấy biển báo\nĐưa biển vào khung"
                    : "Không nhận diện rõ\nHãy đưa biển gần hơn";

            runOnUiThread(() -> updateResultCard(warning, pct, COLOR_RED));

        } else {
            lowConfidenceCount = 0;

            String currentLabel = result.getLabel();
            if (currentLabel.equals(lastLabel)) {
                sameLabelCount++;
            } else {
                lastLabel      = currentLabel;
                sameLabelCount = 1;
            }

            // Only update label after 3 consecutive same labels (smoothing)
            if (sameLabelCount >= 3) {
                final int barColor = confidence >= CONFIDENCE_GREEN ? COLOR_GREEN
                                   : confidence >= CONFIDENCE_ORANGE ? COLOR_ORANGE
                                   : COLOR_RED;

                final String finalLabel = currentLabel;
                runOnUiThread(() -> updateResultCard(finalLabel, pct, barColor));
            }
        }
    }

    /**
     * Update the result card UI atomically.
     *
     * @param signName  Text to show in tvSignName (label or warning)
     * @param pct       Integer 0–100 for ProgressBar
     * @param barColor  Color int for the progress fill
     */
    private void updateResultCard(String signName, int pct, int barColor) {
        tvSignName.setText(signName);
        tvConfidencePercent.setText(pct + "%");
        confidenceBar.setProgress(pct);
        setProgressBarColor(barColor);
    }

    /**
     * Tint the filled portion of the custom confidence ProgressBar.
     * Works with the layer-list defined in confidence_progress.xml.
     */
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
        if (tfliteHelper != null)    tfliteHelper.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
    }
}

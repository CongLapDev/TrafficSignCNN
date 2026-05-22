package com.example.trafficsigncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

    // Fields for visual smoothing and out-of-sign state tracking
    private int lowConfidenceCount = 0;
    private String lastLabel = "";
    private int sameLabelCount = 0;

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

                            // Crop center square before inference
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
        int width = src.getWidth();
        int height = src.getHeight();
        int cropSize = Math.min(width, height) / 2;
        int x = (width - cropSize) / 2;
        int y = (height - cropSize) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropSize, cropSize);
        src.recycle();
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
    }
}
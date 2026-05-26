package com.example.trafficsigncnn;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gallery recognition screen.
 *
 * Flow:
 * 1. User taps "Chọn Ảnh" → opens image picker
 * 2. Selected image displayed in card
 * 3. Majority-vote inference runs in background
 * 4. Result shown below the image
 * 5. User can pick another image (previous bitmap recycled)
 */
public class GalleryRecognitionActivity extends AppCompatActivity {

    private TFLiteHelper tfliteHelper;
    private ImageView selectedImageView;
    private View placeholderView;
    private TextView resultText;
    private TextView analysingText;
    private MaterialCardView resultCard;

    private ExecutorService inferenceExecutor;
    private FirestoreRepository firestoreRepository;

    // Displayed bitmap — recycled when a new image is picked
    private Bitmap displayedBitmap = null;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            processSelectedImage(uri);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery_recognition);

        selectedImageView = findViewById(R.id.selectedImageView);
        placeholderView = findViewById(R.id.placeholderView);
        resultText = findViewById(R.id.resultText);
        analysingText = findViewById(R.id.analysingText);
        resultCard = findViewById(R.id.resultCard);

        inferenceExecutor = Executors.newSingleThreadExecutor();
        firestoreRepository = FirestoreRepository.getInstance();

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        MaterialButton btnPickImage = findViewById(R.id.btnPickImage);
        btnPickImage.setOnClickListener(v -> openGallery());

        try {
            tfliteHelper = new TFLiteHelper(this);
        } catch (Exception e) {
            Toast.makeText(this, "Tải mô hình thất bại", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void processSelectedImage(Uri uri) {
        try {
            InputStream stream = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) {
                Toast.makeText(this, "Không thể tải ảnh", Toast.LENGTH_SHORT).show();
                return;
            }

            // Recycle previous display bitmap
            if (displayedBitmap != null) {
                displayedBitmap.recycle();
            }
            displayedBitmap = bitmap;

            // Show image
            placeholderView.setVisibility(View.GONE);
            selectedImageView.setImageBitmap(displayedBitmap);
            selectedImageView.setVisibility(View.VISIBLE);

            // Show result card in loading state
            resultText.setText("");
            analysingText.setVisibility(View.VISIBLE);
            resultCard.setVisibility(View.VISIBLE);

            // Run inference on background thread
            // Pass a copy so the display bitmap is never recycled by inference
            final Bitmap inferenceBitmap = displayedBitmap.copy(Bitmap.Config.ARGB_8888, false);
            inferenceExecutor.execute(() -> {
                Bitmap cropped = InferenceUtils.cropCenterSquare(inferenceBitmap, true);
                InferenceResult result = InferenceUtils.runMajorityVoteInference(tfliteHelper, cropped);
                cropped.recycle();

                // Persist to Firestore with explicit result callbacks
                if (result != null) {
                    firestoreRepository.saveScanResult(
                            result.getLabel(), result.getConfidence(), "gallery", "",
                            new FirestoreRepository.SaveCallback() {
                                @Override
                                public void onSuccess() {
                                    runOnUiThread(() -> Toast.makeText(GalleryRecognitionActivity.this,
                                            "✅ Đã lưu", Toast.LENGTH_SHORT).show());
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    String msg = e.getMessage() != null
                                            ? e.getMessage()
                                            : "Lỗi không xác định";
                                    runOnUiThread(() -> Toast.makeText(GalleryRecognitionActivity.this,
                                            "❌ Lưu thất bại: " + msg,
                                            Toast.LENGTH_LONG).show());
                                }
                            });
                }

                final String msg = result != null
                        ? String.format("%s\n%.1f%% độ chắc chắn", result.getLabel(), result.getConfidence() * 100)
                        : "Không xác định được biển báo";

                runOnUiThread(() -> {
                    resultText.setText(msg);
                    analysingText.setVisibility(View.GONE);
                });
            });

        } catch (Exception e) {
            Toast.makeText(this, "Lỗi khi xử lý ảnh", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tfliteHelper != null)
            tfliteHelper.close();
        if (inferenceExecutor != null)
            inferenceExecutor.shutdown();
        if (displayedBitmap != null) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
    }
}

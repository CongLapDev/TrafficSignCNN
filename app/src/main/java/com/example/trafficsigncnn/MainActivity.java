package com.example.trafficsigncnn;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    TFLiteHelper tfliteHelper;

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

        try {
            tfliteHelper = new TFLiteHelper(this);

            Bitmap bitmap = BitmapFactory.decodeResource(
                    getResources(),
                    R.drawable.test
            );

            InferenceResult result =
                    tfliteHelper.classifyImage(bitmap);

            String message =
                    "Prediction: " + result.getLabel() +
                            "\nConfidence: " +
                            String.format("%.2f", result.getConfidence());

            Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Inference failed",
                    Toast.LENGTH_LONG
            ).show();

            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (tfliteHelper != null) {
            tfliteHelper.close();
        }

    }
}
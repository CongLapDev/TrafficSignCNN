package com.example.trafficsigncnn;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        MaterialCardView cardLiveScan = findViewById(R.id.cardLiveScan);
        MaterialCardView cardCapturePhoto = findViewById(R.id.cardCapturePhoto);
        MaterialCardView cardGallery = findViewById(R.id.cardGallery);

        cardLiveScan.setOnClickListener(v ->
                startActivity(new Intent(this, LiveScanActivity.class)));

        cardCapturePhoto.setOnClickListener(v ->
                startActivity(new Intent(this, CapturePhotoActivity.class)));

        cardGallery.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryRecognitionActivity.class)));
    }
}

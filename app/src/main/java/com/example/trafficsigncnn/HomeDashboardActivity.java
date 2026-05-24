package com.example.trafficsigncnn;

import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.card.MaterialCardView;

/**
 * Home Dashboard — primary entry screen after login.
 *
 * Responsibilities:
 *  - Display user greeting + avatar initial
 *  - Show daily scan summary stats
 *  - Provide 2-column feature grid navigation
 *  - Show AI model readiness status
 *
 * Firebase migration:
 *  - Replace UserSession.placeholder() with a real Firebase read
 *  - Add an Auth guard: if FirebaseAuth.getInstance().getCurrentUser() == null → finish() → LoginActivity
 */
public class HomeDashboardActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge display (OLED dark mode — no system bar tinting)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_home_dashboard);

        // Apply window insets to push content below status bar
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
                (v, insets) -> {
                    int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    v.setPadding(0, top, 0, 0);
                    return insets;
                });

        // ── Load user session (placeholder; swap with Firebase later) ──
        UserSession session = UserSession.placeholder();
        bindHeader(session);
        bindSummaryCard(session);
        bindFooter(session);
        setupFeatureGrid();
    }

    // ─────────────────────────────────────────────
    // Header
    // ─────────────────────────────────────────────

    private void bindHeader(UserSession session) {
        TextView tvGreeting = findViewById(R.id.tvGreeting);
        TextView tvAvatarInitial = findViewById(R.id.tvAvatarInitial);
        FrameLayout btnAvatar = findViewById(R.id.btnAvatar);

        tvGreeting.setText("Xin chào, " + session.getDisplayName());
        tvAvatarInitial.setText(session.getAvatarInitial());

        btnAvatar.setOnClickListener(v ->
                Toast.makeText(this, "Hồ sơ — sắp ra mắt", Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────
    // Summary card
    // ─────────────────────────────────────────────

    private void bindSummaryCard(UserSession session) {
        TextView tvTotalScans    = findViewById(R.id.tvTotalScans);
        TextView tvMostDetected  = findViewById(R.id.tvMostDetected);
        TextView tvModelVersion  = findViewById(R.id.tvModelVersion);

        tvTotalScans.setText(String.valueOf(session.getScansToday()));
        tvMostDetected.setText(session.getMostDetectedSign());
        tvModelVersion.setText(session.getModelVersion());
    }

    // ─────────────────────────────────────────────
    // Footer
    // ─────────────────────────────────────────────

    private void bindFooter(UserSession session) {
        TextView tvAppInfo = findViewById(R.id.tvAppInfo);
        tvAppInfo.setText(session.getAppInfoLine());
    }

    // ─────────────────────────────────────────────
    // Feature grid
    // ─────────────────────────────────────────────

    private void setupFeatureGrid() {
        // Live scan
        MaterialCardView cardLive = findViewById(R.id.cardFeatureLive);
        cardLive.setOnClickListener(v ->
                startActivity(new Intent(this, LiveScanActivity.class)));

        // Capture photo
        MaterialCardView cardCapture = findViewById(R.id.cardFeatureCapture);
        cardCapture.setOnClickListener(v ->
                startActivity(new Intent(this, CapturePhotoActivity.class)));

        // Gallery recognition
        MaterialCardView cardGallery = findViewById(R.id.cardFeatureGallery);
        cardGallery.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryRecognitionActivity.class)));

        // History (not yet implemented)
        MaterialCardView cardHistory = findViewById(R.id.cardFeatureHistory);
        cardHistory.setOnClickListener(v ->
                Toast.makeText(this, "Lịch sử — sắp ra mắt", Toast.LENGTH_SHORT).show());

        // Statistics (not yet implemented)
        MaterialCardView cardStats = findViewById(R.id.cardFeatureStats);
        cardStats.setOnClickListener(v ->
                Toast.makeText(this, "Thống kê — sắp ra mắt", Toast.LENGTH_SHORT).show());

        // Profile (not yet implemented)
        MaterialCardView cardProfile = findViewById(R.id.cardFeatureProfile);
        cardProfile.setOnClickListener(v ->
                Toast.makeText(this, "Hồ sơ — sắp ra mắt", Toast.LENGTH_SHORT).show());
    }
}

package com.example.trafficsigncnn;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Home Dashboard — primary entry screen after login.
 *
 * Responsibilities:
 *  - Auth guard: redirect to LoginActivity if not authenticated
 *  - Display user greeting + avatar initial from FirebaseAuth
 *  - Show daily scan summary stats (placeholder until Firestore)
 *  - Provide 2-column feature grid navigation
 *  - Logout button in header
 */
public class HomeDashboardActivity extends AppCompatActivity {

    private static final String TAG = "HomeDashboard";
    private FirebaseAuth mAuth;
    private FirestoreRepository repo;
    private TextView tvTotalScans;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        // ── Auth guard ──────────────────────────────────────────────
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            navigateToLogin();
            return; // Do not proceed with UI setup
        }

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

        // ── Load real user session from Firebase ────────────────────
        UserSession session = UserSession.fromFirebaseUser(currentUser);
        bindHeader(session);
        bindSummaryCard(session);
        bindFooter(session);
        setupFeatureGrid();
        setupLogout();

        repo = FirestoreRepository.getInstance();
        tvTotalScans = findViewById(R.id.tvTotalScans);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh totalScans from Firestore each time dashboard is visible
        loadTotalScans();
    }

    private void loadTotalScans() {
        repo.loadUserStats(new FirestoreRepository.StatsCallback() {
            @Override
            public void onSuccess(long totalScans) {
                runOnUiThread(() -> {
                    if (tvTotalScans != null) {
                        tvTotalScans.setText(String.valueOf(totalScans));
                    }
                });
            }
            @Override
            public void onFailure(Exception e) {
                Log.w(TAG, "loadTotalScans failed: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────
    // Header
    // ─────────────────────────────────────────────

    private void bindHeader(UserSession session) {
        TextView tvGreeting     = findViewById(R.id.tvGreeting);
        TextView tvAvatarInitial = findViewById(R.id.tvAvatarInitial);
        FrameLayout btnAvatar   = findViewById(R.id.btnAvatar);

        tvGreeting.setText("Xin chào, " + session.getDisplayName());
        tvAvatarInitial.setText(session.getAvatarInitial());

        btnAvatar.setOnClickListener(v ->
                Toast.makeText(this, "Hồ sơ — sắp ra mắt", Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────
    // Summary card
    // ─────────────────────────────────────────────

    private void bindSummaryCard(UserSession session) {
        TextView tvTotalScans   = findViewById(R.id.tvTotalScans);
        TextView tvMostDetected = findViewById(R.id.tvMostDetected);
        TextView tvModelVersion = findViewById(R.id.tvModelVersion);

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
    // Logout
    // ─────────────────────────────────────────────

    private void setupLogout() {
        // Try to find a logout button by ID — add it to the layout if needed
        View btnLogout = findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> showLogoutDialog());
        }
    }

    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Đăng xuất")
                .setMessage("Bạn có chắc muốn đăng xuất không?")
                .setPositiveButton("Đăng xuất", (dialog, which) -> performLogout())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void performLogout() {
        mAuth.signOut();
        Toast.makeText(this, getString(R.string.toast_logout_success), Toast.LENGTH_SHORT).show();
        navigateToLogin();
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finishAffinity();
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

        // History
        MaterialCardView cardHistory = findViewById(R.id.cardFeatureHistory);
        cardHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        // Statistics
        MaterialCardView cardStats = findViewById(R.id.cardFeatureStats);
        cardStats.setOnClickListener(v ->
                startActivity(new Intent(this, StatisticsActivity.class)));

        // Profile (not yet implemented)
        MaterialCardView cardProfile = findViewById(R.id.cardFeatureProfile);
        cardProfile.setOnClickListener(v ->
                Toast.makeText(this, "Hồ sơ — sắp ra mắt", Toast.LENGTH_SHORT).show());
    }
}

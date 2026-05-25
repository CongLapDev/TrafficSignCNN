package com.example.trafficsigncnn;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * SplashActivity — Premium animated splash + auth gate.
 *
 * Animation sequence (total ~2200ms):
 *   0ms    → Logo: scale 0.7→1.0 + alpha 0→1 (700ms, OvershootInterpolator)
 *   0ms    → Glow ring: pulse breathing (infinite, starts with logo)
 *   500ms  → Title: slide-up + fade-in (500ms)
 *   800ms  → Subtitle + powered-by: fade-in (600ms)
 *   2200ms → Navigate to HomeDashboard or Login
 *
 * Performance:
 *   - No blocking UI thread operations
 *   - Handler posts to main looper (lifecycle-safe)
 *   - hasNavigated flag prevents double-fire
 *   - ObjectAnimator (not Thread.sleep) for all timing
 *   - Edge-to-edge enabled (no status bar flash)
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION_MS = 2200L;

    // Prevent double navigation (e.g. if onResume fires again)
    private boolean hasNavigated = false;

    private final Handler navigationHandler = new Handler(Looper.getMainLooper());
    private Runnable navigationRunnable;

    // Glow ring animator — kept as field so we can cancel it in onDestroy
    private AnimatorSet glowAnimatorSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge: removes status/nav bar — prevents white flash during OS transition
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_splash);

        // Hide system UI for true fullscreen splash
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        // Start the animation sequence
        startSplashAnimations();

        // Schedule auth check + navigation after full splash duration
        scheduleNavigation();
    }

    // ─────────────────────────────────────────────
    // Animation Sequence
    // ─────────────────────────────────────────────

    private void startSplashAnimations() {
        ImageView glowRing          = findViewById(R.id.splashGlowRing);
        View logoContainer          = findViewById(R.id.splashLogoContainer);
        TextView tvTitle            = findViewById(R.id.tvSplashTitle);
        TextView tvSubtitle         = findViewById(R.id.tvSplashSubtitle);
        TextView tvPoweredBy        = findViewById(R.id.tvSplashPoweredBy);

        // ── 1. Logo: scale 0.7→1.0 + alpha 0→1 (OvershootInterpolator) ────
        logoContainer.setScaleX(0.7f);
        logoContainer.setScaleY(0.7f);
        logoContainer.setAlpha(0f);

        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logoContainer, "scaleX", 0.7f, 1.0f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logoContainer, "scaleY", 0.7f, 1.0f);
        ObjectAnimator logoAlpha  = ObjectAnimator.ofFloat(logoContainer, "alpha", 0f, 1f);

        logoScaleX.setDuration(700);
        logoScaleY.setDuration(700);
        logoAlpha.setDuration(500);

        logoScaleX.setInterpolator(new OvershootInterpolator(1.5f));
        logoScaleY.setInterpolator(new OvershootInterpolator(1.5f));

        AnimatorSet logoSet = new AnimatorSet();
        logoSet.playTogether(logoScaleX, logoScaleY, logoAlpha);
        logoSet.start();

        // ── 2. Glow ring: breathing pulse (scale + alpha, infinite) ────────
        startGlowPulse(glowRing);

        // ── 3. Title: slide-up + fade-in (delayed 500ms) ───────────────────
        tvTitle.setAlpha(0f);
        tvTitle.setTranslationY(40f);
        AnimatorSet titleSet = new AnimatorSet();
        ObjectAnimator titleAlpha    = ObjectAnimator.ofFloat(tvTitle, "alpha", 0f, 1f);
        ObjectAnimator titleTranslate = ObjectAnimator.ofFloat(tvTitle, "translationY", 40f, 0f);
        titleAlpha.setDuration(500);
        titleTranslate.setDuration(500);
        titleSet.playTogether(titleAlpha, titleTranslate);
        titleSet.setStartDelay(500);
        titleSet.start();

        // ── 4. Subtitle: fade-in (delayed 800ms) ───────────────────────────
        tvSubtitle.setAlpha(0f);
        ObjectAnimator subtitleAlpha = ObjectAnimator.ofFloat(tvSubtitle, "alpha", 0f, 1f);
        subtitleAlpha.setDuration(600);
        subtitleAlpha.setStartDelay(800);
        subtitleAlpha.start();

        // ── 5. Powered-by: fade-in (delayed 1000ms) ────────────────────────
        tvPoweredBy.setAlpha(0f);
        ObjectAnimator poweredAlpha = ObjectAnimator.ofFloat(tvPoweredBy, "alpha", 0f, 1f);
        poweredAlpha.setDuration(600);
        poweredAlpha.setStartDelay(1000);
        poweredAlpha.start();
    }

    /**
     * Breathing glow pulse:
     * Scale 1.0→1.3→1.0 + alpha 0.7→0.0→0.7 (infinite repeat)
     * Duration per cycle: 1600ms
     */
    private void startGlowPulse(ImageView glowRing) {
        glowRing.setAlpha(0f);
        glowRing.setScaleX(1.0f);
        glowRing.setScaleY(1.0f);

        ObjectAnimator glowScaleX = ObjectAnimator.ofFloat(glowRing, "scaleX", 1.0f, 1.35f);
        ObjectAnimator glowScaleY = ObjectAnimator.ofFloat(glowRing, "scaleY", 1.0f, 1.35f);
        ObjectAnimator glowAlpha  = ObjectAnimator.ofFloat(glowRing, "alpha", 0.7f, 0.0f);

        glowScaleX.setDuration(1600);
        glowScaleY.setDuration(1600);
        glowAlpha.setDuration(1600);

        glowScaleX.setRepeatCount(ValueAnimator.INFINITE);
        glowScaleY.setRepeatCount(ValueAnimator.INFINITE);
        glowAlpha.setRepeatCount(ValueAnimator.INFINITE);

        glowScaleX.setRepeatMode(ValueAnimator.RESTART);
        glowScaleY.setRepeatMode(ValueAnimator.RESTART);
        glowAlpha.setRepeatMode(ValueAnimator.RESTART);

        glowAnimatorSet = new AnimatorSet();
        glowAnimatorSet.playTogether(glowScaleX, glowScaleY, glowAlpha);
        glowAnimatorSet.setStartDelay(200); // Starts just after logo begins appearing
        glowAnimatorSet.start();
    }

    // ─────────────────────────────────────────────
    // Auth Check + Navigation
    // ─────────────────────────────────────────────

    private void scheduleNavigation() {
        navigationRunnable = this::performAuthCheckAndNavigate;
        navigationHandler.postDelayed(navigationRunnable, SPLASH_DURATION_MS);
    }

    private void performAuthCheckAndNavigate() {
        if (hasNavigated || isFinishing() || isDestroyed()) return;
        hasNavigated = true;

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

        Intent destination = (currentUser != null)
                ? new Intent(this, HomeDashboardActivity.class)
                : new Intent(this, LoginActivity.class);

        // Smooth fade transition — prevents white flash
        startActivity(destination);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // ─────────────────────────────────────────────
    // Lifecycle — Memory Leak Prevention
    // ─────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Cancel pending navigation to prevent leak when activity is destroyed early
        if (navigationRunnable != null) {
            navigationHandler.removeCallbacks(navigationRunnable);
        }

        // Stop infinite glow animation
        if (glowAnimatorSet != null && glowAnimatorSet.isRunning()) {
            glowAnimatorSet.cancel();
        }
    }
}

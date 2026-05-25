package com.example.trafficsigncnn;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

/**
 * LoginActivity — Email/Password + Google Sign-In.
 *
 * Flow:
 *  Email/Password → signInWithEmailAndPassword → HomeDashboardActivity
 *  Google         → GoogleSignIn intent → firebaseAuthWithGoogle → HomeDashboardActivity
 *
 * Validation: inline errors on TextInputLayouts (no separate dialog).
 * Loading state: button disabled + ProgressBar visible during network calls.
 */
public class LoginActivity extends AppCompatActivity {

    // TODO: replace WEB_CLIENT_ID here
    private static final String WEB_CLIENT_ID =
            "677816196902-u9lrimqgr8vs9j3i2le75pdc1bk8josa.apps.googleusercontent.com";

    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;

    private TextInputLayout tilEmail, tilPassword;
    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnLogin, btnGoogleSignIn;
    private ProgressBar loginProgress;

    // Modern ActivityResultLauncher — replaces deprecated onActivityResult()
    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> handleGoogleSignInResult(result)
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        setupGoogleSignIn();
        bindViews();
        setupClickListeners();
    }

    // ─────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(WEB_CLIENT_ID)
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    private void bindViews() {
        tilEmail         = findViewById(R.id.tilEmail);
        tilPassword      = findViewById(R.id.tilPassword);
        etEmail          = findViewById(R.id.etEmail);
        etPassword       = findViewById(R.id.etPassword);
        btnLogin         = findViewById(R.id.btnLogin);
        btnGoogleSignIn  = findViewById(R.id.btnGoogleSignIn);
        loginProgress    = findViewById(R.id.loginProgress);

        // Register link — navigate to RegisterActivity
        TextView tvGoToRegister = findViewById(R.id.tvGoToRegister);
        tvGoToRegister.setText(Html.fromHtml(getString(R.string.link_to_register), Html.FROM_HTML_MODE_COMPACT));
        tvGoToRegister.setOnClickListener(v -> {
            startActivity(new Intent(this, RegisterActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
    }

    private void setupClickListeners() {
        btnLogin.setOnClickListener(v -> attemptEmailLogin());
        btnGoogleSignIn.setOnClickListener(v -> launchGoogleSignIn());
    }

    // ─────────────────────────────────────────────
    // Email / Password login
    // ─────────────────────────────────────────────

    private void attemptEmailLogin() {
        if (!validateLoginFields()) return;

        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        setLoadingState(true);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    setLoadingState(false);
                    Toast.makeText(this,
                            getString(R.string.toast_login_success),
                            Toast.LENGTH_SHORT).show();
                    navigateToDashboard();
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
                    tilPassword.setError(e.getLocalizedMessage());
                });
    }

    // ─────────────────────────────────────────────
    // Google Sign-In
    // ─────────────────────────────────────────────

    private void launchGoogleSignIn() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        googleSignInLauncher.launch(signInIntent);
    }

    private void handleGoogleSignInResult(ActivityResult result) {
        Task<GoogleSignInAccount> task =
                GoogleSignIn.getSignedInAccountFromIntent(result.getData());
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            firebaseAuthWithGoogle(account.getIdToken());
        } catch (ApiException e) {
            Toast.makeText(this,
                    getString(R.string.err_google_signin_failed) + ": " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void firebaseAuthWithGoogle(String idToken) {
        setLoadingState(true);
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnSuccessListener(authResult -> {
                    setLoadingState(false);
                    Toast.makeText(this,
                            getString(R.string.toast_login_success),
                            Toast.LENGTH_SHORT).show();
                    navigateToDashboard();
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
                    Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                });
    }

    // ─────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────

    private boolean validateLoginFields() {
        boolean valid = true;

        tilEmail.setError(null);
        tilPassword.setError(null);

        String email    = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString() : "";

        if (TextUtils.isEmpty(email)) {
            tilEmail.setError(getString(R.string.err_empty_email));
            valid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError(getString(R.string.err_invalid_email));
            valid = false;
        }

        if (TextUtils.isEmpty(password)) {
            tilPassword.setError(getString(R.string.err_empty_password));
            valid = false;
        }

        return valid;
    }

    // ─────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────

    private void setLoadingState(boolean loading) {
        btnLogin.setEnabled(!loading);
        btnGoogleSignIn.setEnabled(!loading);
        loginProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(this, HomeDashboardActivity.class);
        startActivity(intent);
        finishAffinity(); // Clear entire back stack — user cannot press Back to return to Login
    }
}

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

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;

/**
 * RegisterActivity — Create new account with Email + Password.
 *
 * Flow:
 *  Validate fields → createUserWithEmailAndPassword
 *  → updateProfile (displayName)
 *  → HomeDashboardActivity
 *
 * Validation: inline errors on TextInputLayouts.
 */
public class RegisterActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;

    private TextInputLayout tilName, tilEmail, tilPassword, tilConfirmPassword;
    private TextInputEditText etName, etEmail, etPassword, etConfirmPassword;
    private MaterialButton btnRegister;
    private ProgressBar registerProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        mAuth = FirebaseAuth.getInstance();
        bindViews();
        setupClickListeners();
    }

    // ─────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────

    private void bindViews() {
        tilName            = findViewById(R.id.tilName);
        tilEmail           = findViewById(R.id.tilEmail);
        tilPassword        = findViewById(R.id.tilPassword);
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword);
        etName             = findViewById(R.id.etName);
        etEmail            = findViewById(R.id.etEmail);
        etPassword         = findViewById(R.id.etPassword);
        etConfirmPassword  = findViewById(R.id.etConfirmPassword);
        btnRegister        = findViewById(R.id.btnRegister);
        registerProgress   = findViewById(R.id.registerProgress);

        // Login link
        TextView tvGoToLogin = findViewById(R.id.tvGoToLogin);
        tvGoToLogin.setText(Html.fromHtml(getString(R.string.link_to_login), Html.FROM_HTML_MODE_COMPACT));
        tvGoToLogin.setOnClickListener(v -> {
            finish(); // Go back to LoginActivity
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
    }

    private void setupClickListeners() {
        btnRegister.setOnClickListener(v -> attemptRegister());
    }

    // ─────────────────────────────────────────────
    // Registration logic
    // ─────────────────────────────────────────────

    private void attemptRegister() {
        if (!validateRegisterFields()) return;

        String name     = etName.getText().toString().trim();
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        setLoadingState(true);

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    // Update Firebase profile with display name
                    UserProfileChangeRequest profileUpdate = new UserProfileChangeRequest.Builder()
                            .setDisplayName(name)
                            .build();

                    authResult.getUser().updateProfile(profileUpdate)
                            .addOnCompleteListener(task -> {
                                setLoadingState(false);
                                Toast.makeText(this,
                                        getString(R.string.toast_register_success),
                                        Toast.LENGTH_SHORT).show();
                                navigateToDashboard();
                            });
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
                    tilEmail.setError(e.getLocalizedMessage());
                });
    }

    // ─────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────

    private boolean validateRegisterFields() {
        boolean valid = true;

        // Clear all errors
        tilName.setError(null);
        tilEmail.setError(null);
        tilPassword.setError(null);
        tilConfirmPassword.setError(null);

        String name     = etName.getText() != null ? etName.getText().toString().trim() : "";
        String email    = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString() : "";
        String confirm  = etConfirmPassword.getText() != null ? etConfirmPassword.getText().toString() : "";

        if (TextUtils.isEmpty(name)) {
            tilName.setError(getString(R.string.err_empty_name));
            valid = false;
        }

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
        } else if (password.length() < 6) {
            tilPassword.setError(getString(R.string.err_password_too_short));
            valid = false;
        }

        if (TextUtils.isEmpty(confirm)) {
            tilConfirmPassword.setError(getString(R.string.err_empty_password));
            valid = false;
        } else if (!confirm.equals(password)) {
            tilConfirmPassword.setError(getString(R.string.err_password_mismatch));
            valid = false;
        }

        return valid;
    }

    // ─────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────

    private void setLoadingState(boolean loading) {
        btnRegister.setEnabled(!loading);
        registerProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(this, HomeDashboardActivity.class);
        startActivity(intent);
        finishAffinity(); // Clears Login + Register from back stack
    }
}

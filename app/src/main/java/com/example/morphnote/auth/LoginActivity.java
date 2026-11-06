package com.example.morphnote.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.morphnote.MainActivity;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.databinding.ActivityLoginBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();

        // Check if user is already logged in
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            navigateToMainActivity();
            return;
        }

        binding.btnLogin.setOnClickListener(v -> attemptLogin());

        binding.tvRegisterLink.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
            startActivity(intent);
        });
    }

    private void attemptLogin() {
        String email = "";
        if (binding.tietEmail.getText() != null) {
            email = binding.tietEmail.getText().toString().trim();
        }

        String password = "";
        if (binding.tietPassword.getText() != null) {
            password = binding.tietPassword.getText().toString().trim();
        }

        // Email validation
        if (TextUtils.isEmpty(email)) {
            binding.tilEmail.setError("Email is required");
            binding.tilEmail.requestFocus();
            return;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError("Please enter a valid email address");
            binding.tilEmail.requestFocus();
            return;
        } else {
            binding.tilEmail.setError(null);
        }

        // Password validation
        if (TextUtils.isEmpty(password)) {
            binding.tilPassword.setError("Password is required");
            binding.tilPassword.requestFocus();
            return;
        } else if (password.length() < 6) {
            binding.tilPassword.setError("Password must be at least 6 characters");
            binding.tilPassword.requestFocus();
            return;
        } else {
            binding.tilPassword.setError(null);
        }

        setLoadingState(true);

        final String finalEmail = email;
        final String finalPassword = password;

        // Perform authentication operation via background thread executor
        AppDatabase.getDatabaseExecutor().execute(() -> {
            mAuth.signInWithEmailAndPassword(finalEmail, finalPassword)
                    .addOnCompleteListener(LoginActivity.this, task -> {
                        setLoadingState(false);
                        if (task.isSuccessful()) {
                            Toast.makeText(LoginActivity.this, "Welcome back!", Toast.LENGTH_SHORT).show();
                            navigateToMainActivity();
                        } else {
                            String errorMsg = "Authentication failed";
                            if (task.getException() != null && task.getException().getMessage() != null) {
                                errorMsg = task.getException().getMessage();
                            }
                            Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                        }
                    });
        });
    }

    private void setLoadingState(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnLogin.setEnabled(!isLoading);
        binding.tvRegisterLink.setEnabled(!isLoading);
    }

    private void navigateToMainActivity() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}


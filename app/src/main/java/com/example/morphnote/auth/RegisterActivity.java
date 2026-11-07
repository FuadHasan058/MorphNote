package com.example.morphnote.auth;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.morphnote.MainActivity;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.databinding.ActivityRegisterBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private ActivityRegisterBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore mFirestore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        boolean isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(!isDarkMode);

        mAuth = FirebaseAuth.getInstance();
        mFirestore = FirebaseFirestore.getInstance();

        binding.btnRegister.setOnClickListener(v -> attemptRegister());

        binding.tvLoginLink.setOnClickListener(v -> finish());
    }

    private void attemptRegister() {
        String email = "";
        if (binding.tietEmail.getText() != null) {
            email = binding.tietEmail.getText().toString().trim();
        }

        String password = "";
        if (binding.tietPassword.getText() != null) {
            password = binding.tietPassword.getText().toString().trim();
        }

        String confirmPassword = "";
        if (binding.tietConfirmPassword.getText() != null) {
            confirmPassword = binding.tietConfirmPassword.getText().toString().trim();
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

        // Confirm Password validation
        if (!password.equals(confirmPassword)) {
            binding.tilConfirmPassword.setError("Passwords do not match");
            binding.tilConfirmPassword.requestFocus();
            return;
        } else {
            binding.tilConfirmPassword.setError(null);
        }

        setLoadingState(true);

        final String finalEmail = email;
        final String finalPassword = password;

        // Perform registration via background executor
        AppDatabase.getDatabaseExecutor().execute(() -> {
            mAuth.createUserWithEmailAndPassword(finalEmail, finalPassword)
                    .addOnCompleteListener(RegisterActivity.this, task -> {
                        if (task.isSuccessful()) {
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                saveUserProfileToFirestore(user.getUid(), finalEmail);
                            } else {
                                setLoadingState(false);
                                navigateToMainActivity();
                            }
                        } else {
                            setLoadingState(false);
                            String errorMsg = "Registration failed";
                            if (task.getException() != null && task.getException().getMessage() != null) {
                                errorMsg = task.getException().getMessage();
                            }
                            Toast.makeText(RegisterActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                        }
                    });
        });
    }

    private void saveUserProfileToFirestore(String userId, String email) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            Map<String, Object> userData = new HashMap<>();
            userData.put("userId", userId);
            userData.put("email", email);
            userData.put("createdAt", System.currentTimeMillis());

            mFirestore.collection("users").document(userId)
                    .set(userData)
                    .addOnCompleteListener(RegisterActivity.this, task -> {
                        setLoadingState(false);
                        Toast.makeText(RegisterActivity.this, "Account created successfully!", Toast.LENGTH_SHORT).show();
                        navigateToMainActivity();
                    });
        });
    }

    private void setLoadingState(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.btnRegister.setEnabled(!isLoading);
        binding.tvLoginLink.setEnabled(!isLoading);
    }

    private void navigateToMainActivity() {
        Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}


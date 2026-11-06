package com.example.morphnote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.morphnote.auth.LoginActivity;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.ActivityMainBinding;
import com.example.morphnote.editor.BlockEditorActivity;
import com.example.morphnote.service.FocusModeService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements PageAdapter.OnPageClickListener {

    private ActivityMainBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore mFirestore;
    private PageAdapter pageAdapter;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startFocusModeService();
                } else {
                    Toast.makeText(this, "Notification permission is required for Focus Mode", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        mFirestore = FirebaseFirestore.getInstance();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            navigateToLogin();
            return;
        }

        setupRecyclerView();
        setupListeners();
        fetchDailyMotivationQuote();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPagesFromDatabase();
    }

    private void setupRecyclerView() {
        pageAdapter = new PageAdapter(this);
        binding.rvPages.setLayoutManager(new LinearLayoutManager(this));
        binding.rvPages.setAdapter(pageAdapter);
    }

    private void setupListeners() {
        binding.fabAddPage.setOnClickListener(v -> createNewPage());

        binding.btnRefreshQuote.setOnClickListener(v -> {
            binding.tvDailyQuote.setText(R.string.motivation_loading);
            binding.tvQuoteAuthor.setText("");
            fetchDailyMotivationQuote();
        });

        binding.btnFocusMode.setOnClickListener(v -> toggleFocusMode());

        binding.btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Log Out")
                    .setMessage("Are you sure you want to log out?")
                    .setPositiveButton("Log Out", (dialog, which) -> {
                        mAuth.signOut();
                        navigateToLogin();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void loadPagesFromDatabase() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        final String userId = currentUser.getUid();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<PageEntity> pages = db.pageDao().getAllPages(userId);

            runOnUiThread(() -> {
                pageAdapter.setPages(pages);
                updateEmptyState(pages.isEmpty());
            });

            // Synchronize with Cloud Firestore in background
            syncPagesWithFirestore(userId, db);
        });
    }

    private void syncPagesWithFirestore(String userId, AppDatabase db) {
        mFirestore.collection("users").document(userId)
                .collection("pages")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots != null && !queryDocumentSnapshots.isEmpty()) {
                        AppDatabase.getDatabaseExecutor().execute(() -> {
                            List<PageEntity> remotePages = new ArrayList<>();
                            for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                                String id = doc.getString("id");
                                String title = doc.getString("title");
                                Long updatedAt = doc.getLong("updatedAt");
                                if (id != null) {
                                    remotePages.add(new PageEntity(
                                            id,
                                            userId,
                                            title != null ? title : "Untitled Page",
                                            updatedAt != null ? updatedAt : System.currentTimeMillis()
                                    ));
                                }
                            }
                            if (!remotePages.isEmpty()) {
                                db.pageDao().insertAll(remotePages);
                                List<PageEntity> refreshed = db.pageDao().getAllPages(userId);
                                runOnUiThread(() -> {
                                    pageAdapter.setPages(refreshed);
                                    updateEmptyState(refreshed.isEmpty());
                                });
                            }
                        });
                    }
                });
    }

    private void createNewPage() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        final String userId = currentUser.getUid();
        final String newPageId = UUID.randomUUID().toString();
        final long now = System.currentTimeMillis();
        final PageEntity newPage = new PageEntity(newPageId, userId, "Untitled Canvas", now);

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            db.pageDao().insert(newPage);

            // Backup page metadata to Cloud Firestore
            Map<String, Object> pageData = new HashMap<>();
            pageData.put("id", newPageId);
            pageData.put("userId", userId);
            pageData.put("title", "Untitled Canvas");
            pageData.put("updatedAt", now);

            mFirestore.collection("users").document(userId)
                    .collection("pages").document(newPageId)
                    .set(pageData);

            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, BlockEditorActivity.class);
                intent.putExtra(BlockEditorActivity.EXTRA_PAGE_ID, newPageId);
                intent.putExtra(BlockEditorActivity.EXTRA_PAGE_TITLE, "Untitled Canvas");
                startActivity(intent);
            });
        });
    }

    @Override
    public void onPageClick(PageEntity page) {
        Intent intent = new Intent(MainActivity.this, BlockEditorActivity.class);
        intent.putExtra(BlockEditorActivity.EXTRA_PAGE_ID, page.getId());
        intent.putExtra(BlockEditorActivity.EXTRA_PAGE_TITLE, page.getTitle());
        startActivity(intent);
    }

    @Override
    public void onPageDelete(PageEntity page) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Page")
                .setMessage("Are you sure you want to delete \"" + page.getTitle() + "\" and all of its blocks?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    AppDatabase.getDatabaseExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                        db.pageDao().deleteById(page.getId());
                        db.blockDao().deleteByPageId(page.getId());

                        // Remove from Firestore
                        FirebaseUser currentUser = mAuth.getCurrentUser();
                        if (currentUser != null) {
                            mFirestore.collection("users").document(currentUser.getUid())
                                    .collection("pages").document(page.getId())
                                    .delete();
                        }

                        List<PageEntity> updatedList = db.pageDao().getAllPages(page.getUserId());
                        runOnUiThread(() -> {
                            pageAdapter.setPages(updatedList);
                            updateEmptyState(updatedList.isEmpty());
                            Toast.makeText(MainActivity.this, "Page deleted", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateEmptyState(boolean isEmpty) {
        binding.layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.rvPages.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    // REST API JSON Quote Fetcher using HttpURLConnection
    private void fetchDailyMotivationQuote() {
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            try {
                URL url = new URL("https://zenquotes.io/api/random");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream inputStream = connection.getInputStream();
                    reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }

                    JSONArray jsonArray = new JSONArray(builder.toString());
                    if (jsonArray.length() > 0) {
                        JSONObject quoteObj = jsonArray.getJSONObject(0);
                        final String quote = quoteObj.optString("q", "Believe you can and you're halfway there.");
                        final String author = quoteObj.optString("a", "Theodore Roosevelt");

                        runOnUiThread(() -> {
                            binding.tvDailyQuote.setText("\"" + quote + "\"");
                            binding.tvQuoteAuthor.setText("— " + author);
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        binding.tvDailyQuote.setText("\"The secret of getting ahead is getting started.\"");
                        binding.tvQuoteAuthor.setText("— Mark Twain");
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.tvDailyQuote.setText("\"Simplicity is prerequisite for reliability.\"");
                    binding.tvQuoteAuthor.setText("— Edsger W. Dijkstra");
                });
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {}
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void toggleFocusMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        startFocusModeService();
    }

    private void startFocusModeService() {
        Intent serviceIntent = new Intent(this, FocusModeService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        Toast.makeText(this, "Focus Mode started in notification drawer", Toast.LENGTH_SHORT).show();
    }

    private void navigateToLogin() {
        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}

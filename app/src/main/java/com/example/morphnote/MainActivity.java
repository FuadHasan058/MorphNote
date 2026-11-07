package com.example.morphnote;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.example.morphnote.auth.LoginActivity;
import com.example.morphnote.calendar.CalendarFragment;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.ActivityMainBinding;
import com.example.morphnote.editor.BlockEditorActivity;
import com.example.morphnote.service.FocusModeService;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore mFirestore;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private CanvasesFragment canvasesFragment;
    private CalendarFragment calendarFragment;
    private ListenerRegistration pagesListener;
    private ListenerRegistration eventsListener;

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

        // Dark Mode status bar icon contrast
        boolean isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(!isDarkMode);

        mAuth = FirebaseAuth.getInstance();
        mFirestore = FirebaseFirestore.getInstance();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            navigateToLogin();
            return;
        }

        setupGreeting(currentUser);
        setupViewPager();
        setupFilterChips();
        setupListeners();
        fetchDailyMotivationQuote();
        checkAndRequestNotificationPermission();
        attachDeletionSyncListeners(currentUser.getUid());
    }

    private void attachDeletionSyncListeners(String userId) {
        // 1. Pages deletion reconciliation
        pagesListener = mFirestore.collection("users").document(userId)
                .collection("pages")
                .addSnapshotListener((snapshots, error) -> {
                    if (snapshots == null || error != null) return;

                    List<String> removedPageIds = new ArrayList<>();
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.REMOVED) {
                            removedPageIds.add(dc.getDocument().getId());
                        }
                    }

                    Set<String> activeRemotePageIds = new HashSet<>();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        activeRemotePageIds.add(doc.getId());
                    }

                    AppDatabase.getDatabaseExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                        boolean changed = false;

                        // Immediate deletion of explicitly removed documents
                        for (String removedId : removedPageIds) {
                            db.pageDao().deleteById(removedId);
                            db.blockDao().deleteByPageId(removedId);
                            changed = true;
                        }

                        List<PageEntity> localPages = db.pageDao().getAllPages(userId);
                        long now = System.currentTimeMillis();
                        for (PageEntity local : localPages) {
                            if (!activeRemotePageIds.contains(local.getId()) && (now - local.getUpdatedAt() > 3000L)) {
                                db.pageDao().deleteById(local.getId());
                                db.blockDao().deleteByPageId(local.getId());
                                changed = true;
                            }
                        }

                        if (changed) {
                            runOnUiThread(() -> {
                                if (canvasesFragment != null) canvasesFragment.loadPages();
                                updateProgressRing();
                            });
                        }
                    });
                });

        // 2. Events deletion reconciliation
        eventsListener = mFirestore.collection("users").document(userId)
                .collection("events")
                .addSnapshotListener((snapshots, error) -> {
                    if (snapshots == null || error != null) return;

                    Set<String> activeRemoteEventIds = new HashSet<>();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        activeRemoteEventIds.add(doc.getId());
                    }

                    AppDatabase.getDatabaseExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                        List<com.example.morphnote.database.EventEntity> localEvents = db.eventDao().getAllEventsForUser(userId);

                        boolean changed = false;
                        for (com.example.morphnote.database.EventEntity local : localEvents) {
                            if (!activeRemoteEventIds.contains(local.getId())) {
                                db.eventDao().deleteById(local.getId());
                                changed = true;
                            }
                        }

                        if (changed) {
                            runOnUiThread(() -> {
                                if (calendarFragment != null) {
                                    calendarFragment.onResume();
                                }
                                updateProgressRing();
                            });
                        }
                    });
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateProgressRing();
        if (canvasesFragment != null) {
            canvasesFragment.loadPages();
        }
    }

    private void setupGreeting(FirebaseUser user) {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour >= 5 && hour < 12) {
            greeting = getString(R.string.greeting_morning);
        } else if (hour >= 12 && hour < 17) {
            greeting = getString(R.string.greeting_afternoon);
        } else if (hour >= 17 && hour < 22) {
            greeting = getString(R.string.greeting_evening);
        } else {
            greeting = getString(R.string.greeting_night);
        }
        binding.tvGreeting.setText(greeting);

        String displayName = user.getDisplayName();
        if (TextUtils.isEmpty(displayName) && user.getEmail() != null) {
            displayName = user.getEmail().split("@")[0];
        }
        if (TextUtils.isEmpty(displayName)) {
            displayName = "Scholar";
        }
        binding.tvUsername.setText(displayName);
    }

    public void updateProgressRing() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;
        final String userId = currentUser.getUid();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            List<PageEntity> pages = db.pageDao().getAllPages(userId);
            Set<String> userPageIds = new HashSet<>();
            for (PageEntity page : pages) {
                userPageIds.add(page.getId());
            }

            int totalTodos = 0;
            int completedTodos = 0;
            int inProgressTodos = 0;

            List<BlockEntity> allBlocks = db.blockDao().getAllBlocks();
            for (BlockEntity b : allBlocks) {
                if (BlockEntity.TYPE_TODO.equalsIgnoreCase(b.getBlockType())) {
                    if (userPageIds.isEmpty() || userPageIds.contains(b.getPageId())) {
                        totalTodos++;
                        if (b.isCompleted() || BlockEntity.STATUS_DONE.equalsIgnoreCase(b.getStatus())) {
                            completedTodos++;
                        } else if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(b.getStatus())) {
                            inProgressTodos++;
                        }
                    }
                }
            }

            // Also include standalone calendar events for user
            List<com.example.morphnote.database.EventEntity> events = db.eventDao().getAllEventsForUser(userId);
            for (com.example.morphnote.database.EventEntity e : events) {
                totalTodos++;
                if (e.isCompleted()) {
                    completedTodos++;
                }
            }

            final int total = totalTodos;
            final int completed = completedTodos;
            final int inProgress = inProgressTodos;
            final int percent = (total > 0) ? (completed * 100 / total) : 0;

            runOnUiThread(() -> {
                binding.progressRing.setProgressCompat(percent, true);
                binding.tvProgressPercent.setText(percent + "%");
                if (total == 0) {
                    binding.tvProgressLabel.setText("No tasks yet");
                } else if (inProgress > 0) {
                    binding.tvProgressLabel.setText(completed + " / " + total + " done (" + inProgress + " in progress)");
                } else {
                    binding.tvProgressLabel.setText(getString(R.string.progress_format, completed, total));
                }
            });
        });
    }

    private void setupViewPager() {
        canvasesFragment = new CanvasesFragment();
        calendarFragment = new CalendarFragment();

        binding.viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return (position == 0) ? canvasesFragment : calendarFragment;
            }

            @Override
            public int getItemCount() {
                return 2;
            }
        });

        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText(R.string.tab_canvases);
                tab.setIcon(R.drawable.ic_notes);
            } else {
                tab.setText(R.string.tab_calendar);
                tab.setIcon(R.drawable.ic_calendar);
            }
        }).attach();
    }

    private void setupFilterChips() {
        binding.chipInbox.setChecked(true);

        binding.chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                binding.chipInbox.setChecked(true);
                if (canvasesFragment != null) canvasesFragment.setFilter("INBOX");
                return;
            }
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chip_today) {
                if (canvasesFragment != null) canvasesFragment.setFilter("TODAY");
            } else if (checkedId == R.id.chip_upcoming) {
                if (canvasesFragment != null) canvasesFragment.setFilter("UPCOMING");
            } else if (checkedId == R.id.chip_priority) {
                if (canvasesFragment != null) canvasesFragment.setFilter("PRIORITY");
            } else {
                if (canvasesFragment != null) canvasesFragment.setFilter("INBOX");
            }
        });
    }

    private void setupListeners() {
        binding.btnLogout.setOnClickListener(v -> {
            mAuth.signOut();
            navigateToLogin();
        });

        binding.btnSync.setOnClickListener(v -> {
            Toast.makeText(this, "Syncing with cloud…", Toast.LENGTH_SHORT).show();
            if (canvasesFragment != null) {
                canvasesFragment.syncPagesFromCloud();
            }
            updateProgressRing();
        });

        binding.btnRefreshQuote.setOnClickListener(v -> {
            binding.tvDailyQuote.setText(R.string.daily_quote);
            fetchDailyMotivationQuote();
        });

        binding.fabAddPage.setOnClickListener(v -> showCreatePageDialog());
    }

    private void showCreatePageDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_page, null);
        EditText etTitle = dialogView.findViewById(R.id.et_page_title_input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_page)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String title = etTitle.getText().toString().trim();
                    if (title.isEmpty()) {
                        title = getString(R.string.untitled_page);
                    }
                    createNewPage(title);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void createNewPage(String title) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        String pageId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        PageEntity newPage = new PageEntity(pageId, currentUser.getUid(), title, now);

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            db.pageDao().insert(newPage);

            Map<String, Object> pageMap = new HashMap<>();
            pageMap.put("id", pageId);
            pageMap.put("userId", currentUser.getUid());
            pageMap.put("title", title);
            pageMap.put("updatedAt", now);
            mFirestore.collection("pages").document(pageId).set(pageMap);
            mFirestore.collection("users").document(currentUser.getUid())
                    .collection("pages").document(pageId).set(pageMap);

            runOnUiThread(() -> {
                if (canvasesFragment != null) {
                    canvasesFragment.loadPages();
                }
                Intent intent = new Intent(this, BlockEditorActivity.class);
                intent.putExtra(BlockEditorActivity.EXTRA_PAGE_ID, pageId);
                intent.putExtra(BlockEditorActivity.EXTRA_PAGE_TITLE, title);
                startActivity(intent);
            });
        });
    }

    private void fetchDailyMotivationQuote() {
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://zenquotes.io/api/random");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream inputStream = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONArray jsonArray = new JSONArray(response.toString());
                    if (jsonArray.length() > 0) {
                        JSONObject quoteObj = jsonArray.getJSONObject(0);
                        final String quote = quoteObj.optString("q", "Stay focused and present.");
                        final String author = quoteObj.optString("a", "Unknown");

                        runOnUiThread(() -> {
                            if (binding != null) {
                                binding.tvDailyQuote.setText("\"" + quote + "\" — " + author);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (binding != null) {
                        binding.tvDailyQuote.setText("\"Small daily improvements over time lead to stunning results.\" — Robin Sharma");
                    }
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                startFocusModeService();
            } else {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            startFocusModeService();
        }
    }

    private void startFocusModeService() {
        Intent serviceIntent = new Intent(this, FocusModeService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pagesListener != null) {
            pagesListener.remove();
        }
        if (eventsListener != null) {
            eventsListener.remove();
        }
        networkExecutor.shutdown();
        binding = null;
    }
}

package com.example.morphnote.editor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.InvalidationTracker;
import androidx.viewpager2.widget.ViewPager2;

import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.ActivityBlockEditorBinding;
import com.example.morphnote.reminder.ReminderScheduler;
import com.example.morphnote.service.FocusModeService;
import com.example.morphnote.util.FileUploadService;
import com.example.morphnote.util.ImageUploadService;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

public class BlockEditorActivity extends AppCompatActivity implements BlockAdapter.OnBlockActionListener {

    public static final String EXTRA_PAGE_ID = "com.example.morphnote.EXTRA_PAGE_ID";
    public static final String EXTRA_PAGE_TITLE = "com.example.morphnote.EXTRA_PAGE_TITLE";

    public static final int MODE_DOCUMENT = 0;
    public static final int MODE_KANBAN   = 1;
    public static final int MODE_REVIEW   = 2;

    private int currentViewMode = MODE_DOCUMENT;

    private ActivityBlockEditorBinding binding;
    private BlockAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    // Kanban adapters
    private KanbanAdapter kanbanTodoAdapter;
    private KanbanAdapter kanbanInProgressAdapter;
    private KanbanAdapter kanbanDoneAdapter;

    // Flashcard adapter
    private FlashcardAdapter flashcardAdapter;
    private List<BlockEntity> reviewBlocks = new ArrayList<>();

    private String pageId;
    private String pageTitle;
    private PageEntity currentPage;

    private DatabaseReference realtimeDbRef;
    private FirebaseFirestore firestore;
    private FirebaseAuth auth;

    // Activity result launcher for picking image with zero-cost cloud upload
    private final ActivityResultLauncher<String> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Toast.makeText(this, "Uploading image to cloud…", Toast.LENGTH_SHORT).show();
                    ImageUploadService.uploadImage(this, uri, new ImageUploadService.UploadCallback() {
                        @Override
                        public void onSuccess(String remoteImageUrl) {
                            addNewBlock(BlockEntity.TYPE_IMAGE, remoteImageUrl);
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(BlockEditorActivity.this, "Cloud upload error. Storing locally…", Toast.LENGTH_SHORT).show();
                            saveImageToInternalStorage(uri);
                        }
                    });
                }
            });

    // Activity result launcher for picking any file with zero-cost cloud upload
    private final ActivityResultLauncher<String> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Toast.makeText(this, "Uploading file to cloud…", Toast.LENGTH_SHORT).show();
                    FileUploadService.uploadFile(this, uri, new FileUploadService.UploadCallback() {
                        @Override
                        public void onSuccess(String fileName, String remoteUrl, String fileSizeStr) {
                            addNewBlock(BlockEntity.TYPE_FILE, remoteUrl + "|||" + fileName + "|||" + fileSizeStr);
                            Toast.makeText(BlockEditorActivity.this, "File attached successfully", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(BlockEditorActivity.this, "Cloud upload failed. Storing locally…", Toast.LENGTH_SHORT).show();
                            saveFileToInternalStorage(uri);
                        }
                    });
                }
            });

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startFocusModeService();
                } else {
                    Toast.makeText(this, "Notification permission is required for Focus Mode", Toast.LENGTH_SHORT).show();
                }
            });

    private ChildEventListener blocksChildEventListener;
    private ListenerRegistration firestoreBlocksListener;
    private InvalidationTracker.Observer blocksTableObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBlockEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Dark Mode status bar icon contrast
        boolean isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(!isDarkMode);

        pageId = getIntent().getStringExtra(EXTRA_PAGE_ID);
        pageTitle = getIntent().getStringExtra(EXTRA_PAGE_TITLE);

        if (pageId == null || pageId.isEmpty()) {
            Toast.makeText(this, "Invalid canvas", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        auth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        realtimeDbRef = FirebaseDatabase.getInstance().getReference("pages").child(pageId).child("blocks");

        setupToolbar();
        setupDocumentView();
        setupKanbanView();
        setupReviewView();
        setupInsertToolbar();
        setupBlocksTableObserver();
        loadPageAndBlocks();
        syncBlocksFromCloud();
        attachRealtimeBlocksListener();
    }

    private void setupBlocksTableObserver() {
        blocksTableObserver = new InvalidationTracker.Observer("blocks") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                AppDatabase.getDatabaseExecutor().execute(() -> {
                    List<BlockEntity> updated = AppDatabase.getInstance(getApplicationContext())
                            .blockDao().getBlocksForPage(pageId);
                    runOnUiThread(() -> {
                        for (BlockEntity b : updated) {
                            adapter.updateBlockWithoutFocusLoss(b);
                        }
                    });
                });
            }
        };
        AppDatabase.getInstance(getApplicationContext()).getInvalidationTracker().addObserver(blocksTableObserver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPageAndBlocks();
    }

    private void attachRealtimeBlocksListener() {
        if (realtimeDbRef != null) {
            blocksChildEventListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                    BlockEntity b = parseBlockFromDataSnapshot(snapshot);
                    if (b == null || adapter == null) return;
                    List<BlockEntity> list = adapter.getBlocks();
                    boolean exists = false;
                    for (BlockEntity existing : list) {
                        if (existing.getId().equals(b.getId())) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        AppDatabase.getDatabaseExecutor().execute(() -> {
                            AppDatabase.getInstance(getApplicationContext()).blockDao().insert(b);
                            List<BlockEntity> updated = AppDatabase.getInstance(getApplicationContext()).blockDao().getBlocksForPage(pageId);
                            runOnUiThread(() -> {
                                adapter.setBlocks(updated);
                                updateEmptyState(updated.isEmpty());
                            });
                        });
                    }
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                    BlockEntity b = parseBlockFromDataSnapshot(snapshot);
                    if (b == null || adapter == null) return;
                    AppDatabase.getDatabaseExecutor().execute(() -> {
                        AppDatabase.getInstance(getApplicationContext()).blockDao().insert(b);
                        runOnUiThread(() -> {
                            adapter.updateBlockWithoutFocusLoss(b);
                            if (currentViewMode == MODE_KANBAN) {
                                populateKanbanLanes();
                            }
                        });
                    });
                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                    String removedId = snapshot.getKey();
                    if (removedId == null) {
                        Object idObj = snapshot.child("id").getValue();
                        if (idObj != null) removedId = idObj.toString();
                    }
                    if (removedId != null) {
                        handleBlockDeletedRemotely(removedId);
                    }
                }

                @Override public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {}
                @Override public void onCancelled(@NonNull DatabaseError error) {}
            };
            realtimeDbRef.addChildEventListener(blocksChildEventListener);
        }

        // Real-time deletion & mutation propagation from Firestore
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            firestoreBlocksListener = firestore.collection("users").document(user.getUid())
                    .collection("pages").document(pageId).collection("blocks")
                    .addSnapshotListener((snapshots, error) -> {
                        if (snapshots == null || error != null) return;
                        for (DocumentChange dc : snapshots.getDocumentChanges()) {
                            if (dc.getType() == DocumentChange.Type.REMOVED) {
                                handleBlockDeletedRemotely(dc.getDocument().getId());
                            } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                                BlockEntity b = parseBlockFromFirestore(dc.getDocument());
                                if (b != null) {
                                    AppDatabase.getDatabaseExecutor().execute(() -> {
                                        AppDatabase.getInstance(getApplicationContext()).blockDao().insert(b);
                                        runOnUiThread(() -> {
                                            adapter.updateBlockWithoutFocusLoss(b);
                                            if (currentViewMode == MODE_KANBAN) {
                                                populateKanbanLanes();
                                            }
                                        });
                                    });
                                }
                            }
                        }
                    });
        }
    }

    private void handleBlockDeletedRemotely(String targetId) {
        if (targetId == null || adapter == null) return;
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().deleteById(targetId);
            runOnUiThread(() -> {
                List<BlockEntity> list = adapter.getBlocks();
                int removeIdx = -1;
                for (int i = 0; i < list.size(); i++) {
                    if (targetId.equals(list.get(i).getId())) {
                        removeIdx = i;
                        break;
                    }
                }
                if (removeIdx != -1) {
                    list.remove(removeIdx);
                    adapter.notifyItemRemoved(removeIdx);
                    updateEmptyState(list.isEmpty());
                }
                if (currentViewMode == MODE_KANBAN) {
                    if (kanbanTodoAdapter != null) kanbanTodoAdapter.removeBlock(targetId);
                    if (kanbanInProgressAdapter != null) kanbanInProgressAdapter.removeBlock(targetId);
                    if (kanbanDoneAdapter != null) kanbanDoneAdapter.removeBlock(targetId);
                    updateKanbanBadgeCounts();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (realtimeDbRef != null && blocksChildEventListener != null) {
            realtimeDbRef.removeEventListener(blocksChildEventListener);
        }
        if (firestoreBlocksListener != null) {
            firestoreBlocksListener.remove();
        }
        if (blocksTableObserver != null) {
            AppDatabase.getInstance(getApplicationContext()).getInvalidationTracker().removeObserver(blocksTableObserver);
        }
    }

    private void setupToolbar() {
        binding.ivBack.setOnClickListener(v -> finish());

        if (pageTitle != null) {
            String current = binding.etPageTitle.getText() != null ? binding.etPageTitle.getText().toString() : "";
            if (!current.equals(pageTitle)) {
                binding.etPageTitle.setText(pageTitle);
            }
        }

        binding.etPageTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (binding.etPageTitle.hasFocus()) {
                    updatePageTitle(s.toString());
                }
            }
        });

        binding.btnViewMode.setOnClickListener(v -> toggleViewMode());
        binding.btnShareMarkdown.setOnClickListener(v -> sharePageAsMarkdown());

        binding.btnEditorFocus.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
            }
            startFocusModeService();
        });
    }

    private void toggleViewMode() {
        if (currentViewMode == MODE_DOCUMENT) {
            switchToViewMode(MODE_KANBAN);
        } else if (currentViewMode == MODE_KANBAN) {
            switchToViewMode(MODE_REVIEW);
        } else {
            switchToViewMode(MODE_DOCUMENT);
        }
    }

    private void switchToViewMode(int mode) {
        currentViewMode = mode;
        if (mode == MODE_DOCUMENT) {
            binding.layoutDocumentView.setVisibility(View.VISIBLE);
            binding.layoutKanbanView.setVisibility(View.GONE);
            binding.layoutReviewView.setVisibility(View.GONE);
            binding.btnViewMode.setText(R.string.view_document);
            binding.btnViewMode.setIconResource(R.drawable.ic_notes);
            loadPageAndBlocks();
        } else if (mode == MODE_KANBAN) {
            binding.layoutDocumentView.setVisibility(View.GONE);
            binding.layoutKanbanView.setVisibility(View.VISIBLE);
            binding.layoutReviewView.setVisibility(View.GONE);
            binding.btnViewMode.setText(R.string.view_kanban);
            binding.btnViewMode.setIconResource(R.drawable.ic_kanban);
            populateKanbanLanes();
        } else {
            binding.layoutDocumentView.setVisibility(View.GONE);
            binding.layoutKanbanView.setVisibility(View.GONE);
            binding.layoutReviewView.setVisibility(View.VISIBLE);
            binding.btnViewMode.setText(R.string.view_flashcard);
            binding.btnViewMode.setIconResource(R.drawable.ic_review);
            populateReviewCards();
        }
    }

    private void setupDocumentView() {
        adapter = new BlockAdapter(this);
        binding.rvBlocks.setLayoutManager(new LinearLayoutManager(this));
        binding.rvBlocks.setAdapter(adapter);

        ItemTouchHelper.SimpleCallback touchCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getBindingAdapterPosition();
                int toPos = target.getBindingAdapterPosition();
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) {
                    return false;
                }

                List<BlockEntity> list = adapter.getBlocks();
                Collections.swap(list, fromPos, toPos);

                for (int i = 0; i < list.size(); i++) {
                    list.get(i).setOrderIndex(i);
                }

                adapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                persistReorderedBlocks(adapter.getBlocks());
            }
        };

        itemTouchHelper = new ItemTouchHelper(touchCallback);
        itemTouchHelper.attachToRecyclerView(binding.rvBlocks);
    }

    private void setupKanbanView() {
        kanbanTodoAdapter = new KanbanAdapter("TODO", block -> advanceKanbanCard(block, "TODO"));
        kanbanInProgressAdapter = new KanbanAdapter("IN_PROGRESS", block -> advanceKanbanCard(block, "IN_PROGRESS"));
        kanbanDoneAdapter = new KanbanAdapter("DONE", block -> advanceKanbanCard(block, "DONE"));

        binding.rvKanbanTodo.setLayoutManager(new LinearLayoutManager(this));
        binding.rvKanbanTodo.setAdapter(kanbanTodoAdapter);

        binding.rvKanbanInprogress.setLayoutManager(new LinearLayoutManager(this));
        binding.rvKanbanInprogress.setAdapter(kanbanInProgressAdapter);

        binding.rvKanbanDone.setLayoutManager(new LinearLayoutManager(this));
        binding.rvKanbanDone.setAdapter(kanbanDoneAdapter);
    }

    private void advanceKanbanCard(BlockEntity block, String fromLane) {
        if (block == null) return;

        if ("TODO".equals(fromLane)) {
            // Move: TODO -> IN_PROGRESS
            block.setStatus(BlockEntity.STATUS_IN_PROGRESS);
            block.setCompleted(false);

            kanbanTodoAdapter.removeBlock(block.getId());
            kanbanInProgressAdapter.addBlock(0, block);
            binding.rvKanbanInprogress.scrollToPosition(0);

        } else if ("IN_PROGRESS".equals(fromLane)) {
            // Move: IN_PROGRESS -> DONE
            block.setStatus(BlockEntity.STATUS_DONE);
            block.setCompleted(true);

            kanbanInProgressAdapter.removeBlock(block.getId());
            kanbanDoneAdapter.addBlock(0, block);
            binding.rvKanbanDone.scrollToPosition(0);

        } else if ("DONE".equals(fromLane)) {
            // Reopen: DONE -> TODO
            block.setStatus(BlockEntity.STATUS_TODO);
            block.setCompleted(false);

            kanbanDoneAdapter.removeBlock(block.getId());
            kanbanTodoAdapter.addBlock(0, block);
            binding.rvKanbanTodo.scrollToPosition(0);
        }

        updateKanbanBadgeCounts();

        // Immediately update Document mode adapter in-memory if initialized
        if (adapter != null) {
            adapter.updateBlockWithoutFocusLoss(block);
        }

        // Asynchronously persist to Room, Firebase, and update notifications
        onBlockStatusChanged(block);
    }

    private void updateKanbanBadgeCounts() {
        if (binding == null) return;
        binding.tvCountTodo.setText(String.valueOf(kanbanTodoAdapter != null ? kanbanTodoAdapter.getItemCount() : 0));
        binding.tvCountInprogress.setText(String.valueOf(kanbanInProgressAdapter != null ? kanbanInProgressAdapter.getItemCount() : 0));
        binding.tvCountDone.setText(String.valueOf(kanbanDoneAdapter != null ? kanbanDoneAdapter.getItemCount() : 0));
    }

    private void populateKanbanLanes() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<BlockEntity> allBlocks = db.blockDao().getBlocksForPage(pageId);

            List<BlockEntity> todoList = new ArrayList<>();
            List<BlockEntity> inProgressList = new ArrayList<>();
            List<BlockEntity> doneList = new ArrayList<>();

            for (BlockEntity b : allBlocks) {
                if (BlockEntity.TYPE_TODO.equalsIgnoreCase(b.getBlockType())) {
                    String status = b.getStatus();
                    if (BlockEntity.STATUS_DONE.equalsIgnoreCase(status) || b.isCompleted()) {
                        doneList.add(b);
                    } else if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(status)) {
                        inProgressList.add(b);
                    } else {
                        todoList.add(b);
                    }
                }
            }

            runOnUiThread(() -> {
                if (kanbanTodoAdapter != null) kanbanTodoAdapter.setBlocks(todoList);
                if (kanbanInProgressAdapter != null) kanbanInProgressAdapter.setBlocks(inProgressList);
                if (kanbanDoneAdapter != null) kanbanDoneAdapter.setBlocks(doneList);
                updateKanbanBadgeCounts();
            });
        });
    }

    private void setupReviewView() {
        flashcardAdapter = new FlashcardAdapter();
        binding.vpFlashcards.setAdapter(flashcardAdapter);

        binding.vpFlashcards.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateFlashcardControls(position, reviewBlocks.size());
            }
        });

        binding.btnPrevCard.setOnClickListener(v -> {
            int current = binding.vpFlashcards.getCurrentItem();
            if (current > 0) {
                binding.vpFlashcards.setCurrentItem(current - 1, true);
            }
        });

        binding.btnNextCard.setOnClickListener(v -> {
            int current = binding.vpFlashcards.getCurrentItem();
            if (current < reviewBlocks.size() - 1) {
                binding.vpFlashcards.setCurrentItem(current + 1, true);
            }
        });
    }

    private void populateReviewCards() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<BlockEntity> allBlocks = db.blockDao().getBlocksForPage(pageId);

            List<BlockEntity> cards = new ArrayList<>();
            for (BlockEntity b : allBlocks) {
                if (BlockEntity.TYPE_CALLOUT.equalsIgnoreCase(b.getBlockType())
                        || BlockEntity.TYPE_CODE.equalsIgnoreCase(b.getBlockType())) {
                    cards.add(b);
                }
            }

            runOnUiThread(() -> {
                reviewBlocks = cards;
                flashcardAdapter.setBlocks(cards);

                if (cards.isEmpty()) {
                    binding.tvNoReviewBlocks.setVisibility(View.VISIBLE);
                    binding.vpFlashcards.setVisibility(View.GONE);
                    binding.flashcardControls.setVisibility(View.GONE);
                } else {
                    binding.tvNoReviewBlocks.setVisibility(View.GONE);
                    binding.vpFlashcards.setVisibility(View.VISIBLE);
                    binding.flashcardControls.setVisibility(View.VISIBLE);
                    updateFlashcardControls(0, cards.size());
                }
            });
        });
    }

    private void updateFlashcardControls(int current, int total) {
        if (total == 0) return;
        binding.tvFlashcardCounter.setText(getString(R.string.flashcard_count, current + 1, total));
        binding.btnPrevCard.setEnabled(current > 0);
        binding.btnNextCard.setEnabled(current < total - 1);
    }

    private void setupInsertToolbar() {
        binding.btnInsertHeading.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_HEADING, ""));
        binding.btnInsertTodo.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_TODO, ""));
        binding.btnInsertBullet.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_BULLET, ""));
        binding.btnInsertCallout.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_CALLOUT, ""));
        binding.btnInsertCode.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_CODE, ""));

        // New feature: Insert Link
        binding.btnInsertLink.setOnClickListener(v -> showInsertLinkDialog());

        // New feature: Insert Picture
        binding.btnInsertImage.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));

        // New feature: Insert File
        binding.btnInsertFile.setOnClickListener(v -> filePickerLauncher.launch("*/*"));
    }

    private void showInsertLinkDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_page, null);
        EditText etInput = dialogView.findViewById(R.id.et_page_title_input);
        etInput.setHint("https://example.com");

        new AlertDialog.Builder(this)
                .setTitle("Add Link")
                .setView(dialogView)
                .setPositiveButton("Add", (dialog, which) -> {
                    String url = etInput.getText().toString().trim();
                    if (!url.isEmpty()) {
                        addNewBlock(BlockEntity.TYPE_LINK, "Link|||" + url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveImageToInternalStorage(Uri uri) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                File imagesDir = new File(getFilesDir(), "canvas_images");
                if (!imagesDir.exists()) imagesDir.mkdirs();

                String fileName = "img_" + System.currentTimeMillis() + ".jpg";
                File destFile = new File(imagesDir, fileName);

                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return;
                OutputStream out = new FileOutputStream(destFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();

                runOnUiThread(() -> addNewBlock(BlockEntity.TYPE_IMAGE, destFile.getAbsolutePath()));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to attach image", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveFileToInternalStorage(Uri uri) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                File filesDir = new File(getFilesDir(), "canvas_files");
                if (!filesDir.exists()) filesDir.mkdirs();

                String displayName = getFileNameFromUri(uri);
                String fileName = "file_" + System.currentTimeMillis() + "_" + displayName;
                File destFile = new File(filesDir, fileName);

                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return;
                OutputStream out = new FileOutputStream(destFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();

                long sizeBytes = destFile.length();
                String sizeStr = formatFileSize(sizeBytes);

                runOnUiThread(() -> addNewBlock(BlockEntity.TYPE_FILE,
                        destFile.getAbsolutePath() + "|||" + displayName + "|||" + sizeStr));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to attach file", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String getFileNameFromUri(Uri uri) {
        String result = "document";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private void loadPageAndBlocks() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            currentPage = db.pageDao().getPageById(pageId);
            List<BlockEntity> blocks = db.blockDao().getBlocksForPage(pageId);

            runOnUiThread(() -> {
                if (currentPage != null && (pageTitle == null || pageTitle.isEmpty())) {
                    String current = binding.etPageTitle.getText() != null ? binding.etPageTitle.getText().toString() : "";
                    if (!current.equals(currentPage.getTitle())) {
                        int cursor = binding.etPageTitle.getSelectionStart();
                        binding.etPageTitle.setText(currentPage.getTitle());
                        if (binding.etPageTitle.hasFocus()) {
                            binding.etPageTitle.setSelection(Math.min(cursor, binding.etPageTitle.getText().length()));
                        }
                    }
                }
                adapter.setBlocks(blocks);
                updateEmptyState(blocks.isEmpty());
            });
        });
    }

    public static BlockEntity parseBlockFromDataSnapshot(DataSnapshot child) {
        try {
            String id = child.child("id").getValue(String.class);
            if (id == null) id = child.getKey();
            if (id == null) return null;

            String pageId = child.child("pageId").getValue(String.class);
            String blockType = child.child("blockType").getValue(String.class);
            if (blockType == null) blockType = BlockEntity.TYPE_HEADING;

            String content = child.child("content").getValue(String.class);
            if (content == null) content = "";

            Boolean isCompleted = child.child("isCompleted").getValue(Boolean.class);
            if (isCompleted == null) {
                isCompleted = child.child("completed").getValue(Boolean.class);
            }
            boolean completed = (isCompleted != null && isCompleted);

            int orderIndex = 0;
            Object orderObj = child.child("orderIndex").getValue();
            if (orderObj instanceof Number) {
                orderIndex = ((Number) orderObj).intValue();
            }

            BlockEntity block = new BlockEntity(id, pageId, blockType, content, completed, orderIndex);

            Object dueObj = child.child("dueDate").getValue();
            if (dueObj instanceof Number) {
                block.setDueDate(((Number) dueObj).longValue());
            }

            Object reminderObj = child.child("reminderTime").getValue();
            if (reminderObj instanceof Number) {
                block.setReminderTime(((Number) reminderObj).longValue());
            }

            Boolean hasReminder = child.child("hasReminder").getValue(Boolean.class);
            if (hasReminder != null) {
                block.setHasReminder(hasReminder);
            }

            Object priorityObj = child.child("priority").getValue();
            if (priorityObj instanceof Number) {
                block.setPriority(((Number) priorityObj).intValue());
            }

            String status = child.child("status").getValue(String.class);
            if (status != null) {
                block.setStatus(status);
            }

            return block;
        } catch (Exception e) {
            return null;
        }
    }

    public static BlockEntity parseBlockFromFirestore(DocumentSnapshot doc) {
        try {
            String id = doc.getString("id");
            if (id == null || id.isEmpty()) id = doc.getId();
            String pageId = doc.getString("pageId");
            String blockType = doc.getString("blockType");
            if (blockType == null) blockType = BlockEntity.TYPE_HEADING;

            String content = doc.getString("content");
            if (content == null) content = "";

            Boolean isCompleted = doc.getBoolean("isCompleted");
            if (isCompleted == null) isCompleted = doc.getBoolean("completed");
            boolean completed = (isCompleted != null && isCompleted);

            Long orderLong = doc.getLong("orderIndex");
            int orderIndex = (orderLong != null) ? orderLong.intValue() : 0;

            BlockEntity block = new BlockEntity(id, pageId, blockType, content, completed, orderIndex);

            Long dueDate = doc.getLong("dueDate");
            if (dueDate != null) block.setDueDate(dueDate);

            Long reminderTime = doc.getLong("reminderTime");
            if (reminderTime != null) block.setReminderTime(reminderTime);

            Boolean hasReminder = doc.getBoolean("hasReminder");
            if (hasReminder != null) block.setHasReminder(hasReminder);

            Long priorityL = doc.getLong("priority");
            if (priorityL != null) block.setPriority(priorityL.intValue());

            String status = doc.getString("status");
            if (status != null) block.setStatus(status);

            return block;
        } catch (Exception e) {
            return null;
        }
    }

    private void syncBlocksFromCloud() {
        FirebaseUser user = auth.getCurrentUser();

        // 1. Fetch from Firestore subcollection: pages/{pageId}/blocks
        firestore.collection("pages").document(pageId).collection("blocks").get()
                .addOnSuccessListener(querySnapshot -> {
                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        List<BlockEntity> firestoreBlocks = new ArrayList<>();
                        for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                            BlockEntity b = parseBlockFromFirestore(doc);
                            if (b != null) firestoreBlocks.add(b);
                        }
                        if (!firestoreBlocks.isEmpty()) {
                            saveBlocksAndRefreshUi(firestoreBlocks);
                        }
                    }
                });

        // 2. Also check user-scoped Firestore path: users/{uid}/pages/{pageId}/blocks
        if (user != null) {
            firestore.collection("users").document(user.getUid())
                    .collection("pages").document(pageId).collection("blocks").get()
                    .addOnSuccessListener(querySnapshot -> {
                        if (querySnapshot != null && !querySnapshot.isEmpty()) {
                            List<BlockEntity> userBlocks = new ArrayList<>();
                            for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                                BlockEntity b = parseBlockFromFirestore(doc);
                                if (b != null) userBlocks.add(b);
                            }
                            if (!userBlocks.isEmpty()) {
                                saveBlocksAndRefreshUi(userBlocks);
                            }
                        }
                    });
        }

        // 3. Also fetch from Firebase Realtime Database: pages/{pageId}/blocks
        realtimeDbRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                List<BlockEntity> cloudBlocks = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    BlockEntity b = parseBlockFromDataSnapshot(child);
                    if (b != null) {
                        cloudBlocks.add(b);
                    }
                }
                if (!cloudBlocks.isEmpty()) {
                    saveBlocksAndRefreshUi(cloudBlocks);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void saveBlocksAndRefreshUi(List<BlockEntity> blocks) {
        if (blocks == null) return;
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            // Reconcile against local Room: delete blocks no longer in cloud
            List<BlockEntity> localBlocks = db.blockDao().getBlocksForPage(pageId);
            Set<String> cloudIds = new HashSet<>();
            for (BlockEntity cb : blocks) {
                cloudIds.add(cb.getId());
            }
            for (BlockEntity lb : localBlocks) {
                if (!cloudIds.contains(lb.getId())) {
                    db.blockDao().deleteById(lb.getId());
                }
            }

            db.blockDao().insertAll(blocks);
            List<BlockEntity> updated = db.blockDao().getBlocksForPage(pageId);
            runOnUiThread(() -> {
                if (binding.rvBlocks.hasFocus()) {
                    for (BlockEntity b : updated) {
                        adapter.updateBlockWithoutFocusLoss(b);
                    }
                } else {
                    adapter.setBlocks(updated);
                }
                updateEmptyState(updated.isEmpty());
            });
        });
    }

    private void addNewBlock(String blockType, String initialContent) {
        List<BlockEntity> currentList = adapter.getBlocks();
        int newIndex = currentList.size();

        BlockEntity newBlock = new BlockEntity(
                UUID.randomUUID().toString(),
                pageId,
                blockType,
                initialContent,
                false,
                newIndex
        );

        currentList.add(newBlock);
        adapter.notifyItemInserted(newIndex);
        binding.rvBlocks.smoothScrollToPosition(newIndex);
        updateEmptyState(false);

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().insert(newBlock);
            syncBlockToFirebase(newBlock);
            dispatchNotificationUpdate();
        });
    }

    private void updatePageTitle(String newTitle) {
        long now = System.currentTimeMillis();
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            if (currentPage == null) {
                FirebaseUser user = auth.getCurrentUser();
                String userId = (user != null) ? user.getUid() : "";
                currentPage = new PageEntity(pageId, userId, newTitle, now);
                db.pageDao().insert(currentPage);
            } else {
                currentPage.setTitle(newTitle);
                currentPage.setUpdatedAt(now);
                db.pageDao().update(currentPage);
            }

            // Sync with Firestore (both paths for cross-device support)
            FirebaseUser user = auth.getCurrentUser();
            if (user != null) {
                Map<String, Object> map = new HashMap<>();
                map.put("title", newTitle);
                map.put("updatedAt", now);
                firestore.collection("pages").document(pageId).update(map);
                firestore.collection("users").document(user.getUid())
                        .collection("pages").document(pageId)
                        .set(map);
            }
        });
    }

    @Override
    public void onBlockContentChanged(BlockEntity block) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().update(block);
            syncBlockToFirebase(block);
            dispatchNotificationUpdate();
        });
    }

    @Override
    public void onBlockStatusChanged(BlockEntity block) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().update(block);
            syncBlockToFirebase(block);
            dispatchNotificationUpdate();
        });
    }

    @Override
    public void onPriorityToggle(BlockEntity block) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().update(block);
            syncBlockToFirebase(block);
            dispatchNotificationUpdate();
            runOnUiThread(() -> {
                String priorityLabel = block.getPriority() == BlockEntity.PRIORITY_URGENT ? "Urgent"
                        : block.getPriority() == BlockEntity.PRIORITY_HIGH ? "High" : "Normal";
                Toast.makeText(this, "Priority: " + priorityLabel, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    public void onBlockDeleted(BlockEntity block, int position) {
        List<BlockEntity> list = adapter.getBlocks();
        if (position >= 0 && position < list.size()) {
            list.remove(position);
            adapter.notifyItemRemoved(position);
            updateEmptyState(list.isEmpty());

            AppDatabase.getDatabaseExecutor().execute(() -> {
                ReminderScheduler.cancelReminder(getApplicationContext(), block.getId());
                AppDatabase.getInstance(getApplicationContext()).blockDao().deleteById(block.getId());
                realtimeDbRef.child(block.getId()).removeValue();
                firestore.collection("pages").document(pageId).collection("blocks").document(block.getId()).delete();
                FirebaseUser user = auth.getCurrentUser();
                if (user != null) {
                    firestore.collection("users").document(user.getUid())
                            .collection("pages").document(pageId)
                            .collection("blocks").document(block.getId()).delete();
                }
                dispatchNotificationUpdate();
            });
        }
    }

    @Override
    public void onStartDrag(RecyclerView.ViewHolder viewHolder) {
        itemTouchHelper.startDrag(viewHolder);
    }

    @Override
    public void onOpenUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
            return;
        }
        String targetUrl = url.trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open URL: " + targetUrl, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onOpenFile(String filePathOrUrl) {
        if (filePathOrUrl == null || filePathOrUrl.trim().isEmpty()) {
            Toast.makeText(this, "Invalid file path", Toast.LENGTH_SHORT).show();
            return;
        }

        if (filePathOrUrl.startsWith("http://") || filePathOrUrl.startsWith("https://")) {
            downloadAndOpenFile(filePathOrUrl);
            return;
        }

        if (filePathOrUrl.startsWith("data:")) {
            saveDataUriAndOpenFile(filePathOrUrl);
            return;
        }

        openLocalFile(new File(filePathOrUrl));
    }

    private void downloadAndOpenFile(String remoteUrl) {
        Toast.makeText(this, "Opening file…", Toast.LENGTH_SHORT).show();
        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                String fileName = Uri.parse(remoteUrl).getLastPathSegment();
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "file_" + Math.abs(remoteUrl.hashCode());
                }
                File cacheFile = new File(getCacheDir(), fileName);

                if (!cacheFile.exists() || cacheFile.length() == 0) {
                    java.net.URL url = new java.net.URL(remoteUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "MorphNote-Android/1.0");
                    conn.connect();

                    int respCode = conn.getResponseCode();
                    if (respCode >= 200 && respCode < 300) {
                        try (InputStream in = conn.getInputStream();
                             FileOutputStream out = new FileOutputStream(cacheFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = in.read(buffer)) != -1) {
                                out.write(buffer, 0, len);
                            }
                        }
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "Download failed (HTTP " + respCode + ")", Toast.LENGTH_SHORT).show());
                        return;
                    }
                }

                runOnUiThread(() -> openLocalFile(cacheFile));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error downloading file: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveDataUriAndOpenFile(String dataUri) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                int commaIndex = dataUri.indexOf(",");
                if (commaIndex == -1) return;
                String header = dataUri.substring(0, commaIndex);
                String base64Data = dataUri.substring(commaIndex + 1);
                byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);

                String ext = "bin";
                if (header.contains("application/pdf")) ext = "pdf";
                else if (header.contains("text/plain")) ext = "txt";
                else if (header.contains("image/jpeg")) ext = "jpg";
                else if (header.contains("image/png")) ext = "png";

                File cacheFile = new File(getCacheDir(), "attachment_" + System.currentTimeMillis() + "." + ext);
                try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                    out.write(bytes);
                }
                runOnUiThread(() -> openLocalFile(cacheFile));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error decoding file: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openLocalFile(File file) {
        if (!file.exists()) {
            Toast.makeText(this, "File not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri fileUri = FileProvider.getUriForFile(this, "com.example.morphnote.fileprovider", file);
            String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
            String mimeType = null;
            if (extension != null && !extension.isEmpty()) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
            }
            if (mimeType == null) mimeType = "*/*";

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open file with"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDueDateChipClicked(BlockEntity block) {
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.set_due_date)
                .setSelection(block.getDueDate() != null ? block.getDueDate() : MaterialDatePicker.todayInUtcMilliseconds())
                .build();

        datePicker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) return;

            Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utcCal.setTimeInMillis(selection);
            int year = utcCal.get(Calendar.YEAR);
            int month = utcCal.get(Calendar.MONTH);
            int day = utcCal.get(Calendar.DAY_OF_MONTH);

            MaterialTimePicker timePicker = new MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(12)
                    .setMinute(0)
                    .setTitleText(R.string.set_reminder)
                    .build();

            timePicker.addOnPositiveButtonClickListener(v -> {
                Calendar localCal = Calendar.getInstance();
                localCal.set(year, month, day, timePicker.getHour(), timePicker.getMinute(), 0);
                long targetTimeMs = localCal.getTimeInMillis();

                block.setDueDate(targetTimeMs);
                block.setReminderTime(targetTimeMs);
                block.setHasReminder(true);

                AppDatabase.getDatabaseExecutor().execute(() -> {
                    AppDatabase.getInstance(getApplicationContext()).blockDao().update(block);
                    syncBlockToFirebase(block);
                    ReminderScheduler.scheduleReminder(
                            getApplicationContext(),
                            block.getId(),
                            block.getContent() != null ? block.getContent() : "Scheduled Task",
                            targetTimeMs,
                            block.getPriority(),
                            false
                    );
                    dispatchNotificationUpdate();

                    runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
                        Toast.makeText(BlockEditorActivity.this,
                                "Reminder scheduled: " + fmt.format(new Date(targetTimeMs)),
                                Toast.LENGTH_SHORT).show();
                    });
                });
            });

            timePicker.show(getSupportFragmentManager(), "MATERIAL_TIME_PICKER");
        });

        datePicker.show(getSupportFragmentManager(), "MATERIAL_DATE_PICKER");
    }

    private void dispatchNotificationUpdate() {
        Context context = getApplicationContext();
        Intent intent = new Intent(context, FocusModeService.class);
        intent.setAction(FocusModeService.ACTION_UPDATE_NOTIFICATION);
        ContextCompat.startForegroundService(context, intent);
    }

    private void persistReorderedBlocks(List<BlockEntity> list) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            db.blockDao().insertAll(list);

            FirebaseUser user = auth.getCurrentUser();
            for (BlockEntity b : list) {
                realtimeDbRef.child(b.getId()).child("orderIndex").setValue(b.getOrderIndex());

                Map<String, Object> updateMap = new HashMap<>();
                updateMap.put("orderIndex", b.getOrderIndex());
                firestore.collection("pages").document(pageId)
                        .collection("blocks").document(b.getId()).update(updateMap);
                if (user != null) {
                    firestore.collection("users").document(user.getUid())
                            .collection("pages").document(pageId)
                            .collection("blocks").document(b.getId()).update(updateMap);
                }
            }
            dispatchNotificationUpdate();
        });
    }

    private void syncBlockToFirebase(BlockEntity block) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", block.getId());
        map.put("pageId", block.getPageId());
        map.put("blockType", block.getBlockType());
        map.put("content", block.getContent());
        map.put("isCompleted", block.isCompleted());
        map.put("status", block.getStatus());
        map.put("orderIndex", block.getOrderIndex());
        map.put("dueDate", block.getDueDate());
        map.put("reminderTime", block.getReminderTime());
        map.put("hasReminder", block.isHasReminder());
        map.put("priority", block.getPriority());

        // 1. Sync to Firebase Realtime Database
        realtimeDbRef.child(block.getId()).setValue(map);

        // 2. Sync to Firestore (global pages collection)
        firestore.collection("pages").document(pageId)
                .collection("blocks").document(block.getId()).set(map);

        // 3. Sync to Firestore (user-scoped pages collection)
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            firestore.collection("users").document(user.getUid())
                    .collection("pages").document(pageId)
                    .collection("blocks").document(block.getId()).set(map);
        }
    }

    @Override
    public void onImageBlockClicked(BlockEntity block, String imageUrl, String caption) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra(ImageViewerActivity.EXTRA_IMAGE_URL, imageUrl);
        intent.putExtra(ImageViewerActivity.EXTRA_IMAGE_CAPTION, caption);
        startActivity(intent);
    }

    private void sharePageAsMarkdown() {
        List<BlockEntity> blocks = adapter.getBlocks();
        StringBuilder mdBuilder = new StringBuilder();

        String title = binding.etPageTitle.getText() != null ? binding.etPageTitle.getText().toString().trim() : "";
        if (title.isEmpty()) {
            title = getString(R.string.untitled_page);
        }

        mdBuilder.append("# ").append(title).append("\n\n");

        SimpleDateFormat dueFormat = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault());

        for (BlockEntity block : blocks) {
            String type = block.getBlockType();
            String rawContent = (block.getContent() != null) ? block.getContent() : "";

            if (BlockEntity.TYPE_HEADING.equalsIgnoreCase(type)) {
                mdBuilder.append("## ").append(rawContent).append("\n\n");
            } else if (BlockEntity.TYPE_TODO.equalsIgnoreCase(type)) {
                String check = block.isCompleted() ? "- [x] " : "- [ ] ";
                mdBuilder.append(check).append(rawContent);
                if (block.getDueDate() != null && block.getDueDate() > 0) {
                    mdBuilder.append(" *(Due: ").append(dueFormat.format(new Date(block.getDueDate()))).append(")*");
                }
                mdBuilder.append("\n");
            } else if (BlockEntity.TYPE_BULLET.equalsIgnoreCase(type)) {
                mdBuilder.append("- ").append(rawContent).append("\n");
            } else if (BlockEntity.TYPE_CALLOUT.equalsIgnoreCase(type)) {
                String cTitle = block.getStructuredTitle();
                String cBody = block.getStructuredBody();
                mdBuilder.append("> [!NOTE]");
                if (!cTitle.isEmpty()) {
                    mdBuilder.append(" **").append(cTitle).append("**");
                }
                mdBuilder.append("\n");
                String[] lines = cBody.split("\n");
                for (String line : lines) {
                    mdBuilder.append("> ").append(line).append("\n");
                }
                mdBuilder.append("\n");
            } else if (BlockEntity.TYPE_CODE.equalsIgnoreCase(type)) {
                String snippetTitle = block.getStructuredTitle();
                String codeBody = block.getStructuredBody();
                String lang = "java";
                String lower = snippetTitle.toLowerCase();
                if (lower.endsWith(".py")) lang = "python";
                else if (lower.endsWith(".js")) lang = "javascript";
                else if (lower.endsWith(".ts")) lang = "typescript";
                else if (lower.endsWith(".cpp") || lower.endsWith(".c") || lower.endsWith(".h")) lang = "cpp";
                else if (lower.endsWith(".html")) lang = "html";
                else if (lower.endsWith(".xml")) lang = "xml";
                else if (lower.endsWith(".sql")) lang = "sql";

                if (!snippetTitle.isEmpty()) {
                    mdBuilder.append("### ").append(snippetTitle).append("\n");
                }
                mdBuilder.append("```").append(lang).append("\n")
                         .append(codeBody).append("\n```\n\n");
            } else if (BlockEntity.TYPE_LINK.equalsIgnoreCase(type)) {
                String[] parts = rawContent.split("\\|\\|\\|");
                String lTitle = parts[0];
                String lUrl = parts.length > 1 ? parts[1] : parts[0];
                mdBuilder.append("[").append(lTitle).append("](").append(lUrl).append(")\n\n");
            } else if (BlockEntity.TYPE_IMAGE.equalsIgnoreCase(type)) {
                String imgUrl = rawContent;
                String caption = "Image";
                if (rawContent.contains("|||")) {
                    String[] parts = rawContent.split("\\|\\|\\|", 2);
                    imgUrl = parts[0];
                    caption = parts[1];
                }
                mdBuilder.append("![").append(caption).append("](").append(imgUrl).append(")\n\n");
            } else if (BlockEntity.TYPE_FILE.equalsIgnoreCase(type)) {
                mdBuilder.append("📎 [Attached File: ").append(rawContent).append("]\n\n");
            } else {
                mdBuilder.append(rawContent).append("\n\n");
            }
        }

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        sendIntent.putExtra(Intent.EXTRA_TEXT, mdBuilder.toString());
        sendIntent.setType("text/plain");

        Intent shareChooser = Intent.createChooser(sendIntent, "Share Markdown Note via");
        startActivity(shareChooser);
    }

    private void startFocusModeService() {
        Intent serviceIntent = new Intent(this, FocusModeService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        Toast.makeText(this, "Focus Mode running for active tasks", Toast.LENGTH_SHORT).show();
    }

    private void updateEmptyState(boolean isEmpty) {
        binding.tvEmptyBlocks.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }
}

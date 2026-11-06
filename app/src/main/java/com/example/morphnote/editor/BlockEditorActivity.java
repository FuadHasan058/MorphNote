package com.example.morphnote.editor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.ActivityBlockEditorBinding;
import com.example.morphnote.reminder.ReminderScheduler;
import com.example.morphnote.service.FocusModeService;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        binding = ActivityBlockEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
        loadPageAndBlocks();
    }

    private void setupToolbar() {
        binding.ivBack.setOnClickListener(v -> finish());

        if (pageTitle != null) {
            binding.etPageTitle.setText(pageTitle);
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

        // View Mode Switcher
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
        kanbanTodoAdapter = new KanbanAdapter("TODO", block -> {
            // Move from To Do -> In Progress
            block.setPriority(BlockEntity.PRIORITY_HIGH);
            block.setCompleted(false);
            onBlockStatusChanged(block);
            populateKanbanLanes();
        });

        kanbanInProgressAdapter = new KanbanAdapter("IN_PROGRESS", block -> {
            // Move from In Progress -> Done
            block.setCompleted(true);
            onBlockStatusChanged(block);
            populateKanbanLanes();
        });

        kanbanDoneAdapter = new KanbanAdapter("DONE", block -> {
            // Move from Done -> Reopen in To Do
            block.setCompleted(false);
            block.setPriority(BlockEntity.PRIORITY_NORMAL);
            onBlockStatusChanged(block);
            populateKanbanLanes();
        });

        binding.rvKanbanTodo.setLayoutManager(new LinearLayoutManager(this));
        binding.rvKanbanTodo.setAdapter(kanbanTodoAdapter);

        binding.rvKanbanInprogress.setLayoutManager(new LinearLayoutManager(this));
        binding.rvKanbanInprogress.setAdapter(kanbanInProgressAdapter);

        binding.rvKanbanDone.setLayoutManager(new LinearLayoutManager(this));
        binding.rvKanbanDone.setAdapter(kanbanDoneAdapter);
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
                    if (b.isCompleted()) {
                        doneList.add(b);
                    } else if (b.getPriority() >= BlockEntity.PRIORITY_HIGH) {
                        inProgressList.add(b);
                    } else {
                        todoList.add(b);
                    }
                }
            }

            runOnUiThread(() -> {
                binding.tvCountTodo.setText(String.valueOf(todoList.size()));
                binding.tvCountInprogress.setText(String.valueOf(inProgressList.size()));
                binding.tvCountDone.setText(String.valueOf(doneList.size()));

                kanbanTodoAdapter.setBlocks(todoList);
                kanbanInProgressAdapter.setBlocks(inProgressList);
                kanbanDoneAdapter.setBlocks(doneList);
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
        binding.btnInsertHeading.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_HEADING));
        binding.btnInsertTodo.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_TODO));
        binding.btnInsertBullet.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_BULLET));
        binding.btnInsertCallout.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_CALLOUT));
        binding.btnInsertCode.setOnClickListener(v -> addNewBlock(BlockEntity.TYPE_CODE));
    }

    private void loadPageAndBlocks() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            currentPage = db.pageDao().getPageById(pageId);
            List<BlockEntity> blocks = db.blockDao().getBlocksForPage(pageId);

            runOnUiThread(() -> {
                if (currentPage != null && (pageTitle == null || pageTitle.isEmpty())) {
                    binding.etPageTitle.setText(currentPage.getTitle());
                }
                adapter.setBlocks(blocks);
                updateEmptyState(blocks.isEmpty());
            });
        });
    }

    private void addNewBlock(String blockType) {
        List<BlockEntity> currentList = adapter.getBlocks();
        int newIndex = currentList.size();

        BlockEntity newBlock = new BlockEntity(
                UUID.randomUUID().toString(),
                pageId,
                blockType,
                "",
                false,
                newIndex,
                null,
                null,
                false,
                BlockEntity.PRIORITY_NORMAL
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

            FirebaseUser user = auth.getCurrentUser();
            if (user != null) {
                Map<String, Object> map = new HashMap<>();
                map.put("title", newTitle);
                map.put("updatedAt", now);
                firestore.collection("users").document(user.getUid())
                        .collection("pages").document(pageId)
                        .update(map);
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
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(viewIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open URL: " + url, Toast.LENGTH_SHORT).show();
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
                            targetTimeMs
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

            for (BlockEntity b : list) {
                realtimeDbRef.child(b.getId()).child("orderIndex").setValue(b.getOrderIndex());
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
        map.put("orderIndex", block.getOrderIndex());
        map.put("dueDate", block.getDueDate());
        map.put("reminderTime", block.getReminderTime());
        map.put("hasReminder", block.isHasReminder());
        map.put("priority", block.getPriority());

        realtimeDbRef.child(block.getId()).setValue(map);
    }

    private void sharePageAsMarkdown() {
        List<BlockEntity> blocks = adapter.getBlocks();
        StringBuilder mdBuilder = new StringBuilder();

        String title = binding.etPageTitle.getText() != null ? binding.etPageTitle.getText().toString().trim() : "";
        if (title.isEmpty()) {
            title = getString(R.string.untitled_page);
        }

        mdBuilder.append("# ").append(title).append("\n\n");

        for (BlockEntity block : blocks) {
            String type = block.getBlockType();
            String content = (block.getContent() != null) ? block.getContent() : "";

            if (BlockEntity.TYPE_HEADING.equalsIgnoreCase(type)) {
                mdBuilder.append("## ").append(content).append("\n\n");
            } else if (BlockEntity.TYPE_TODO.equalsIgnoreCase(type)) {
                mdBuilder.append(block.isCompleted() ? "- [x] " : "- [ ] ")
                        .append(content).append("\n");
            } else if (BlockEntity.TYPE_BULLET.equalsIgnoreCase(type)) {
                mdBuilder.append("* ").append(content).append("\n");
            } else if (BlockEntity.TYPE_CALLOUT.equalsIgnoreCase(type)) {
                mdBuilder.append("> 💡 ").append(content).append("\n\n");
            } else if (BlockEntity.TYPE_CODE.equalsIgnoreCase(type)) {
                mdBuilder.append("```\n").append(content).append("\n```\n\n");
            } else {
                mdBuilder.append(content).append("\n\n");
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

package com.example.morphnote.editor;

import android.Manifest;
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

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.ActivityBlockEditorBinding;
import com.example.morphnote.service.FocusModeService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BlockEditorActivity extends AppCompatActivity implements BlockAdapter.OnBlockActionListener {

    public static final String EXTRA_PAGE_ID = "com.example.morphnote.EXTRA_PAGE_ID";
    public static final String EXTRA_PAGE_TITLE = "com.example.morphnote.EXTRA_PAGE_TITLE";

    private ActivityBlockEditorBinding binding;
    private BlockAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

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
            Toast.makeText(this, "Invalid page", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        auth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        realtimeDbRef = FirebaseDatabase.getInstance().getReference("pages").child(pageId).child("blocks");

        setupToolbar();
        setupRecyclerView();
        setupInsertToolbar();
        loadPageAndBlocks();
    }

    private void setupToolbar() {
        binding.ivBack.setOnClickListener(v -> finish());

        if (pageTitle != null) {
            binding.etPageTitle.setText(pageTitle);
        }

        binding.etPageTitle.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (binding.etPageTitle.hasFocus()) {
                    updatePageTitle(s.toString());
                }
            }
        });

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

    private void setupRecyclerView() {
        adapter = new BlockAdapter(this);
        binding.rvBlocks.setLayoutManager(new LinearLayoutManager(this));
        binding.rvBlocks.setAdapter(adapter);

        // Drag-and-drop reordering with ItemTouchHelper
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
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Swiping not used; items are deleted via trash icon
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // Persist new orderIndex values to Room and Firebase
                persistReorderedBlocks(adapter.getBlocks());
            }
        };

        itemTouchHelper = new ItemTouchHelper(touchCallback);
        itemTouchHelper.attachToRecyclerView(binding.rvBlocks);
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
                newIndex
        );

        currentList.add(newBlock);
        adapter.notifyItemInserted(newIndex);
        binding.rvBlocks.smoothScrollToPosition(newIndex);
        updateEmptyState(false);

        // Save to Room and Firebase Realtime Database
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().insert(newBlock);
            syncBlockToFirebase(newBlock);
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

            // Sync with Firestore
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
        });
    }

    @Override
    public void onBlockStatusChanged(BlockEntity block) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase.getInstance(getApplicationContext()).blockDao().update(block);
            syncBlockToFirebase(block);
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
                AppDatabase.getInstance(getApplicationContext()).blockDao().deleteById(block.getId());
                realtimeDbRef.child(block.getId()).removeValue();
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

    private void persistReorderedBlocks(List<BlockEntity> list) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            db.blockDao().insertAll(list);

            // Sync orderIndex to Realtime Database
            for (BlockEntity b : list) {
                realtimeDbRef.child(b.getId()).child("orderIndex").setValue(b.getOrderIndex());
            }
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

        realtimeDbRef.child(block.getId()).setValue(map);
    }

    // Markdown Serializer & Implicit ACTION_SEND Intent
    private void sharePageAsMarkdown() {
        List<BlockEntity> blocks = adapter.getBlocks();
        StringBuilder mdBuilder = new StringBuilder();

        String title = binding.etPageTitle.getText() != null ? binding.etPageTitle.getText().toString().trim() : "";
        if (title.isEmpty()) {
            title = "Untitled Page";
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


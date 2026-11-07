package com.example.morphnote;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.FragmentCanvasesBinding;
import com.example.morphnote.editor.BlockEditorActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CanvasesFragment extends Fragment implements PageAdapter.OnPageClickListener {

    private FragmentCanvasesBinding binding;
    private PageAdapter pageAdapter;
    private List<PageEntity> allPages = new ArrayList<>();
    private String currentFilter = "INBOX";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCanvasesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        pageAdapter = new PageAdapter(this);

        updateLayoutManager(getResources().getConfiguration().orientation);
        binding.rvPages.setNestedScrollingEnabled(true);
        binding.rvPages.setAdapter(pageAdapter);
        loadPages();
        syncPagesFromCloud();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayoutManager(newConfig.orientation);
    }

    private void updateLayoutManager(int orientation) {
        if (binding == null || getContext() == null) return;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.rvPages.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        } else {
            binding.rvPages.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPages();
        syncPagesFromCloud();
    }

    public void loadPages() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null || getContext() == null) return;

        final String userId = currentUser.getUid();
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            List<PageEntity> pages = db.pageDao().getPagesForUser(userId);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allPages = pages != null ? pages : new ArrayList<>();
                    applyFilter(currentFilter);
                });
            }
        });
    }

    public void syncPagesFromCloud() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null || getContext() == null) return;
        final String userId = currentUser.getUid();

        FirebaseFirestore fs = FirebaseFirestore.getInstance();

        // 1. Fetch from global pages collection
        fs.collection("pages").whereEqualTo("userId", userId).get()
                .addOnSuccessListener(snapshots -> {
                    if (snapshots != null && !snapshots.isEmpty()) {
                        importPagesFromCloud(snapshots.getDocuments(), userId);
                    }
                });

        // 2. Fetch from users/{uid}/pages subcollection
        fs.collection("users").document(userId).collection("pages").get()
                .addOnSuccessListener(snapshots -> {
                    if (snapshots != null && !snapshots.isEmpty()) {
                        importPagesFromCloud(snapshots.getDocuments(), userId);
                    }
                });
    }

    private void importPagesFromCloud(List<DocumentSnapshot> docs, String userId) {
        if (docs == null || docs.isEmpty() || getContext() == null) return;

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            List<PageEntity> imported = new ArrayList<>();

            for (DocumentSnapshot doc : docs) {
                String id = doc.getString("id");
                if (id == null || id.isEmpty()) id = doc.getId();
                String title = doc.getString("title");
                if (title == null) title = "Untitled Canvas";
                Long updatedAt = doc.getLong("updatedAt");
                if (updatedAt == null) updatedAt = System.currentTimeMillis();

                PageEntity page = new PageEntity(id, userId, title, updatedAt);
                imported.add(page);
                db.pageDao().insert(page);

                // Download page blocks from Realtime Database as well
                syncBlocksForPage(id);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(this::loadPages);
            }
        });
    }

    private void syncBlocksForPage(String pageId) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        FirebaseFirestore fs = FirebaseFirestore.getInstance();

        // 1. Fetch from Firestore global pages/{pageId}/blocks
        fs.collection("pages").document(pageId).collection("blocks").get()
                .addOnSuccessListener(querySnapshot -> {
                    if (querySnapshot != null && !querySnapshot.isEmpty() && getContext() != null) {
                        List<BlockEntity> list = new ArrayList<>();
                        for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                            BlockEntity b = BlockEditorActivity.parseBlockFromFirestore(doc);
                            if (b != null) list.add(b);
                        }
                        if (!list.isEmpty()) {
                            reconcileAndSaveBlocks(pageId, list);
                        }
                    }
                });

        // 2. Fetch from Firestore users/{uid}/pages/{pageId}/blocks
        if (currentUser != null) {
            fs.collection("users").document(currentUser.getUid())
                    .collection("pages").document(pageId).collection("blocks").get()
                    .addOnSuccessListener(querySnapshot -> {
                        if (querySnapshot != null && !querySnapshot.isEmpty() && getContext() != null) {
                            List<BlockEntity> list = new ArrayList<>();
                            for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                                BlockEntity b = BlockEditorActivity.parseBlockFromFirestore(doc);
                                if (b != null) list.add(b);
                            }
                            if (!list.isEmpty()) {
                                reconcileAndSaveBlocks(pageId, list);
                            }
                        }
                    });
        }

        // 3. Fallback to Firebase Realtime Database
        FirebaseDatabase.getInstance().getReference("pages").child(pageId).child("blocks")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists() || getContext() == null) return;
                        List<BlockEntity> blocks = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            BlockEntity b = BlockEditorActivity.parseBlockFromDataSnapshot(child);
                            if (b != null) {
                                blocks.add(b);
                            }
                        }
                        if (!blocks.isEmpty()) {
                            reconcileAndSaveBlocks(pageId, blocks);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void reconcileAndSaveBlocks(String pageId, List<BlockEntity> cloudBlocks) {
        if (cloudBlocks == null || getContext() == null) return;
        AppDatabase.getDatabaseExecutor().execute(() -> {
            if (getContext() == null) return;
            AppDatabase db = AppDatabase.getInstance(requireContext());
            List<BlockEntity> localBlocks = db.blockDao().getBlocksForPage(pageId);
            Set<String> cloudIds = new HashSet<>();
            for (BlockEntity cb : cloudBlocks) {
                cloudIds.add(cb.getId());
            }
            // Delete any local block that was removed from cloud
            for (BlockEntity lb : localBlocks) {
                if (!cloudIds.contains(lb.getId())) {
                    db.blockDao().deleteById(lb.getId());
                }
            }
            db.blockDao().insertAll(cloudBlocks);
        });
    }

    public void setFilter(String filter) {
        this.currentFilter = filter;
        applyFilter(filter);
    }

    private void applyFilter(String filter) {
        if (allPages == null || binding == null || getContext() == null) return;

        if ("INBOX".equalsIgnoreCase(filter)) {
            displayPages(allPages, "INBOX");
            return;
        }

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            Set<String> matchingPageIds = new HashSet<>();

            if ("TODAY".equalsIgnoreCase(filter)) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long dayStart = cal.getTimeInMillis();
                long dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1;
                List<BlockEntity> blocks = db.blockDao().getBlocksByDateRange(dayStart, dayEnd);
                for (BlockEntity b : blocks) {
                    if (b.getPageId() != null) matchingPageIds.add(b.getPageId());
                }
            } else if ("UPCOMING".equalsIgnoreCase(filter)) {
                long now = System.currentTimeMillis();
                long future = now + 7L * 24 * 60 * 60 * 1000L;
                List<BlockEntity> blocks = db.blockDao().getBlocksByDateRange(now, future);
                for (BlockEntity b : blocks) {
                    if (b.getPageId() != null) matchingPageIds.add(b.getPageId());
                }
            } else if ("PRIORITY".equalsIgnoreCase(filter)) {
                List<BlockEntity> blocks = db.blockDao().getHighPriorityPending();
                for (BlockEntity b : blocks) {
                    if (b.getPageId() != null) matchingPageIds.add(b.getPageId());
                }
            }

            List<PageEntity> filtered = new ArrayList<>();
            for (PageEntity p : allPages) {
                if (matchingPageIds.contains(p.getId())) {
                    filtered.add(p);
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> displayPages(filtered, filter));
            }
        });
    }

    private void displayPages(List<PageEntity> pages, String filter) {
        if (pageAdapter != null) {
            pageAdapter.setPages(pages);
        }
        if (binding != null) {
            if (pages == null || pages.isEmpty()) {
                binding.tvEmpty.setVisibility(View.VISIBLE);
                binding.rvPages.setVisibility(View.GONE);

                if ("PRIORITY".equalsIgnoreCase(filter)) {
                    binding.tvEmpty.setText("No high priority canvases.\nTap the flag icon on any task to mark it high priority.");
                } else if ("TODAY".equalsIgnoreCase(filter)) {
                    binding.tvEmpty.setText("No canvases with tasks due today.");
                } else if ("UPCOMING".equalsIgnoreCase(filter)) {
                    binding.tvEmpty.setText("No canvases with upcoming tasks.");
                } else {
                    binding.tvEmpty.setText(R.string.no_pages);
                }
            } else {
                binding.tvEmpty.setVisibility(View.GONE);
                binding.rvPages.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onPageClick(PageEntity page) {
        Intent intent = new Intent(requireContext(), BlockEditorActivity.class);
        intent.putExtra(BlockEditorActivity.EXTRA_PAGE_ID, page.getId());
        intent.putExtra(BlockEditorActivity.EXTRA_PAGE_TITLE, page.getTitle());
        startActivity(intent);
    }

    @Override
    public void onPageDelete(PageEntity page) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm_delete_page)
                .setMessage("Are you sure you want to delete \"" + page.getTitle() + "\"?")
                .setPositiveButton(R.string.yes, (dialog, which) -> deletePage(page))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deletePage(PageEntity page) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            db.blockDao().deleteByPageId(page.getId());
            db.pageDao().delete(page);

            FirebaseFirestore.getInstance().collection("pages").document(page.getId()).delete();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                        .collection("pages").document(page.getId()).delete();
            }
            FirebaseDatabase.getInstance().getReference("pages").child(page.getId()).removeValue();

            if (getActivity() != null) {
                getActivity().runOnUiThread(this::loadPages);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

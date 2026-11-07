package com.example.morphnote.editor;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import com.example.morphnote.R;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.databinding.ItemBlockBulletBinding;
import com.example.morphnote.databinding.ItemBlockCalloutBinding;
import com.example.morphnote.databinding.ItemBlockCodeBinding;
import com.example.morphnote.databinding.ItemBlockFileBinding;
import com.example.morphnote.databinding.ItemBlockHeadingBinding;
import com.example.morphnote.databinding.ItemBlockImageBinding;
import com.example.morphnote.databinding.ItemBlockLinkBinding;
import com.example.morphnote.databinding.ItemBlockTodoBinding;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BlockAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_HEADING = 0;
    public static final int VIEW_TYPE_TODO    = 1;
    public static final int VIEW_TYPE_BULLET  = 2;
    public static final int VIEW_TYPE_CALLOUT = 3;
    public static final int VIEW_TYPE_CODE    = 4;
    public static final int VIEW_TYPE_LINK    = 5;
    public static final int VIEW_TYPE_IMAGE   = 6;
    public static final int VIEW_TYPE_FILE    = 7;

    public interface OnBlockActionListener {
        void onBlockContentChanged(BlockEntity block);
        void onBlockStatusChanged(BlockEntity block);
        void onBlockDeleted(BlockEntity block, int position);
        void onStartDrag(RecyclerView.ViewHolder viewHolder);
        void onOpenUrl(String url);
        void onDueDateChipClicked(BlockEntity block);
        void onOpenFile(String filePath);
        void onPriorityToggle(BlockEntity block);
        void onImageBlockClicked(BlockEntity block, String imageUrl, String caption);
    }

    private List<BlockEntity> blocks = new ArrayList<>();
    private final OnBlockActionListener actionListener;
    private RecyclerView recyclerView;

    public BlockAdapter(OnBlockActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.recyclerView = null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setBlocks(List<BlockEntity> blockList) {
        this.blocks = (blockList != null) ? blockList : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateBlockWithoutFocusLoss(BlockEntity updatedBlock) {
        if (updatedBlock == null) return;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).getId().equals(updatedBlock.getId())) {
                blocks.set(i, updatedBlock);
                if (recyclerView != null) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder != null && holder.itemView.hasFocus()) {
                        // User is actively editing this block; do NOT rebind to avoid cursor snapping
                        return;
                    }
                }
                notifyItemChanged(i);
                break;
            }
        }
    }

    public List<BlockEntity> getBlocks() {
        return blocks;
    }

    @Override
    public int getItemViewType(int position) {
        BlockEntity block = blocks.get(position);
        String type = block.getBlockType();
        if (BlockEntity.TYPE_TODO.equalsIgnoreCase(type)) {
            return VIEW_TYPE_TODO;
        } else if (BlockEntity.TYPE_BULLET.equalsIgnoreCase(type)) {
            return VIEW_TYPE_BULLET;
        } else if (BlockEntity.TYPE_CALLOUT.equalsIgnoreCase(type)) {
            return VIEW_TYPE_CALLOUT;
        } else if (BlockEntity.TYPE_CODE.equalsIgnoreCase(type)) {
            return VIEW_TYPE_CODE;
        } else if (BlockEntity.TYPE_LINK.equalsIgnoreCase(type)) {
            return VIEW_TYPE_LINK;
        } else if (BlockEntity.TYPE_IMAGE.equalsIgnoreCase(type)) {
            return VIEW_TYPE_IMAGE;
        } else if (BlockEntity.TYPE_FILE.equalsIgnoreCase(type)) {
            return VIEW_TYPE_FILE;
        } else {
            return VIEW_TYPE_HEADING;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_TYPE_TODO:
                return new TodoViewHolder(ItemBlockTodoBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_BULLET:
                return new BulletViewHolder(ItemBlockBulletBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_CALLOUT:
                return new CalloutViewHolder(ItemBlockCalloutBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_CODE:
                return new CodeViewHolder(ItemBlockCodeBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_LINK:
                return new LinkViewHolder(ItemBlockLinkBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_IMAGE:
                return new ImageViewHolder(ItemBlockImageBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_FILE:
                return new FileViewHolder(ItemBlockFileBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_HEADING:
            default:
                return new HeadingViewHolder(ItemBlockHeadingBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        BlockEntity block = blocks.get(position);
        if (holder instanceof HeadingViewHolder) {
            ((HeadingViewHolder) holder).bind(block);
        } else if (holder instanceof TodoViewHolder) {
            ((TodoViewHolder) holder).bind(block);
        } else if (holder instanceof BulletViewHolder) {
            ((BulletViewHolder) holder).bind(block);
        } else if (holder instanceof CalloutViewHolder) {
            ((CalloutViewHolder) holder).bind(block);
        } else if (holder instanceof CodeViewHolder) {
            ((CodeViewHolder) holder).bind(block);
        } else if (holder instanceof LinkViewHolder) {
            ((LinkViewHolder) holder).bind(block);
        } else if (holder instanceof ImageViewHolder) {
            ((ImageViewHolder) holder).bind(block);
        } else if (holder instanceof FileViewHolder) {
            ((FileViewHolder) holder).bind(block);
        }
    }

    @Override
    public int getItemCount() {
        return blocks.size();
    }

    private static void bindDateChip(Context context, com.google.android.material.chip.Chip chip,
                                     Long dueDate, boolean isCompleted, Runnable onClick) {
        if (chip == null) return;
        if (dueDate == null || dueDate <= 0) {
            chip.setText("+ Date");
            chip.setChipBackgroundColorResource(R.color.surface_variant);
            chip.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            chip.setVisibility(View.VISIBLE);
        } else {
            long now = System.currentTimeMillis();
            Calendar dueCal = Calendar.getInstance();
            dueCal.setTimeInMillis(dueDate);
            Calendar todayCal = Calendar.getInstance();

            boolean isToday = (dueCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR)
                    && dueCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR));

            SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
            SimpleDateFormat dateFmt = new SimpleDateFormat("MMM d", Locale.getDefault());

            if (dueDate < now && !isCompleted) {
                chip.setText("Overdue • " + dateFmt.format(new Date(dueDate)));
                chip.setChipBackgroundColorResource(R.color.date_chip_overdue_bg);
                chip.setTextColor(ContextCompat.getColor(context, R.color.date_chip_overdue_text));
            } else if (isToday) {
                chip.setText("Today • " + timeFmt.format(new Date(dueDate)));
                chip.setChipBackgroundColorResource(R.color.date_chip_today_bg);
                chip.setTextColor(ContextCompat.getColor(context, R.color.date_chip_today_text));
            } else {
                chip.setText(dateFmt.format(new Date(dueDate)) + " • " + timeFmt.format(new Date(dueDate)));
                chip.setChipBackgroundColorResource(R.color.date_chip_upcoming_bg);
                chip.setTextColor(ContextCompat.getColor(context, R.color.date_chip_upcoming_text));
            }
            chip.setVisibility(View.VISIBLE);
        }

        chip.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
    }

    // ── 1. Heading ViewHolder ─────────────────────────────────────────────────
    public class HeadingViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockHeadingBinding binding;
        private TextWatcher textWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public HeadingViewHolder(@NonNull ItemBlockHeadingBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(HeadingViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });
        }

        public void bind(BlockEntity block) {
            if (textWatcher != null) {
                binding.etHeadingContent.removeTextChangedListener(textWatcher);
            }

            String currentContent = block.getContent() != null ? block.getContent() : "";
            if (!binding.etHeadingContent.getText().toString().equals(currentContent)) {
                int cursor = binding.etHeadingContent.getSelectionStart();
                binding.etHeadingContent.setText(currentContent);
                if (binding.etHeadingContent.hasFocus()) {
                    binding.etHeadingContent.setSelection(Math.min(cursor, binding.etHeadingContent.getText().length()));
                }
            }

            Context context = binding.getRoot().getContext();
            if (block.getDueDate() != null && block.getDueDate() > 0) {
                bindDateChip(context, binding.chipDueDate, block.getDueDate(), false, () -> {
                    if (actionListener != null) actionListener.onDueDateChipClicked(block);
                });
            } else {
                binding.chipDueDate.setVisibility(View.GONE);
            }

            textWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etHeadingContent.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etHeadingContent.addTextChangedListener(textWatcher);
        }
    }

    // ── 2. To-Do ViewHolder with Priority Toggle ──────────────────────────────
    public class TodoViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockTodoBinding binding;
        private TextWatcher textWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public TodoViewHolder(@NonNull ItemBlockTodoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(TodoViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });

            binding.ivPriority.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    BlockEntity block = blocks.get(pos);
                    int nextPriority = (block.getPriority() + 1) % 3;
                    block.setPriority(nextPriority);
                    applyPriorityStyle(binding.ivPriority, nextPriority);
                    if (actionListener != null) {
                        actionListener.onPriorityToggle(block);
                    }
                }
            });

            binding.chipTaskStatus.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    BlockEntity b = blocks.get(pos);
                    String current = b.getStatus();
                    String next;
                    if (BlockEntity.STATUS_TODO.equalsIgnoreCase(current)) {
                        next = BlockEntity.STATUS_IN_PROGRESS;
                    } else if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(current)) {
                        next = BlockEntity.STATUS_DONE;
                    } else {
                        next = BlockEntity.STATUS_TODO;
                    }
                    b.setStatus(next);
                    applyStatusStyle(binding.chipTaskStatus, next);
                    binding.cbTodo.setOnCheckedChangeListener(null);
                    binding.cbTodo.setChecked(b.isCompleted());
                    animateTodoStyle(b.isCompleted());
                    Context context = binding.getRoot().getContext();
                    bindDateChip(context, binding.chipDueDate, b.getDueDate(), b.isCompleted(), () -> {
                        if (actionListener != null) actionListener.onDueDateChipClicked(b);
                    });
                    attachCheckboxListener(b);
                    if (actionListener != null) {
                        actionListener.onBlockStatusChanged(b);
                    }
                }
            });
        }

        private void attachCheckboxListener(BlockEntity block) {
            binding.cbTodo.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    BlockEntity b = blocks.get(pos);
                    b.setCompleted(isChecked);
                    applyStatusStyle(binding.chipTaskStatus, b.getStatus());
                    animateTodoStyle(isChecked);
                    Context context = binding.getRoot().getContext();
                    bindDateChip(context, binding.chipDueDate, b.getDueDate(), isChecked, () -> {
                        if (actionListener != null) actionListener.onDueDateChipClicked(b);
                    });
                    if (actionListener != null) {
                        actionListener.onBlockStatusChanged(b);
                    }
                }
            });
        }

        public void bind(BlockEntity block) {
            if (textWatcher != null) {
                binding.etTodoContent.removeTextChangedListener(textWatcher);
            }

            binding.cbTodo.setOnCheckedChangeListener(null);
            binding.cbTodo.setChecked(block.isCompleted());

            String currentContent = block.getContent() != null ? block.getContent() : "";
            if (!binding.etTodoContent.getText().toString().equals(currentContent)) {
                int cursor = binding.etTodoContent.getSelectionStart();
                binding.etTodoContent.setText(currentContent);
                if (binding.etTodoContent.hasFocus()) {
                    binding.etTodoContent.setSelection(Math.min(cursor, binding.etTodoContent.getText().length()));
                }
            }

            applyTodoStyleImmediate(block.isCompleted());
            applyPriorityStyle(binding.ivPriority, block.getPriority());
            applyStatusStyle(binding.chipTaskStatus, block.getStatus());

            Context context = binding.getRoot().getContext();
            bindDateChip(context, binding.chipDueDate, block.getDueDate(), block.isCompleted(), () -> {
                if (actionListener != null) actionListener.onDueDateChipClicked(block);
            });

            attachCheckboxListener(block);

            textWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etTodoContent.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etTodoContent.addTextChangedListener(textWatcher);
        }

        private void applyStatusStyle(com.google.android.material.chip.Chip chip, String status) {
            if (chip == null) return;
            Context context = chip.getContext();
            if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(status)) {
                chip.setText("In Progress ⏳");
                chip.setChipBackgroundColorResource(R.color.warning_container);
                chip.setTextColor(ContextCompat.getColor(context, R.color.warning));
                chip.setChipStrokeColorResource(R.color.warning);
            } else if (BlockEntity.STATUS_DONE.equalsIgnoreCase(status)) {
                chip.setText("Done ✓");
                chip.setChipBackgroundColorResource(R.color.success_container);
                chip.setTextColor(ContextCompat.getColor(context, R.color.success));
                chip.setChipStrokeColorResource(R.color.success);
            } else {
                chip.setText("To Do");
                chip.setChipBackgroundColorResource(R.color.surface_variant);
                chip.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
                chip.setChipStrokeColorResource(R.color.outline);
            }
        }

        private void applyPriorityStyle(ImageView iv, int priority) {
            if (priority == BlockEntity.PRIORITY_URGENT) {
                iv.setColorFilter(Color.parseColor("#EF4444"));
                iv.setAlpha(1.0f);
            } else if (priority == BlockEntity.PRIORITY_HIGH) {
                iv.setColorFilter(Color.parseColor("#F59E0B"));
                iv.setAlpha(1.0f);
            } else {
                iv.setColorFilter(Color.parseColor("#94A3B8"));
                iv.setAlpha(0.4f);
            }
        }

        private void applyTodoStyleImmediate(boolean isCompleted) {
            Context context = binding.getRoot().getContext();
            if (isCompleted) {
                binding.etTodoContent.setPaintFlags(
                        binding.etTodoContent.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                binding.etTodoContent.setTextColor(ContextCompat.getColor(context, R.color.todo_completed_text));
                binding.getRoot().setAlpha(0.65f);
            } else {
                binding.etTodoContent.setPaintFlags(
                        binding.etTodoContent.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                binding.etTodoContent.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                binding.getRoot().setAlpha(1.0f);
            }
        }

        private void animateTodoStyle(boolean isCompleted) {
            Context context = binding.getRoot().getContext();
            int colorFrom = isCompleted ? ContextCompat.getColor(context, R.color.text_primary)
                    : ContextCompat.getColor(context, R.color.todo_completed_text);
            int colorTo = isCompleted ? ContextCompat.getColor(context, R.color.todo_completed_text)
                    : ContextCompat.getColor(context, R.color.text_primary);

            ValueAnimator colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, colorTo);
            colorAnim.setDuration(250);
            colorAnim.addUpdateListener(animator ->
                    binding.etTodoContent.setTextColor((int) animator.getAnimatedValue()));
            colorAnim.start();

            float targetAlpha = isCompleted ? 0.65f : 1.0f;
            binding.getRoot().animate().alpha(targetAlpha).setDuration(250).start();

            if (isCompleted) {
                binding.etTodoContent.setPaintFlags(
                        binding.etTodoContent.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                binding.etTodoContent.setPaintFlags(
                        binding.etTodoContent.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            }
        }
    }

    // ── 3. Bullet ViewHolder ──────────────────────────────────────────────────
    public class BulletViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockBulletBinding binding;
        private TextWatcher textWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public BulletViewHolder(@NonNull ItemBlockBulletBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(BulletViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });
        }

        public void bind(BlockEntity block) {
            if (textWatcher != null) {
                binding.etBulletContent.removeTextChangedListener(textWatcher);
            }

            String currentContent = block.getContent() != null ? block.getContent() : "";
            if (!binding.etBulletContent.getText().toString().equals(currentContent)) {
                int cursor = binding.etBulletContent.getSelectionStart();
                binding.etBulletContent.setText(currentContent);
                if (binding.etBulletContent.hasFocus()) {
                    binding.etBulletContent.setSelection(Math.min(cursor, binding.etBulletContent.getText().length()));
                }
            }

            textWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etBulletContent.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etBulletContent.addTextChangedListener(textWatcher);
        }
    }

    // ── 4. Callout ViewHolder ─────────────────────────────────────────────────
    public class CalloutViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockCalloutBinding binding;
        private TextWatcher titleWatcher;
        private TextWatcher contentWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public CalloutViewHolder(@NonNull ItemBlockCalloutBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(CalloutViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });
        }

        public void bind(BlockEntity block) {
            if (titleWatcher != null) {
                binding.etCalloutTitle.removeTextChangedListener(titleWatcher);
            }
            if (contentWatcher != null) {
                binding.etCalloutContent.removeTextChangedListener(contentWatcher);
            }

            String currentTitle = block.getStructuredTitle() != null ? block.getStructuredTitle() : "";
            if (!binding.etCalloutTitle.getText().toString().equals(currentTitle)) {
                int cursor = binding.etCalloutTitle.getSelectionStart();
                binding.etCalloutTitle.setText(currentTitle);
                if (binding.etCalloutTitle.hasFocus()) {
                    binding.etCalloutTitle.setSelection(Math.min(cursor, binding.etCalloutTitle.getText().length()));
                }
            }

            String currentBody = block.getStructuredBody() != null ? block.getStructuredBody() : "";
            if (!binding.etCalloutContent.getText().toString().equals(currentBody)) {
                int cursor = binding.etCalloutContent.getSelectionStart();
                binding.etCalloutContent.setText(currentBody);
                if (binding.etCalloutContent.hasFocus()) {
                    binding.etCalloutContent.setSelection(Math.min(cursor, binding.etCalloutContent.getText().length()));
                }
            }

            titleWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etCalloutTitle.hasFocus()) {
                        String currentBody = block.getStructuredBody();
                        block.setStructuredContent(s.toString(), currentBody);
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etCalloutTitle.addTextChangedListener(titleWatcher);

            contentWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etCalloutContent.hasFocus()) {
                        String currentTitle = block.getStructuredTitle();
                        block.setStructuredContent(currentTitle, s.toString());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etCalloutContent.addTextChangedListener(contentWatcher);
        }
    }

    // ── 5. Code ViewHolder ────────────────────────────────────────────────────
    public class CodeViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockCodeBinding binding;
        private TextWatcher titleWatcher;
        private TextWatcher contentWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public CodeViewHolder(@NonNull ItemBlockCodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(CodeViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });
        }

        public void bind(BlockEntity block) {
            if (titleWatcher != null) {
                binding.etCodeTitle.removeTextChangedListener(titleWatcher);
            }
            if (contentWatcher != null) {
                binding.etCodeContent.removeTextChangedListener(contentWatcher);
            }

            String currentTitle = block.getStructuredTitle() != null ? block.getStructuredTitle() : "";
            String currentBody = block.getStructuredBody() != null ? block.getStructuredBody() : "";

            if (!binding.etCodeTitle.getText().toString().equals(currentTitle)) {
                int cursor = binding.etCodeTitle.getSelectionStart();
                binding.etCodeTitle.setText(currentTitle);
                if (binding.etCodeTitle.hasFocus()) {
                    binding.etCodeTitle.setSelection(Math.min(cursor, binding.etCodeTitle.getText().length()));
                }
            }

            if (!binding.etCodeContent.getText().toString().equals(currentBody)) {
                int cursor = binding.etCodeContent.getSelectionStart();
                binding.etCodeContent.setText(currentBody);
                if (binding.etCodeContent.hasFocus()) {
                    binding.etCodeContent.setSelection(Math.min(cursor, binding.etCodeContent.getText().length()));
                }
            }

            updateLanguageBadge(currentTitle);

            // One-tap copy code button
            binding.btnCopyCode.setOnClickListener(v -> {
                Context context = binding.getRoot().getContext();
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("Code Snippet", block.getStructuredBody());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });

            titleWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etCodeTitle.hasFocus()) {
                        String newTitle = s.toString();
                        block.setStructuredContent(newTitle, block.getStructuredBody());
                        updateLanguageBadge(newTitle);
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etCodeTitle.addTextChangedListener(titleWatcher);

            contentWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etCodeContent.hasFocus()) {
                        block.setStructuredContent(block.getStructuredTitle(), s.toString());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etCodeContent.addTextChangedListener(contentWatcher);
        }

        private void updateLanguageBadge(String title) {
            String lower = (title != null) ? title.trim().toLowerCase() : "";
            if (lower.endsWith(".java")) {
                binding.tvCodeLanguage.setText("JAVA");
            } else if (lower.endsWith(".py")) {
                binding.tvCodeLanguage.setText("PYTHON");
            } else if (lower.endsWith(".js") || lower.endsWith(".ts")) {
                binding.tvCodeLanguage.setText("JS/TS");
            } else if (lower.endsWith(".cpp") || lower.endsWith(".c") || lower.endsWith(".h")) {
                binding.tvCodeLanguage.setText("C/C++");
            } else if (lower.endsWith(".html") || lower.endsWith(".xml")) {
                binding.tvCodeLanguage.setText("MARKUP");
            } else if (lower.endsWith(".sql")) {
                binding.tvCodeLanguage.setText("SQL");
            } else if (lower.endsWith(".json")) {
                binding.tvCodeLanguage.setText("JSON");
            } else {
                binding.tvCodeLanguage.setText("CODE");
            }
        }
    }

    // ── 6. Link ViewHolder (Interactive Link Card) ────────────────────────────
    public class LinkViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockLinkBinding binding;
        private TextWatcher titleWatcher;
        private TextWatcher urlWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public LinkViewHolder(@NonNull ItemBlockLinkBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(LinkViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });

            binding.btnOpenLink.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    String raw = blocks.get(pos).getContent();
                    String url = extractUrl(raw);
                    actionListener.onOpenUrl(url);
                }
            });
        }

        public void bind(BlockEntity block) {
            if (titleWatcher != null) binding.etLinkTitle.removeTextChangedListener(titleWatcher);
            if (urlWatcher != null) binding.etLinkUrl.removeTextChangedListener(urlWatcher);

            String content = block.getContent() != null ? block.getContent() : "";
            String title = "";
            String url = "";
            if (content.contains("|||")) {
                String[] parts = content.split("\\|\\|\\|", 2);
                title = parts[0];
                url = parts.length > 1 ? parts[1] : "";
            } else {
                url = content;
                title = "Link";
            }

            if (!binding.etLinkTitle.getText().toString().equals(title)) {
                int cursor = binding.etLinkTitle.getSelectionStart();
                binding.etLinkTitle.setText(title);
                if (binding.etLinkTitle.hasFocus()) {
                    binding.etLinkTitle.setSelection(Math.min(cursor, binding.etLinkTitle.getText().length()));
                }
            }

            if (!binding.etLinkUrl.getText().toString().equals(url)) {
                int cursor = binding.etLinkUrl.getSelectionStart();
                binding.etLinkUrl.setText(url);
                if (binding.etLinkUrl.hasFocus()) {
                    binding.etLinkUrl.setSelection(Math.min(cursor, binding.etLinkUrl.getText().length()));
                }
            }

            titleWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (binding.etLinkTitle.hasFocus()) {
                        String currentUrl = binding.etLinkUrl.getText().toString().trim();
                        block.setContent(s.toString().trim() + "|||" + currentUrl);
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etLinkTitle.addTextChangedListener(titleWatcher);

            urlWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (binding.etLinkUrl.hasFocus()) {
                        String currentTitle = binding.etLinkTitle.getText().toString().trim();
                        block.setContent(currentTitle + "|||" + s.toString().trim());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etLinkUrl.addTextChangedListener(urlWatcher);
        }

        private String extractUrl(String raw) {
            if (raw == null) return "https://google.com";
            if (raw.contains("|||")) {
                String[] parts = raw.split("\\|\\|\\|", 2);
                return parts.length > 1 ? parts[1] : parts[0];
            }
            return raw;
        }
    }

    // ── 7. Image ViewHolder ───────────────────────────────────────────────────
    public class ImageViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockImageBinding binding;
        private TextWatcher captionWatcher;

        @SuppressLint("ClickableViewAccessibility")
        public ImageViewHolder(@NonNull ItemBlockImageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(ImageViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });
        }

        public void bind(BlockEntity block) {
            if (captionWatcher != null) {
                binding.etImageCaption.removeTextChangedListener(captionWatcher);
            }

            String content = block.getContent() != null ? block.getContent() : "";
            String imagePath = content;
            String caption = "";
            if (content.contains("|||")) {
                String[] parts = content.split("\\|\\|\\|", 2);
                imagePath = parts[0];
                caption = parts[1];
            }

            if (!binding.etImageCaption.getText().toString().equals(caption)) {
                int cursor = binding.etImageCaption.getSelectionStart();
                binding.etImageCaption.setText(caption);
                if (binding.etImageCaption.hasFocus()) {
                    binding.etImageCaption.setSelection(Math.min(cursor, binding.etImageCaption.getText().length()));
                }
            }

            Context context = binding.getRoot().getContext();
            Glide.with(context)
                    .load(imagePath)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .into(binding.ivBlockImage);

            final String finalImgPath = imagePath;

            binding.ivBlockImage.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onImageBlockClicked(block, finalImgPath, binding.etImageCaption.getText().toString());
                }
            });

            captionWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (binding.etImageCaption.hasFocus()) {
                        block.setContent(finalImgPath + "|||" + s.toString().trim());
                        if (actionListener != null) actionListener.onBlockContentChanged(block);
                    }
                }
            };
            binding.etImageCaption.addTextChangedListener(captionWatcher);
        }
    }

    // ── 8. File ViewHolder ────────────────────────────────────────────────────
    public class FileViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockFileBinding binding;

        @SuppressLint("ClickableViewAccessibility")
        public FileViewHolder(@NonNull ItemBlockFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && actionListener != null) {
                    actionListener.onStartDrag(FileViewHolder.this);
                }
                return false;
            });

            binding.ivDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    actionListener.onBlockDeleted(blocks.get(pos), pos);
                }
            });

            View.OnClickListener openClick = v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && actionListener != null) {
                    String content = blocks.get(pos).getContent();
                    String filePathOrUrl = parseFilePathOrUrl(content);
                    actionListener.onOpenFile(filePathOrUrl);
                }
            };

            binding.btnOpenFile.setOnClickListener(openClick);
            binding.getRoot().setOnClickListener(openClick);
        }

        public void bind(BlockEntity block) {
            String content = block.getContent() != null ? block.getContent() : "";
            String fileName = "Attached File";
            String fileSize = "Tap to open";

            if (content.contains("|||")) {
                String[] parts = content.split("\\|\\|\\|");
                if (parts.length > 1) fileName = parts[1];
                if (parts.length > 2) fileSize = parts[2];
            } else if (content.contains("|")) {
                String[] parts = content.split("\\|", 2);
                fileName = parts[0];
                fileSize = "Tap to open";
            } else {
                File f = new File(content);
                fileName = f.getName();
            }

            binding.tvFileName.setText(fileName);
            binding.tvFileSize.setText(fileSize);
        }

        private String parseFilePathOrUrl(String content) {
            if (content == null) return "";
            if (content.contains("|||")) {
                return content.split("\\|\\|\\|")[0];
            } else if (content.contains("|")) {
                String[] parts = content.split("\\|", 2);
                return parts.length > 1 ? parts[1] : parts[0];
            }
            return content;
        }
    }
}

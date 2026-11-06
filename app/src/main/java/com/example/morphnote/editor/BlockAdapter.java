package com.example.morphnote.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.morphnote.R;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.databinding.ItemBlockBulletBinding;
import com.example.morphnote.databinding.ItemBlockCalloutBinding;
import com.example.morphnote.databinding.ItemBlockCodeBinding;
import com.example.morphnote.databinding.ItemBlockHeadingBinding;
import com.example.morphnote.databinding.ItemBlockTodoBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlockAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_HEADING = 0;
    public static final int VIEW_TYPE_TODO = 1;
    public static final int VIEW_TYPE_BULLET = 2;
    public static final int VIEW_TYPE_CALLOUT = 3;
    public static final int VIEW_TYPE_CODE = 4;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w-]+(\\.[\\w-]+)+(/[^\\s]*)?");

    public interface OnBlockActionListener {
        void onBlockContentChanged(BlockEntity block);
        void onBlockStatusChanged(BlockEntity block);
        void onBlockDeleted(BlockEntity block, int position);
        void onStartDrag(RecyclerView.ViewHolder viewHolder);
        void onOpenUrl(String url);
    }

    private List<BlockEntity> blocks = new ArrayList<>();
    private final OnBlockActionListener actionListener;

    public BlockAdapter(OnBlockActionListener listener) {
        this.actionListener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setBlocks(List<BlockEntity> blockList) {
        this.blocks = (blockList != null) ? blockList : new ArrayList<>();
        notifyDataSetChanged();
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
                ItemBlockTodoBinding todoBinding = ItemBlockTodoBinding.inflate(inflater, parent, false);
                return new TodoViewHolder(todoBinding);
            case VIEW_TYPE_BULLET:
                ItemBlockBulletBinding bulletBinding = ItemBlockBulletBinding.inflate(inflater, parent, false);
                return new BulletViewHolder(bulletBinding);
            case VIEW_TYPE_CALLOUT:
                ItemBlockCalloutBinding calloutBinding = ItemBlockCalloutBinding.inflate(inflater, parent, false);
                return new CalloutViewHolder(calloutBinding);
            case VIEW_TYPE_CODE:
                ItemBlockCodeBinding codeBinding = ItemBlockCodeBinding.inflate(inflater, parent, false);
                return new CodeViewHolder(codeBinding);
            case VIEW_TYPE_HEADING:
            default:
                ItemBlockHeadingBinding headingBinding = ItemBlockHeadingBinding.inflate(inflater, parent, false);
                return new HeadingViewHolder(headingBinding);
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
        }
    }

    @Override
    public int getItemCount() {
        return blocks.size();
    }

    // 1. Heading ViewHolder
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
                binding.etHeading.removeTextChangedListener(textWatcher);
            }

            binding.etHeading.setText(block.getContent());

            textWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etHeading.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) {
                            actionListener.onBlockContentChanged(block);
                        }
                    }
                }
            };
            binding.etHeading.addTextChangedListener(textWatcher);
        }
    }

    // 2. To-Do ViewHolder
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

            binding.cbTodo.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    BlockEntity block = blocks.get(pos);
                    block.setCompleted(isChecked);
                    applyTodoStyle(isChecked);
                    if (actionListener != null) {
                        actionListener.onBlockStatusChanged(block);
                    }
                }
            });
        }

        public void bind(BlockEntity block) {
            if (textWatcher != null) {
                binding.etTodoContent.removeTextChangedListener(textWatcher);
            }

            binding.cbTodo.setChecked(block.isCompleted());
            binding.etTodoContent.setText(block.getContent());
            applyTodoStyle(block.isCompleted());

            textWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etTodoContent.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) {
                            actionListener.onBlockContentChanged(block);
                        }
                    }
                }
            };
            binding.etTodoContent.addTextChangedListener(textWatcher);
        }

        private void applyTodoStyle(boolean isCompleted) {
            Context context = binding.getRoot().getContext();
            if (isCompleted) {
                binding.etTodoContent.setPaintFlags(binding.etTodoContent.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                binding.etTodoContent.setTextColor(ContextCompat.getColor(context, R.color.todo_completed_text));
            } else {
                binding.etTodoContent.setPaintFlags(binding.etTodoContent.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                binding.etTodoContent.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            }
        }
    }

    // 3. Bullet ViewHolder
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

            binding.etBulletContent.setText(block.getContent());

            textWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etBulletContent.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) {
                            actionListener.onBlockContentChanged(block);
                        }
                    }
                }
            };
            binding.etBulletContent.addTextChangedListener(textWatcher);
        }
    }

    // 4. Callout ViewHolder
    public class CalloutViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockCalloutBinding binding;
        private TextWatcher textWatcher;

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
            if (textWatcher != null) {
                binding.etCalloutContent.removeTextChangedListener(textWatcher);
            }

            binding.etCalloutContent.setText(block.getContent());
            checkAndConfigureUrlButton(block.getContent());

            textWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etCalloutContent.hasFocus()) {
                        block.setContent(s.toString());
                        checkAndConfigureUrlButton(s.toString());
                        if (actionListener != null) {
                            actionListener.onBlockContentChanged(block);
                        }
                    }
                }
            };
            binding.etCalloutContent.addTextChangedListener(textWatcher);
        }

        private void checkAndConfigureUrlButton(String content) {
            if (content == null) {
                binding.ivOpenLink.setVisibility(View.GONE);
                return;
            }

            Matcher matcher = URL_PATTERN.matcher(content);
            if (matcher.find()) {
                String matchedUrl = matcher.group();
                binding.ivOpenLink.setVisibility(View.VISIBLE);
                binding.ivOpenLink.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onOpenUrl(matchedUrl);
                    }
                });
            } else {
                binding.ivOpenLink.setVisibility(View.GONE);
            }
        }
    }

    // 5. Code ViewHolder
    public class CodeViewHolder extends RecyclerView.ViewHolder {
        private final ItemBlockCodeBinding binding;
        private TextWatcher textWatcher;

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
            if (textWatcher != null) {
                binding.etCodeContent.removeTextChangedListener(textWatcher);
            }

            binding.etCodeContent.setText(block.getContent());

            textWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (binding.etCodeContent.hasFocus()) {
                        block.setContent(s.toString());
                        if (actionListener != null) {
                            actionListener.onBlockContentChanged(block);
                        }
                    }
                }
            };
            binding.etCodeContent.addTextChangedListener(textWatcher);
        }
    }
}


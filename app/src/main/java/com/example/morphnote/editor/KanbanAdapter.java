package com.example.morphnote.editor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.databinding.ItemKanbanCardBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class KanbanAdapter extends RecyclerView.Adapter<KanbanAdapter.KanbanCardViewHolder> {

    public interface OnKanbanCardActionListener {
        void onCardAdvance(BlockEntity block);
    }

    private List<BlockEntity> blocks = new ArrayList<>();
    private final String laneType; // "TODO", "IN_PROGRESS", "DONE"
    private final OnKanbanCardActionListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

    public KanbanAdapter(String laneType, OnKanbanCardActionListener listener) {
        this.laneType = laneType;
        this.listener = listener;
    }

    public void setBlocks(List<BlockEntity> newBlocks) {
        this.blocks = (newBlocks != null) ? newBlocks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public KanbanCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemKanbanCardBinding binding = ItemKanbanCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new KanbanCardViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull KanbanCardViewHolder holder, int position) {
        holder.bind(blocks.get(position));
    }

    @Override
    public int getItemCount() {
        return blocks.size();
    }

    public class KanbanCardViewHolder extends RecyclerView.ViewHolder {
        private final ItemKanbanCardBinding binding;

        public KanbanCardViewHolder(@NonNull ItemKanbanCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(BlockEntity block) {
            binding.tvKanbanCardContent.setText(block.getContent());

            if (block.getDueDate() != null && block.getDueDate() > 0) {
                binding.tvKanbanCardDate.setVisibility(View.VISIBLE);
                binding.tvKanbanCardDate.setText(dateFormat.format(new Date(block.getDueDate())));
            } else {
                binding.tvKanbanCardDate.setVisibility(View.GONE);
            }

            if ("DONE".equals(laneType)) {
                binding.btnKanbanMove.setText("Reopen ↩");
            } else if ("IN_PROGRESS".equals(laneType)) {
                binding.btnKanbanMove.setText("Complete ✓");
            } else {
                binding.btnKanbanMove.setText("Start →");
            }

            binding.btnKanbanMove.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCardAdvance(block);
                }
            });
        }
    }
}


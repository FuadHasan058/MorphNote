package com.example.morphnote.editor;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.morphnote.R;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.databinding.ItemFlashcardBinding;

import java.util.ArrayList;
import java.util.List;

public class FlashcardAdapter extends RecyclerView.Adapter<FlashcardAdapter.FlashcardViewHolder> {

    private List<BlockEntity> reviewBlocks = new ArrayList<>();

    public void setBlocks(List<BlockEntity> blocks) {
        this.reviewBlocks = (blocks != null) ? blocks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FlashcardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFlashcardBinding binding = ItemFlashcardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new FlashcardViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull FlashcardViewHolder holder, int position) {
        holder.bind(reviewBlocks.get(position), position, reviewBlocks.size());
    }

    @Override
    public int getItemCount() {
        return reviewBlocks.size();
    }

    public static class FlashcardViewHolder extends RecyclerView.ViewHolder {
        private final ItemFlashcardBinding binding;

        public FlashcardViewHolder(@NonNull ItemFlashcardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(BlockEntity block, int position, int totalCount) {
            binding.tvCardIndex.setText((position + 1) + " / " + totalCount);
            binding.tvCardContent.setText(block.getContent());

            if (BlockEntity.TYPE_CODE.equalsIgnoreCase(block.getBlockType())) {
                binding.tvCardTypeBadge.setText("CODE SNIPPET");
                binding.tvCardTypeBadge.setTextColor(ContextCompat.getColor(
                        binding.getRoot().getContext(), R.color.secondary_variant));
                binding.tvCardContent.setTypeface(Typeface.MONOSPACE);
                binding.tvCardContent.setTextSize(14f);
            } else {
                binding.tvCardTypeBadge.setText("CALLOUT NOTE");
                binding.tvCardTypeBadge.setTextColor(ContextCompat.getColor(
                        binding.getRoot().getContext(), R.color.primary));
                binding.tvCardContent.setTypeface(Typeface.DEFAULT);
                binding.tvCardContent.setTextSize(16f);
            }
        }
    }
}


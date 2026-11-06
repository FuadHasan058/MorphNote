package com.example.morphnote;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.morphnote.database.PageEntity;
import com.example.morphnote.databinding.ItemPageBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageViewHolder> {

    public interface OnPageClickListener {
        void onPageClick(PageEntity page);
        void onPageDelete(PageEntity page);
    }

    private List<PageEntity> pages = new ArrayList<>();
    private final OnPageClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault());

    public PageAdapter(OnPageClickListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setPages(List<PageEntity> pageList) {
        this.pages = (pageList != null) ? pageList : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPageBinding binding = ItemPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new PageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        holder.bind(pages.get(position));
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    public class PageViewHolder extends RecyclerView.ViewHolder {
        private final ItemPageBinding binding;

        public PageViewHolder(@NonNull ItemPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.getRoot().setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onPageClick(pages.get(pos));
                }
            });

            binding.btnDeletePage.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onPageDelete(pages.get(pos));
                }
            });
        }

        public void bind(PageEntity page) {
            String title = page.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = "Untitled Page";
            }
            binding.tvPageTitle.setText(title);

            String formattedDate = dateFormat.format(new Date(page.getUpdatedAt()));
            binding.tvPageUpdated.setText("Edited " + formattedDate);
        }
    }
}


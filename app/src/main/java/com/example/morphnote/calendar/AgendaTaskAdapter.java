package com.example.morphnote.calendar;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.morphnote.R;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.databinding.ItemAgendaTaskBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AgendaTaskAdapter extends RecyclerView.Adapter<AgendaTaskAdapter.AgendaViewHolder> {

    public interface OnTaskActionListener {
        void onTaskToggled(BlockEntity block, boolean isCompleted);
    }

    private List<BlockEntity> tasks = new ArrayList<>();
    private final OnTaskActionListener listener;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());

    public AgendaTaskAdapter(OnTaskActionListener listener) {
        this.listener = listener;
    }

    public void setTasks(List<BlockEntity> newTasks) {
        this.tasks = (newTasks != null) ? newTasks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AgendaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAgendaTaskBinding binding = ItemAgendaTaskBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new AgendaViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AgendaViewHolder holder, int position) {
        holder.bind(tasks.get(position));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    public class AgendaViewHolder extends RecyclerView.ViewHolder {
        private final ItemAgendaTaskBinding binding;

        public AgendaViewHolder(@NonNull ItemAgendaTaskBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(BlockEntity block) {
            Context context = binding.getRoot().getContext();

            binding.tvAgendaTaskContent.setText(block.getContent());
            binding.cbAgendaTask.setOnCheckedChangeListener(null);
            binding.cbAgendaTask.setChecked(block.isCompleted());

            applyStatusStyle(block, context);

            if (block.getDueDate() != null && block.getDueDate() > 0) {
                binding.tvAgendaTaskTime.setVisibility(View.VISIBLE);
                binding.tvAgendaTaskTime.setText(timeFormat.format(new Date(block.getDueDate())));
            } else {
                binding.tvAgendaTaskTime.setVisibility(View.GONE);
            }

            binding.tvAgendaTaskPage.setText(block.getPageId() != null ? "Canvas Task" : "");

            binding.cbAgendaTask.setOnCheckedChangeListener((buttonView, isChecked) -> {
                block.setCompleted(isChecked);
                applyStatusStyle(block, context);
                if (listener != null) {
                    listener.onTaskToggled(block, isChecked);
                }
            });

            binding.chipAgendaTaskStatus.setOnClickListener(v -> {
                String currentStatus = block.getStatus();
                if (BlockEntity.STATUS_TODO.equalsIgnoreCase(currentStatus)) {
                    block.setStatus(BlockEntity.STATUS_IN_PROGRESS);
                } else if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(currentStatus)) {
                    block.setStatus(BlockEntity.STATUS_DONE);
                } else {
                    block.setStatus(BlockEntity.STATUS_TODO);
                }
                binding.cbAgendaTask.setChecked(block.isCompleted());
                applyStatusStyle(block, context);
                if (listener != null) {
                    listener.onTaskToggled(block, block.isCompleted());
                }
            });
        }

        private void applyStatusStyle(BlockEntity block, Context context) {
            String status = block.getStatus();
            if (BlockEntity.STATUS_DONE.equalsIgnoreCase(status) || block.isCompleted()) {
                binding.tvAgendaTaskContent.setPaintFlags(
                        binding.tvAgendaTaskContent.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                binding.tvAgendaTaskContent.setTextColor(
                        ContextCompat.getColor(context, R.color.todo_completed_text));
                binding.chipAgendaTaskStatus.setText("Done ✓");
                binding.chipAgendaTaskStatus.setTextColor(ContextCompat.getColor(context, R.color.primary));
            } else if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(status)) {
                binding.tvAgendaTaskContent.setPaintFlags(
                        binding.tvAgendaTaskContent.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                binding.tvAgendaTaskContent.setTextColor(
                        ContextCompat.getColor(context, R.color.text_primary));
                binding.chipAgendaTaskStatus.setText("In Progress ⏳");
                binding.chipAgendaTaskStatus.setTextColor(ContextCompat.getColor(context, R.color.amber));
            } else {
                binding.tvAgendaTaskContent.setPaintFlags(
                        binding.tvAgendaTaskContent.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                binding.tvAgendaTaskContent.setTextColor(
                        ContextCompat.getColor(context, R.color.text_primary));
                binding.chipAgendaTaskStatus.setText("To Do");
                binding.chipAgendaTaskStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            }
        }
    }
}

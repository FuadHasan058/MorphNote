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
import com.example.morphnote.database.EventEntity;
import com.example.morphnote.databinding.ItemCalendarEventBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class EventAdapter extends RecyclerView.Adapter<EventAdapter.EventViewHolder> {

    public interface OnEventListener {
        void onEventToggled(EventEntity event, boolean isCompleted);
        void onEventDeleted(EventEntity event);
    }

    private List<EventEntity> events = new ArrayList<>();
    private final OnEventListener listener;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());

    public EventAdapter(OnEventListener listener) {
        this.listener = listener;
    }

    public void setEvents(List<EventEntity> newEvents) {
        this.events = (newEvents != null) ? newEvents : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCalendarEventBinding binding = ItemCalendarEventBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new EventViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
        holder.bind(events.get(position));
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    public class EventViewHolder extends RecyclerView.ViewHolder {
        private final ItemCalendarEventBinding binding;

        public EventViewHolder(@NonNull ItemCalendarEventBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(EventEntity event) {
            Context context = binding.getRoot().getContext();

            binding.tvCalendarEventTitle.setText(event.getTitle());
            binding.cbCalendarEvent.setOnCheckedChangeListener(null);
            binding.cbCalendarEvent.setChecked(event.isCompleted());

            if (event.isCompleted()) {
                binding.tvCalendarEventTitle.setPaintFlags(
                        binding.tvCalendarEventTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                binding.tvCalendarEventTitle.setTextColor(
                        ContextCompat.getColor(context, R.color.todo_completed_text));
            } else {
                binding.tvCalendarEventTitle.setPaintFlags(
                        binding.tvCalendarEventTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                binding.tvCalendarEventTitle.setTextColor(
                        ContextCompat.getColor(context, R.color.text_primary));
            }

            if (event.getEventTime() > 0) {
                binding.tvCalendarEventTime.setVisibility(View.VISIBLE);
                binding.tvCalendarEventTime.setText(timeFormat.format(new Date(event.getEventTime())));
            } else {
                binding.tvCalendarEventTime.setVisibility(View.GONE);
            }

            // Priority
            if (event.getPriority() == EventEntity.PRIORITY_URGENT) {
                binding.tvCalendarEventPriority.setText("Urgent");
                binding.tvCalendarEventPriority.setTextColor(ContextCompat.getColor(context, R.color.accent_crimson));
                binding.tvCalendarEventPriority.setVisibility(View.VISIBLE);
            } else if (event.getPriority() == EventEntity.PRIORITY_HIGH) {
                binding.tvCalendarEventPriority.setText("High");
                binding.tvCalendarEventPriority.setTextColor(ContextCompat.getColor(context, R.color.amber));
                binding.tvCalendarEventPriority.setVisibility(View.VISIBLE);
            } else {
                binding.tvCalendarEventPriority.setText("Normal");
                binding.tvCalendarEventPriority.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
                binding.tvCalendarEventPriority.setVisibility(View.VISIBLE);
            }

            // Reminder badge
            binding.tvCalendarEventReminderBadge.setVisibility(event.isHasReminder() ? View.VISIBLE : View.GONE);

            // Checkbox listener
            binding.cbCalendarEvent.setOnCheckedChangeListener((buttonView, isChecked) -> {
                event.setCompleted(isChecked);
                if (isChecked) {
                    binding.tvCalendarEventTitle.setPaintFlags(
                            binding.tvCalendarEventTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                    binding.tvCalendarEventTitle.setTextColor(
                            ContextCompat.getColor(context, R.color.todo_completed_text));
                } else {
                    binding.tvCalendarEventTitle.setPaintFlags(
                            binding.tvCalendarEventTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                    binding.tvCalendarEventTitle.setTextColor(
                            ContextCompat.getColor(context, R.color.text_primary));
                }
                if (listener != null) {
                    listener.onEventToggled(event, isChecked);
                }
            });

            // Delete button listener
            binding.btnDeleteCalendarEvent.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEventDeleted(event);
                }
            });
        }
    }
}


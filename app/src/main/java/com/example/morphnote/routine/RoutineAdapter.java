package com.example.morphnote.routine;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.morphnote.R;
import com.example.morphnote.database.RoutineEntity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying recurring weekly routine items in a RecyclerView.
 */
public class RoutineAdapter extends RecyclerView.Adapter<RoutineAdapter.RoutineViewHolder> {

    public interface OnRoutineListener {
        void onRoutineToggle(RoutineEntity routine, boolean isEnabled);
        void onRoutineDelete(RoutineEntity routine);
    }

    private final List<RoutineEntity> routineList = new ArrayList<>();
    private final OnRoutineListener listener;

    public RoutineAdapter(OnRoutineListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setRoutines(List<RoutineEntity> list) {
        routineList.clear();
        if (list != null) {
            routineList.addAll(list);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        if (position >= 0 && position < routineList.size()) {
            RoutineEntity item = routineList.get(position);
            if (item != null && item.getId() != null) {
                return item.getId().hashCode();
            }
        }
        return position;
    }

    @NonNull
    @Override
    public RoutineViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_routine, parent, false);
        return new RoutineViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RoutineViewHolder holder, int position) {
        RoutineEntity routine = routineList.get(position);
        holder.bind(routine, listener);
    }

    @Override
    public int getItemCount() {
        return routineList.size();
    }

    public static class RoutineViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvTime;
        private final TextView tvTag;
        private final TextView tvTitle;
        private final TextView tvNotice;
        private final MaterialSwitch switchReminder;
        private final MaterialButton btnDelete;

        public RoutineViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_routine_time);
            tvTag = itemView.findViewById(R.id.tv_routine_tag);
            tvTitle = itemView.findViewById(R.id.tv_routine_title);
            tvNotice = itemView.findViewById(R.id.tv_routine_notice);
            switchReminder = itemView.findViewById(R.id.switch_routine_reminder);
            btnDelete = itemView.findViewById(R.id.btn_delete_routine);
        }

        public void bind(RoutineEntity routine, OnRoutineListener listener) {
            tvTime.setText(routine.getFormattedTime());
            tvTitle.setText(routine.getTitle());

            String tag = (routine.getTag() != null && !routine.getTag().isEmpty())
                    ? routine.getTag()
                    : "Class";
            tvTag.setText(tag);
            applyTagBadgeStyle(tvTag, tag);

            // Clear listeners before assigning state to prevent unwanted callbacks
            switchReminder.setOnCheckedChangeListener(null);
            switchReminder.setOnClickListener(null);

            // Set state directly
            switchReminder.setChecked(routine.isEnabled());
            updateNoticeText(tvNotice, routine.isEnabled());

            // Using OnClickListener ensures ONLY genuine user interaction triggers the toggle callback,
            // never programmatic binding or view recycling during scrolling / notifyDataSetChanged
            switchReminder.setOnClickListener(v -> {
                boolean isChecked = switchReminder.isChecked();
                routine.setEnabled(isChecked);
                updateNoticeText(tvNotice, isChecked);
                if (listener != null) {
                    listener.onRoutineToggle(routine, isChecked);
                }
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRoutineDelete(routine);
                }
            });
        }

        private static void updateNoticeText(TextView tvNotice, boolean isEnabled) {
            if (isEnabled) {
                tvNotice.setText("🔔 30m advance alert ON");
                tvNotice.setAlpha(1.0f);
            } else {
                tvNotice.setText("🔕 Reminder OFF");
                tvNotice.setAlpha(0.6f);
            }
        }

        private void applyTagBadgeStyle(TextView tv, String tag) {
            int textColor;
            int bgColor;

            if ("Fitness".equalsIgnoreCase(tag)) {
                textColor = Color.parseColor("#DC2626"); // Red
                bgColor   = Color.parseColor("#33EF4444");
            } else if ("Work".equalsIgnoreCase(tag)) {
                textColor = Color.parseColor("#9333EA"); // Purple
                bgColor   = Color.parseColor("#33A855F7");
            } else if ("Study".equalsIgnoreCase(tag)) {
                textColor = Color.parseColor("#059669"); // Emerald
                bgColor   = Color.parseColor("#3310B981");
            } else if ("Personal".equalsIgnoreCase(tag)) {
                textColor = Color.parseColor("#D97706"); // Amber
                bgColor   = Color.parseColor("#33F59E0B");
            } else { // Class / Default
                textColor = Color.parseColor("#2563EB"); // Blue
                bgColor   = Color.parseColor("#333B82F6");
            }

            tv.setTextColor(textColor);

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(16f);
            shape.setColor(bgColor);
            tv.setBackground(shape);
        }
    }
}

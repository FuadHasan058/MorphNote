package com.example.morphnote.calendar;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.EventEntity;
import com.example.morphnote.databinding.FragmentCalendarBinding;
import com.example.morphnote.databinding.ItemCalendarDayBinding;
import com.example.morphnote.reminder.ReminderScheduler;
import com.example.morphnote.service.FocusModeService;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CalendarFragment extends Fragment implements
        AgendaTaskAdapter.OnTaskActionListener,
        EventAdapter.OnEventListener {

    private FragmentCalendarBinding binding;
    private AgendaTaskAdapter taskAdapter;
    private EventAdapter eventAdapter;
    private Calendar currentMonthCal = Calendar.getInstance();
    private Calendar selectedDateCal = Calendar.getInstance();
    private final SimpleDateFormat monthYearFormat = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
    private final SimpleDateFormat labelDateFormat = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
    private final SimpleDateFormat timePickerFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private Set<Integer> daysWithItems = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCalendarBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Standalone Events List
        eventAdapter = new EventAdapter(this);
        binding.rvDayEvents.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvDayEvents.setAdapter(eventAdapter);
        binding.rvDayEvents.setNestedScrollingEnabled(false);

        // Canvas Tasks List
        taskAdapter = new AgendaTaskAdapter(this);
        binding.rvDayTasks.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvDayTasks.setAdapter(taskAdapter);
        binding.rvDayTasks.setNestedScrollingEnabled(false);

        binding.btnPrevMonth.setOnClickListener(v -> {
            currentMonthCal.add(Calendar.MONTH, -1);
            renderCalendarMonth();
        });

        binding.btnNextMonth.setOnClickListener(v -> {
            currentMonthCal.add(Calendar.MONTH, 1);
            renderCalendarMonth();
        });

        // Single "+ Add Event" button in the date header
        binding.btnAddEvent.setOnClickListener(v -> showAddEventDialog());

        renderCalendarMonth();
        loadEventsAndTasksForSelectedDate();
        syncEventsFromCloud();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderCalendarMonth();
        loadEventsAndTasksForSelectedDate();
        syncEventsFromCloud();
    }

    private void showAddEventDialog() {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_event, null);
        EditText etTitle = dialogView.findViewById(R.id.et_event_title);
        MaterialButton btnPickTime = dialogView.findViewById(R.id.btn_pick_event_time);
        ChipGroup chipGroupPriority = dialogView.findViewById(R.id.chip_group_event_priority);
        MaterialCheckBox cbReminder = dialogView.findViewById(R.id.cb_event_reminder);

        final Calendar eventCal = (Calendar) selectedDateCal.clone();
        eventCal.set(Calendar.HOUR_OF_DAY, 10);
        eventCal.set(Calendar.MINUTE, 0);
        eventCal.set(Calendar.SECOND, 0);

        btnPickTime.setText("Time: " + timePickerFormat.format(eventCal.getTime()));

        btnPickTime.setOnClickListener(v -> {
            MaterialTimePicker timePicker = new MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(eventCal.get(Calendar.HOUR_OF_DAY))
                    .setMinute(eventCal.get(Calendar.MINUTE))
                    .setTitleText("Select Event Time")
                    .build();

            timePicker.addOnPositiveButtonClickListener(tp -> {
                eventCal.set(Calendar.HOUR_OF_DAY, timePicker.getHour());
                eventCal.set(Calendar.MINUTE, timePicker.getMinute());
                btnPickTime.setText("Time: " + timePickerFormat.format(eventCal.getTime()));
            });

            timePicker.show(getParentFragmentManager(), "EVENT_TIME_PICKER");
        });

        new AlertDialog.Builder(requireContext())
                .setTitle("Add Event: " + new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(selectedDateCal.getTime()))
                .setView(dialogView)
                .setPositiveButton("Create Event", (dialog, which) -> {
                    String title = etTitle.getText().toString().trim();
                    if (title.isEmpty()) {
                        title = "Calendar Event";
                    }

                    int priority = EventEntity.PRIORITY_NORMAL;
                    int checkedId = chipGroupPriority.getCheckedChipId();
                    if (checkedId == R.id.chip_priority_high) {
                        priority = EventEntity.PRIORITY_HIGH;
                    } else if (checkedId == R.id.chip_priority_urgent) {
                        priority = EventEntity.PRIORITY_URGENT;
                    }

                    boolean hasReminder = cbReminder.isChecked();
                    createCalendarEvent(title, eventCal.getTimeInMillis(), priority, hasReminder);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Standalone Calendar Event Creation:
     * Managed strictly in the Calendar section, never added to canvases.
     */
    private void createCalendarEvent(String title, long eventTimeMs, int priority, boolean hasReminder) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || getContext() == null) return;
        final String userId = user.getUid();
        final String eventId = UUID.randomUUID().toString();

        EventEntity newEvent = new EventEntity(
                eventId,
                userId,
                title,
                eventTimeMs,
                priority,
                hasReminder,
                false
        );

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            db.eventDao().insert(newEvent);

            // Sync to Firestore & Realtime Database
            syncEventToCloud(newEvent);

            // Schedule exact alarm if reminder requested
            if (hasReminder && getContext() != null) {
                ReminderScheduler.scheduleReminder(
                        requireContext().getApplicationContext(),
                        eventId,
                        title,
                        eventTimeMs
                );
            }

            // Dispatch notification update
            if (getContext() != null) {
                Intent updateIntent = new Intent(requireContext().getApplicationContext(), FocusModeService.class);
                updateIntent.setAction(FocusModeService.ACTION_UPDATE_NOTIFICATION);
                ContextCompat.startForegroundService(requireContext().getApplicationContext(), updateIntent);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Event scheduled for " +
                            new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date(eventTimeMs)),
                            Toast.LENGTH_SHORT).show();
                    renderCalendarMonth();
                    loadEventsAndTasksForSelectedDate();
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).updateProgressRing();
                    }
                });
            }
        });
    }

    private void syncEventToCloud(EventEntity event) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", event.getId());
        map.put("userId", event.getUserId());
        map.put("title", event.getTitle());
        map.put("eventTime", event.getEventTime());
        map.put("priority", event.getPriority());
        map.put("hasReminder", event.isHasReminder());
        map.put("isCompleted", event.isCompleted());

        FirebaseFirestore fs = FirebaseFirestore.getInstance();
        fs.collection("users").document(event.getUserId())
                .collection("events").document(event.getId()).set(map);
        fs.collection("events").document(event.getId()).set(map);

        FirebaseDatabase.getInstance().getReference("events")
                .child(event.getUserId()).child(event.getId()).setValue(map);
    }

    private void syncEventsFromCloud() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || getContext() == null) return;
        final String userId = user.getUid();

        FirebaseFirestore.getInstance().collection("users").document(userId)
                .collection("events").get()
                .addOnSuccessListener(snapshots -> {
                    if (snapshots == null || snapshots.isEmpty()) return;
                    AppDatabase.getDatabaseExecutor().execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(requireContext());
                        for (DocumentSnapshot doc : snapshots.getDocuments()) {
                            try {
                                String id = doc.getString("id");
                                if (id == null) id = doc.getId();
                                String title = doc.getString("title");
                                if (title == null) title = "Event";
                                Long eventTime = doc.getLong("eventTime");
                                if (eventTime == null) eventTime = System.currentTimeMillis();
                                Long priorityL = doc.getLong("priority");
                                int priority = (priorityL != null) ? priorityL.intValue() : EventEntity.PRIORITY_NORMAL;
                                Boolean hasReminder = doc.getBoolean("hasReminder");
                                Boolean isCompleted = doc.getBoolean("isCompleted");

                                EventEntity entity = new EventEntity(
                                        id,
                                        userId,
                                        title,
                                        eventTime,
                                        priority,
                                        (hasReminder != null && hasReminder),
                                        (isCompleted != null && isCompleted)
                                );
                                db.eventDao().insert(entity);
                            } catch (Exception ignored) {}
                        }
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                renderCalendarMonth();
                                loadEventsAndTasksForSelectedDate();
                                if (getActivity() instanceof MainActivity) {
                                    ((MainActivity) getActivity()).updateProgressRing();
                                }
                            });
                        }
                    });
                });
    }

    private void renderCalendarMonth() {
        if (binding == null || getContext() == null) return;

        binding.tvMonthYear.setText(monthYearFormat.format(currentMonthCal.getTime()));

        Calendar cal = (Calendar) currentMonthCal.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK); // 1 = Sunday
        int maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long monthStart = cal.getTimeInMillis();

        cal.set(Calendar.DAY_OF_MONTH, maxDaysInMonth);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        long monthEnd = cal.getTimeInMillis();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        final String userId = (user != null) ? user.getUid() : "";

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            List<BlockEntity> monthBlocks = db.blockDao().getBlocksByDateRange(monthStart, monthEnd);
            List<EventEntity> monthEvents = db.eventDao().getEventsForRange(userId, monthStart, monthEnd);

            Set<Integer> itemDays = new HashSet<>();
            Calendar tempCal = Calendar.getInstance();

            for (BlockEntity b : monthBlocks) {
                if (b.getDueDate() != null) {
                    tempCal.setTimeInMillis(b.getDueDate());
                    if (tempCal.get(Calendar.MONTH) == currentMonthCal.get(Calendar.MONTH)
                            && tempCal.get(Calendar.YEAR) == currentMonthCal.get(Calendar.YEAR)) {
                        itemDays.add(tempCal.get(Calendar.DAY_OF_MONTH));
                    }
                }
            }

            for (EventEntity e : monthEvents) {
                if (e.getEventTime() > 0) {
                    tempCal.setTimeInMillis(e.getEventTime());
                    if (tempCal.get(Calendar.MONTH) == currentMonthCal.get(Calendar.MONTH)
                            && tempCal.get(Calendar.YEAR) == currentMonthCal.get(Calendar.YEAR)) {
                        itemDays.add(tempCal.get(Calendar.DAY_OF_MONTH));
                    }
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    daysWithItems = itemDays;
                    populateCalendarGrid(firstDayOfWeek, maxDaysInMonth);
                });
            }
        });
    }

    private void populateCalendarGrid(int firstDayOfWeek, int maxDaysInMonth) {
        if (binding == null || getContext() == null) return;
        binding.gridCalendarDays.removeAllViews();

        int emptyOffset = firstDayOfWeek - 1; // 0 for Sunday
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        // Empty cells before day 1
        for (int i = 0; i < emptyOffset; i++) {
            ItemCalendarDayBinding dayBinding = ItemCalendarDayBinding.inflate(
                    inflater, binding.gridCalendarDays, false);
            dayBinding.tvDayNumber.setText("");
            dayBinding.dotIndicator.setVisibility(View.INVISIBLE);
            dayBinding.getRoot().setClickable(false);
            binding.gridCalendarDays.addView(dayBinding.getRoot());
        }

        Calendar todayCal = Calendar.getInstance();

        // Days 1 through maxDaysInMonth
        for (int day = 1; day <= maxDaysInMonth; day++) {
            final int dayNum = day;
            ItemCalendarDayBinding dayBinding = ItemCalendarDayBinding.inflate(
                    inflater, binding.gridCalendarDays, false);

            dayBinding.tvDayNumber.setText(String.valueOf(dayNum));

            boolean isToday = (todayCal.get(Calendar.YEAR) == currentMonthCal.get(Calendar.YEAR)
                    && todayCal.get(Calendar.MONTH) == currentMonthCal.get(Calendar.MONTH)
                    && todayCal.get(Calendar.DAY_OF_MONTH) == dayNum);

            boolean isSelected = (selectedDateCal.get(Calendar.YEAR) == currentMonthCal.get(Calendar.YEAR)
                    && selectedDateCal.get(Calendar.MONTH) == currentMonthCal.get(Calendar.MONTH)
                    && selectedDateCal.get(Calendar.DAY_OF_MONTH) == dayNum);

            if (isSelected) {
                dayBinding.tvDayNumber.setBackgroundResource(R.drawable.bg_chip_upcoming);
                dayBinding.tvDayNumber.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary));
            } else if (isToday) {
                dayBinding.tvDayNumber.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary));
            } else {
                dayBinding.tvDayNumber.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            }

            if (daysWithItems.contains(dayNum)) {
                dayBinding.dotIndicator.setVisibility(View.VISIBLE);
            } else {
                dayBinding.dotIndicator.setVisibility(View.INVISIBLE);
            }

            dayBinding.getRoot().setOnClickListener(v -> {
                selectedDateCal.set(Calendar.YEAR, currentMonthCal.get(Calendar.YEAR));
                selectedDateCal.set(Calendar.MONTH, currentMonthCal.get(Calendar.MONTH));
                selectedDateCal.set(Calendar.DAY_OF_MONTH, dayNum);
                populateCalendarGrid(firstDayOfWeek, maxDaysInMonth);
                loadEventsAndTasksForSelectedDate();
            });

            binding.gridCalendarDays.addView(dayBinding.getRoot());
        }
    }

    private void loadEventsAndTasksForSelectedDate() {
        if (binding == null || getContext() == null) return;

        binding.tvSelectedDateLabel.setText(getString(R.string.tasks_for_date,
                labelDateFormat.format(selectedDateCal.getTime())));

        Calendar dayStartCal = (Calendar) selectedDateCal.clone();
        dayStartCal.set(Calendar.HOUR_OF_DAY, 0);
        dayStartCal.set(Calendar.MINUTE, 0);
        dayStartCal.set(Calendar.SECOND, 0);
        dayStartCal.set(Calendar.MILLISECOND, 0);
        long dayStart = dayStartCal.getTimeInMillis();
        long dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        final String userId = (user != null) ? user.getUid() : "";

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            List<EventEntity> dayEvents = db.eventDao().getEventsForDay(userId, dayStart, dayEnd);
            List<BlockEntity> dayBlocks = db.blockDao().getBlocksByDueDate(dayStart, dayEnd);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Update Events section
                    if (eventAdapter != null) {
                        eventAdapter.setEvents(dayEvents);
                    }
                    if (binding != null) {
                        binding.tvNoEvents.setVisibility(dayEvents.isEmpty() ? View.VISIBLE : View.GONE);
                        binding.tvEventsHeader.setText("📅 Events (" + dayEvents.size() + ")");

                        // Update Canvas Tasks section
                        if (taskAdapter != null) {
                            taskAdapter.setTasks(dayBlocks);
                        }
                        binding.tvNoTasks.setVisibility(dayBlocks.isEmpty() ? View.VISIBLE : View.GONE);
                        binding.tvTasksHeader.setText("✓ Canvas Tasks (" + dayBlocks.size() + ")");
                    }
                });
            }
        });
    }

    @Override
    public void onEventToggled(EventEntity event, boolean isCompleted) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            db.eventDao().update(event);
            syncEventToCloud(event);

            // Notification update
            Context context = requireContext().getApplicationContext();
            Intent updateIntent = new Intent(context, FocusModeService.class);
            updateIntent.setAction(FocusModeService.ACTION_UPDATE_NOTIFICATION);
            ContextCompat.startForegroundService(context, updateIntent);

            if (getActivity() instanceof MainActivity) {
                getActivity().runOnUiThread(() -> ((MainActivity) getActivity()).updateProgressRing());
            }
        });
    }

    @Override
    public void onEventDeleted(EventEntity event) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            db.eventDao().delete(event);

            if (event.isHasReminder() && getContext() != null) {
                ReminderScheduler.cancelReminder(requireContext().getApplicationContext(), event.getId());
            }

            // Delete from Cloud
            FirebaseFirestore.getInstance().collection("users").document(event.getUserId())
                    .collection("events").document(event.getId()).delete();
            FirebaseFirestore.getInstance().collection("events").document(event.getId()).delete();
            FirebaseDatabase.getInstance().getReference("events")
                    .child(event.getUserId()).child(event.getId()).removeValue();

            // Notification update
            Context context = requireContext().getApplicationContext();
            Intent updateIntent = new Intent(context, FocusModeService.class);
            updateIntent.setAction(FocusModeService.ACTION_UPDATE_NOTIFICATION);
            ContextCompat.startForegroundService(context, updateIntent);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Event deleted", Toast.LENGTH_SHORT).show();
                    renderCalendarMonth();
                    loadEventsAndTasksForSelectedDate();
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).updateProgressRing();
                    }
                });
            }
        });
    }

    @Override
    public void onTaskToggled(BlockEntity block, boolean isCompleted) {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            db.blockDao().update(block);

            // Sync status to Firebase
            FirebaseDatabase.getInstance().getReference("pages")
                    .child(block.getPageId()).child("blocks").child(block.getId())
                    .child("completed").setValue(isCompleted);

            Map<String, Object> updateMap = new HashMap<>();
            updateMap.put("isCompleted", isCompleted);
            updateMap.put("status", block.getStatus());
            FirebaseFirestore.getInstance().collection("pages")
                    .document(block.getPageId()).collection("blocks")
                    .document(block.getId()).update(updateMap);

            Context context = requireContext().getApplicationContext();
            Intent updateIntent = new Intent(context, FocusModeService.class);
            updateIntent.setAction(FocusModeService.ACTION_UPDATE_NOTIFICATION);
            ContextCompat.startForegroundService(context, updateIntent);

            if (getActivity() instanceof MainActivity) {
                getActivity().runOnUiThread(() -> ((MainActivity) getActivity()).updateProgressRing());
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

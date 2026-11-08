package com.example.morphnote.routine;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.RoutineEntity;
import com.example.morphnote.databinding.DialogAddRoutineBinding;
import com.example.morphnote.databinding.FragmentRoutineBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fragment presenting the weekly recurring Routine / Timetable section.
 *
 * <p>Supports day-by-day navigation, landscape multi-column grid, bi-directional
 * real-time Firebase syncing, and exact 30-minute advance reminder alarms.</p>
 */
public class RoutineFragment extends Fragment implements RoutineAdapter.OnRoutineListener {

    private static final String TAG = "RoutineFragment";

    private FragmentRoutineBinding binding;
    private RoutineAdapter adapter;
    private int selectedDayOfWeek = Calendar.MONDAY;
    private ValueEventListener routineSyncListener;
    private DatabaseReference routinesRef;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRoutineBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new RoutineAdapter(this);
        binding.rvRoutines.setAdapter(adapter);

        updateLayoutManager(getResources().getConfiguration().orientation);

        // Auto-select current day of week on initial launch
        int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        selectedDayOfWeek = today;
        selectDayChip(today);

        setupDayFilterListener();

        binding.btnAddRoutine.setOnClickListener(v -> showAddRoutineDialog());

        loadRoutinesForSelectedDay();
        attachCloudSyncListener();
        syncRoutinesFromCloud();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadRoutinesForSelectedDay();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayoutManager(newConfig.orientation);
    }

    private void updateLayoutManager(int orientation) {
        if (binding == null || getContext() == null) return;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.rvRoutines.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        } else {
            binding.rvRoutines.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
    }

    // ── Day Filter Strip ──────────────────────────────────────────────────────

    private void selectDayChip(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:    binding.chipFilterMon.setChecked(true); break;
            case Calendar.TUESDAY:   binding.chipFilterTue.setChecked(true); break;
            case Calendar.WEDNESDAY: binding.chipFilterWed.setChecked(true); break;
            case Calendar.THURSDAY:  binding.chipFilterThu.setChecked(true); break;
            case Calendar.FRIDAY:    binding.chipFilterFri.setChecked(true); break;
            case Calendar.SATURDAY:  binding.chipFilterSat.setChecked(true); break;
            case Calendar.SUNDAY:    binding.chipFilterSun.setChecked(true); break;
        }
    }

    private void setupDayFilterListener() {
        binding.chipGroupDaysFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;

            int id = checkedIds.get(0);
            if (id == R.id.chip_filter_mon) {
                selectedDayOfWeek = Calendar.MONDAY;
            } else if (id == R.id.chip_filter_tue) {
                selectedDayOfWeek = Calendar.TUESDAY;
            } else if (id == R.id.chip_filter_wed) {
                selectedDayOfWeek = Calendar.WEDNESDAY;
            } else if (id == R.id.chip_filter_thu) {
                selectedDayOfWeek = Calendar.THURSDAY;
            } else if (id == R.id.chip_filter_fri) {
                selectedDayOfWeek = Calendar.FRIDAY;
            } else if (id == R.id.chip_filter_sat) {
                selectedDayOfWeek = Calendar.SATURDAY;
            } else if (id == R.id.chip_filter_sun) {
                selectedDayOfWeek = Calendar.SUNDAY;
            }

            loadRoutinesForSelectedDay();
        });
    }

    // ── Local Data Loading ────────────────────────────────────────────────────

    public void loadRoutinesForSelectedDay() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || getContext() == null || binding == null) return;
        final String userId = user.getUid();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext().getApplicationContext());
            List<RoutineEntity> routines = db.routineDao().getRoutinesByDay(userId, selectedDayOfWeek);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (binding == null) return;

                    String dayName = RoutineEntity.getDayName(selectedDayOfWeek);
                    binding.tvSelectedDayTitle.setText(dayName + " Schedule");

                    int count = routines.size();
                    binding.tvRoutineCount.setText(count == 1
                            ? "1 routine scheduled"
                            : count + " routines scheduled");

                    adapter.setRoutines(routines);

                    if (routines.isEmpty()) {
                        binding.layoutRoutineEmpty.setVisibility(View.VISIBLE);
                        binding.rvRoutines.setVisibility(View.GONE);
                    } else {
                        binding.layoutRoutineEmpty.setVisibility(View.GONE);
                        binding.rvRoutines.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    // ── Add Routine Dialog ────────────────────────────────────────────────────

    private void showAddRoutineDialog() {
        if (getContext() == null) return;

        DialogAddRoutineBinding dialogBinding = DialogAddRoutineBinding.inflate(getLayoutInflater());

        // Pre-select current filter day
        switch (selectedDayOfWeek) {
            case Calendar.MONDAY:    dialogBinding.chipDayMon.setChecked(true); break;
            case Calendar.TUESDAY:   dialogBinding.chipDayTue.setChecked(true); break;
            case Calendar.WEDNESDAY: dialogBinding.chipDayWed.setChecked(true); break;
            case Calendar.THURSDAY:  dialogBinding.chipDayThu.setChecked(true); break;
            case Calendar.FRIDAY:    dialogBinding.chipDayFri.setChecked(true); break;
            case Calendar.SATURDAY:  dialogBinding.chipDaySat.setChecked(true); break;
            case Calendar.SUNDAY:    dialogBinding.chipDaySun.setChecked(true); break;
        }

        final int[] selectedTime = new int[]{9, 30}; // default 09:30 AM
        dialogBinding.btnPickRoutineTime.setText("Start Time: 09:30 AM");

        dialogBinding.btnPickRoutineTime.setOnClickListener(v -> {
            MaterialTimePicker picker = new MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(selectedTime[0])
                    .setMinute(selectedTime[1])
                    .setTitleText("Select Routine Start Time")
                    .build();

            picker.addOnPositiveButtonClickListener(dialog -> {
                selectedTime[0] = picker.getHour();
                selectedTime[1] = picker.getMinute();

                int displayHour = selectedTime[0] % 12;
                if (displayHour == 0) displayHour = 12;
                String amPm = (selectedTime[0] < 12) ? "AM" : "PM";
                String timeStr = String.format(Locale.getDefault(), "Start Time: %02d:%02d %s",
                        displayHour, selectedTime[1], amPm);
                dialogBinding.btnPickRoutineTime.setText(timeStr);
            });

            picker.show(getParentFragmentManager(), "ROUTINE_TIME_PICKER");
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .setPositiveButton("Save Routine", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String title = (dialogBinding.etRoutineTitle.getText() != null)
                        ? dialogBinding.etRoutineTitle.getText().toString().trim()
                        : "";

                if (TextUtils.isEmpty(title)) {
                    dialogBinding.etRoutineTitle.setError("Please enter a routine title");
                    return;
                }

                // Determine selected days
                List<Integer> selectedDays = new ArrayList<>();
                if (dialogBinding.chipDayMon.isChecked()) selectedDays.add(Calendar.MONDAY);
                if (dialogBinding.chipDayTue.isChecked()) selectedDays.add(Calendar.TUESDAY);
                if (dialogBinding.chipDayWed.isChecked()) selectedDays.add(Calendar.WEDNESDAY);
                if (dialogBinding.chipDayThu.isChecked()) selectedDays.add(Calendar.THURSDAY);
                if (dialogBinding.chipDayFri.isChecked()) selectedDays.add(Calendar.FRIDAY);
                if (dialogBinding.chipDaySat.isChecked()) selectedDays.add(Calendar.SATURDAY);
                if (dialogBinding.chipDaySun.isChecked()) selectedDays.add(Calendar.SUNDAY);

                if (selectedDays.isEmpty()) {
                    Toast.makeText(getContext(), "Please select at least one day", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Determine category tag
                String tag = "Class";
                int tagChipId = dialogBinding.chipGroupTags.getCheckedChipId();
                if (tagChipId == R.id.chip_tag_fitness) {
                    tag = "Fitness";
                } else if (tagChipId == R.id.chip_tag_work) {
                    tag = "Work";
                } else if (tagChipId == R.id.chip_tag_study) {
                    tag = "Study";
                } else if (tagChipId == R.id.chip_tag_personal) {
                    tag = "Personal";
                }

                boolean isReminderEnabled = dialogBinding.switchEnableReminder.isChecked();
                saveNewRoutines(title, selectedDays, selectedTime[0], selectedTime[1], tag, isReminderEnabled);
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void saveNewRoutines(String title, List<Integer> days, int hour, int minute,
                                 String tag, boolean isReminderEnabled) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || getContext() == null) return;
        final String userId = user.getUid();
        final Context appContext = requireContext().getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(appContext);

            for (int day : days) {
                String routineId = UUID.randomUUID().toString();
                RoutineEntity routine = new RoutineEntity(
                        routineId,
                        userId,
                        title,
                        day,
                        hour,
                        minute,
                        tag,
                        isReminderEnabled,
                        System.currentTimeMillis()
                );

                db.routineDao().insert(routine);

                // Sync to Firebase (both Realtime Database and Firestore)
                syncRoutineToFirebase(routine);

                // Schedule 30-min advance alarm if enabled
                if (isReminderEnabled) {
                    RoutineScheduler.scheduleRoutineReminder(appContext, routine);
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Routine saved", Toast.LENGTH_SHORT).show();
                    loadRoutinesForSelectedDay();
                });
            }
        });
    }

    // ── Adapter Callbacks ─────────────────────────────────────────────────────

    @Override
    public void onRoutineToggle(RoutineEntity routine, boolean isEnabled) {
        routine.setEnabled(isEnabled);
        final Context appContext = requireContext().getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(appContext);
            db.routineDao().update(routine);

            // 1. Atomically update only this routine's enabled flag in Realtime Database
            if (routine.getUserId() != null && !routine.getUserId().isEmpty()) {
                Map<String, Object> updateMap = new HashMap<>();
                updateMap.put("isEnabled", isEnabled);
                updateMap.put("enabled", isEnabled);

                FirebaseDatabase.getInstance().getReference("users")
                        .child(routine.getUserId())
                        .child("routines")
                        .child(routine.getId())
                        .updateChildren(updateMap);

                // 2. Also merge update in Firestore for cross-device sync
                FirebaseFirestore.getInstance().collection("users")
                        .document(routine.getUserId())
                        .collection("routines")
                        .document(routine.getId())
                        .set(updateMap, SetOptions.merge());
            }

            // 3. Update local exact alarm
            if (isEnabled) {
                RoutineScheduler.scheduleRoutineReminder(appContext, routine);
            } else {
                RoutineScheduler.cancelRoutineReminder(appContext, routine.getId());
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    String msg = isEnabled ? "30-min advance alert enabled" : "Alert disabled";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onRoutineDelete(RoutineEntity routine) {
        final Context appContext = requireContext().getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(appContext);
            db.routineDao().delete(routine);

            // Delete from Firebase Realtime Database
            if (routine.getUserId() != null && !routine.getUserId().isEmpty()) {
                FirebaseDatabase.getInstance().getReference("users")
                        .child(routine.getUserId())
                        .child("routines")
                        .child(routine.getId())
                        .removeValue();

                // Delete from Firebase Firestore
                FirebaseFirestore.getInstance().collection("users")
                        .document(routine.getUserId())
                        .collection("routines")
                        .document(routine.getId())
                        .delete();
            }

            RoutineScheduler.cancelRoutineReminder(appContext, routine.getId());

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Routine deleted", Toast.LENGTH_SHORT).show();
                    loadRoutinesForSelectedDay();
                });
            }
        });
    }

    // ── Bi-Directional Firebase Realtime Database Sync ────────────────────────

    private void syncRoutineToFirebase(RoutineEntity routine) {
        if (routine == null || routine.getUserId() == null || routine.getUserId().isEmpty()) return;

        Map<String, Object> map = new HashMap<>();
        map.put("id", routine.getId());
        map.put("userId", routine.getUserId());
        map.put("title", routine.getTitle());
        map.put("dayOfWeek", routine.getDayOfWeek());
        map.put("hour", routine.getHour());
        map.put("minute", routine.getMinute());
        map.put("tag", routine.getTag());
        map.put("isEnabled", routine.isEnabled());
        map.put("enabled", routine.isEnabled());
        map.put("createdAt", routine.getCreatedAt());

        FirebaseDatabase.getInstance().getReference("users")
                .child(routine.getUserId())
                .child("routines")
                .child(routine.getId())
                .setValue(map);

        FirebaseFirestore.getInstance().collection("users")
                .document(routine.getUserId())
                .collection("routines")
                .document(routine.getId())
                .set(map);
    }

    private void attachCloudSyncListener() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || getContext() == null) return;
        final String userId = user.getUid();
        final Context appContext = requireContext().getApplicationContext();

        routinesRef = FirebaseDatabase.getInstance().getReference("users")
                .child(userId).child("routines");

        routineSyncListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                AppDatabase.getDatabaseExecutor().execute(() -> {
                    try {
                        AppDatabase db = AppDatabase.getInstance(appContext);
                        List<RoutineEntity> remoteList = new ArrayList<>();
                        Set<String> remoteIds = new HashSet<>();

                        for (DataSnapshot child : snapshot.getChildren()) {
                            RoutineEntity r = child.getValue(RoutineEntity.class);
                            if (r == null) {
                                r = new RoutineEntity();
                            }

                            // Defensive extraction from child snapshot to guarantee 100% field accuracy
                            if (child.hasChild("id") && child.child("id").getValue(String.class) != null) {
                                r.setId(child.child("id").getValue(String.class));
                            }
                            if (child.hasChild("userId") && child.child("userId").getValue(String.class) != null) {
                                r.setUserId(child.child("userId").getValue(String.class));
                            }
                            if (child.hasChild("title") && child.child("title").getValue(String.class) != null) {
                                r.setTitle(child.child("title").getValue(String.class));
                            }
                            if (child.hasChild("dayOfWeek")) {
                                Long dow = child.child("dayOfWeek").getValue(Long.class);
                                if (dow != null) r.setDayOfWeek(dow.intValue());
                            }
                            if (child.hasChild("hour")) {
                                Long h = child.child("hour").getValue(Long.class);
                                if (h != null) r.setHour(h.intValue());
                            }
                            if (child.hasChild("minute")) {
                                Long m = child.child("minute").getValue(Long.class);
                                if (m != null) r.setMinute(m.intValue());
                            }
                            if (child.hasChild("tag") && child.child("tag").getValue(String.class) != null) {
                                r.setTag(child.child("tag").getValue(String.class));
                            }

                            // Correctly extract the boolean enabled flag regardless of key naming
                            if (child.hasChild("isEnabled")) {
                                Boolean en = child.child("isEnabled").getValue(Boolean.class);
                                if (en != null) r.setEnabled(en);
                            } else if (child.hasChild("enabled")) {
                                Boolean en = child.child("enabled").getValue(Boolean.class);
                                if (en != null) r.setEnabled(en);
                            }

                            if (child.hasChild("createdAt")) {
                                Long ca = child.child("createdAt").getValue(Long.class);
                                if (ca != null) r.setCreatedAt(ca);
                            }

                            if (r.getId() != null && !r.getId().isEmpty()) {
                                remoteList.add(r);
                                remoteIds.add(r.getId());
                            }
                        }

                        // Reconcile deletions with a 3-second grace period for newly created items
                        long now = System.currentTimeMillis();
                        List<RoutineEntity> localList = db.routineDao().getAllRoutines(userId);
                        for (RoutineEntity local : localList) {
                            if (!remoteIds.contains(local.getId()) && (now - local.getCreatedAt() > 3000L)) {
                                db.routineDao().deleteById(local.getId());
                                RoutineScheduler.cancelRoutineReminder(appContext, local.getId());
                            }
                        }

                        // Insert or update remote entries in Room
                        if (!remoteList.isEmpty()) {
                            db.routineDao().insertAll(remoteList);
                            for (RoutineEntity r : remoteList) {
                                if (r.isEnabled()) {
                                    RoutineScheduler.scheduleRoutineReminder(appContext, r);
                                } else {
                                    RoutineScheduler.cancelRoutineReminder(appContext, r.getId());
                                }
                            }
                        }

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> loadRoutinesForSelectedDay());
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error synchronizing routines from Realtime Database", e);
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "attachCloudSyncListener cancelled: " + error.getMessage());
            }
        };

        routinesRef.addValueEventListener(routineSyncListener);
    }

    /**
     * Fallback and initial loader from Firestore for cross-device routine sync.
     */
    public void syncRoutinesFromCloud() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || getContext() == null) return;
        final String userId = user.getUid();
        final Context appContext = requireContext().getApplicationContext();

        FirebaseFirestore.getInstance().collection("users").document(userId)
                .collection("routines").get()
                .addOnSuccessListener(snapshots -> {
                    if (snapshots == null || snapshots.isEmpty()) return;
                    AppDatabase.getDatabaseExecutor().execute(() -> {
                        try {
                            AppDatabase db = AppDatabase.getInstance(appContext);
                            List<RoutineEntity> firestoreList = new ArrayList<>();

                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                String id = doc.getString("id");
                                if (id == null || id.isEmpty()) id = doc.getId();

                                String title = doc.getString("title");
                                Long dow = doc.getLong("dayOfWeek");
                                Long h = doc.getLong("hour");
                                Long m = doc.getLong("minute");
                                String tag = doc.getString("tag");
                                Boolean isEn = doc.getBoolean("isEnabled");
                                if (isEn == null) isEn = doc.getBoolean("enabled");
                                Long ca = doc.getLong("createdAt");

                                RoutineEntity r = new RoutineEntity(
                                        id,
                                        userId,
                                        title != null ? title : "",
                                        dow != null ? dow.intValue() : Calendar.MONDAY,
                                        h != null ? h.intValue() : 9,
                                        m != null ? m.intValue() : 0,
                                        tag != null ? tag : "Class",
                                        isEn != null ? isEn : false,
                                        ca != null ? ca : System.currentTimeMillis()
                                );
                                firestoreList.add(r);
                            }

                            if (!firestoreList.isEmpty()) {
                                db.routineDao().insertAll(firestoreList);
                                for (RoutineEntity r : firestoreList) {
                                    if (r.isEnabled()) {
                                        RoutineScheduler.scheduleRoutineReminder(appContext, r);
                                    } else {
                                        RoutineScheduler.cancelRoutineReminder(appContext, r.getId());
                                    }
                                }
                            }

                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> loadRoutinesForSelectedDay());
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error syncing routines from Firestore", e);
                        }
                    });
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (routinesRef != null && routineSyncListener != null) {
            routinesRef.removeEventListener(routineSyncListener);
        }
        binding = null;
    }
}

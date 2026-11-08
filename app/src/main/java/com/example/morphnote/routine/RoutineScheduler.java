package com.example.morphnote.routine;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.RoutineEntity;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for scheduling and managing exact 30-minute advance routine reminders.
 *
 * <p>Calculates the next recurring weekly occurrence of a routine's day and time,
 * subtracts exactly 30 minutes, and sets an exact alarm via {@link AlarmManager}.</p>
 */
public final class RoutineScheduler {

    private static final String TAG = "RoutineScheduler";

    // ── Intent Extra Keys ─────────────────────────────────────────────────────

    public static final String EXTRA_ROUTINE_ID     = "ROUTINE_ID";
    public static final String EXTRA_ROUTINE_TITLE  = "ROUTINE_TITLE";
    public static final String EXTRA_ROUTINE_TIME   = "ROUTINE_TIME";
    public static final String EXTRA_ROUTINE_DAY    = "ROUTINE_DAY";
    public static final String EXTRA_ROUTINE_TAG    = "ROUTINE_TAG";
    public static final String EXTRA_ROUTINE_HOUR   = "ROUTINE_HOUR";
    public static final String EXTRA_ROUTINE_MINUTE = "ROUTINE_MINUTE";

    /** 30 minutes advance notification notice in milliseconds. */
    public static final long ADVANCE_NOTICE_MS = 30L * 60L * 1000L;

    private RoutineScheduler() {}

    /**
     * Calculates the next upcoming trigger time (in epoch milliseconds) exactly 30 minutes
     * before the scheduled day and time.
     */
    public static long calculateNextTriggerTime(int dayOfWeek, int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        int currentDay = now.get(Calendar.DAY_OF_WEEK);
        int daysDiff = (dayOfWeek - currentDay + 7) % 7;
        target.add(Calendar.DAY_OF_YEAR, daysDiff);

        long triggerTimeMillis = target.getTimeInMillis() - ADVANCE_NOTICE_MS;

        // If this week's trigger time has already passed, schedule for next week (+7 days)
        if (triggerTimeMillis <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 7);
            triggerTimeMillis = target.getTimeInMillis() - ADVANCE_NOTICE_MS;
        }

        return triggerTimeMillis;
    }

    /**
     * Schedules an exact 30-minute advance reminder alarm for the given routine.
     */
    public static void scheduleRoutineReminder(Context context, RoutineEntity routine) {
        if (context == null || routine == null) return;

        if (!routine.isEnabled()) {
            cancelRoutineReminder(context, routine.getId());
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "scheduleRoutineReminder: AlarmManager is null.");
            return;
        }

        long triggerTimeMillis = calculateNextTriggerTime(
                routine.getDayOfWeek(), routine.getHour(), routine.getMinute());

        Intent intent = new Intent(context, RoutineNotificationReceiver.class);
        intent.putExtra(EXTRA_ROUTINE_ID, routine.getId());
        intent.putExtra(EXTRA_ROUTINE_TITLE, routine.getTitle());
        intent.putExtra(EXTRA_ROUTINE_TIME, routine.getFormattedTime());
        intent.putExtra(EXTRA_ROUTINE_DAY, routine.getDayOfWeek());
        intent.putExtra(EXTRA_ROUTINE_TAG, routine.getTag());
        intent.putExtra(EXTRA_ROUTINE_HOUR, routine.getHour());
        intent.putExtra(EXTRA_ROUTINE_MINUTE, routine.getMinute());

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                Math.abs(routine.getId().hashCode()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent);
                Log.d(TAG, "Scheduled exact routine alarm for " + routine.getTitle() + " at " + triggerTimeMillis);
            } else {
                Log.w(TAG, "Cannot schedule exact routine alarm – SCHEDULE_EXACT_ALARM not granted.");
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent);
            Log.d(TAG, "Scheduled exact routine alarm (API≥23) for " + routine.getTitle() + " at " + triggerTimeMillis);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent);
            Log.d(TAG, "Scheduled exact routine alarm (API<23) for " + routine.getTitle() + " at " + triggerTimeMillis);
        }
    }

    /**
     * Cancels any scheduled reminder alarm for the specified routine ID.
     */
    public static void cancelRoutineReminder(Context context, String routineId) {
        if (context == null || routineId == null || routineId.isEmpty()) return;

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, RoutineNotificationReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                Math.abs(routineId.hashCode()),
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "Cancelled routine reminder for id=" + routineId);
        }
    }

    /**
     * Queries all enabled routines from Room and re-schedules alarms for each one.
     * Safe to call from the main thread; runs on {@link AppDatabase#getDatabaseExecutor()}.
     */
    public static void rescheduleAllRoutines(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                List<RoutineEntity> routines = db.routineDao().getAllEnabledRoutines();
                Log.d(TAG, "rescheduleAllRoutines: restoring " + routines.size() + " routine alarms.");

                for (RoutineEntity routine : routines) {
                    scheduleRoutineReminder(appContext, routine);
                }
            } catch (Exception e) {
                Log.e(TAG, "rescheduleAllRoutines: error restoring routine alarms.", e);
            }
        });
    }
}


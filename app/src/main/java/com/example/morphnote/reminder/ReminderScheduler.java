package com.example.morphnote.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.EventEntity;

import java.util.List;

/**
 * Utility class for scheduling and cancelling exact reminder alarms via {@link AlarmManager}.
 *
 * <p>All alarm operations target {@link ReminderBroadcastReceiver} as the delivery point.
 * Priority-based advance lead times are calculated dynamically:
 * <ul>
 *   <li><b>Urgent (2)</b>: Notifies 60 minutes (1 hour) before the scheduled time.</li>
 *   <li><b>High (1)</b>: Notifies 15 minutes before the scheduled time.</li>
 *   <li><b>Normal/Default (0)</b>: Notifies at the exact scheduled time (0 minutes).</li>
 * </ul>
 * </p>
 */
public final class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";

    // Prevent instantiation – this is a static utility class.
    private ReminderScheduler() {}

    // ── Extra keys ────────────────────────────────────────────────────────────

    /** Intent extra key for the block or event's unique identifier. */
    public static final String EXTRA_BLOCK_ID        = "blockId";

    /** Intent extra key for the task or event title displayed in the notification. */
    public static final String EXTRA_TASK_CONTENT    = "taskContent";

    /** Intent extra key for the item's priority (0 = normal, 1 = high, 2 = urgent). */
    public static final String EXTRA_PRIORITY        = "priority";

    /** Intent extra key for the original scheduled timestamp (ms since epoch). */
    public static final String EXTRA_SCHEDULED_TIME  = "scheduledTime";

    /** Intent extra key boolean indicating if this is an EventEntity rather than BlockEntity. */
    public static final String EXTRA_IS_EVENT        = "isEvent";

    // ── Priority Lead Time Helpers ────────────────────────────────────────────

    /**
     * Returns the advance lead time in milliseconds according to priority.
     *
     * @param priority 2 for Urgent (60m), 1 for High (15m), 0 for Normal/Default (0m).
     * @return Milliseconds before the event when the reminder should fire.
     */
    public static long getLeadTimeMs(int priority) {
        if (priority == BlockEntity.PRIORITY_URGENT) {
            return 60L * 60L * 1000L; // 60 minutes before
        } else if (priority == BlockEntity.PRIORITY_HIGH) {
            return 15L * 60L * 1000L; // 15 minutes before
        } else {
            return 0L; // Exact scheduled time
        }
    }

    /**
     * Calculates the actual alarm trigger time taking priority lead time into account.
     * If the offset trigger time is already in the past but the scheduled item is still in
     * the future, returns (now + 2s) so the user receives an immediate heads-up alert.
     *
     * @param scheduledTimeMs The target scheduled time.
     * @param priority        Priority level (0 = normal, 1 = high, 2 = urgent).
     * @return Trigger timestamp in milliseconds since epoch.
     */
    public static long calculateTriggerTime(long scheduledTimeMs, int priority) {
        long leadTimeMs = getLeadTimeMs(priority);
        long triggerTime = scheduledTimeMs - leadTimeMs;
        long now = System.currentTimeMillis();

        if (triggerTime <= now && scheduledTimeMs > now) {
            return now + 2000L; // Fire shortly in 2 seconds
        }
        return triggerTime;
    }

    // ── Public Scheduling API ─────────────────────────────────────────────────

    /**
     * Schedules an exact alarm with priority lead-time calculation.
     *
     * @param context         Application context.
     * @param id              Unique item identifier.
     * @param content         Human-readable title/content for notification.
     * @param scheduledTimeMs Scheduled epoch time.
     * @param priority        Priority level.
     * @param isEvent         {@code true} if this is a calendar event.
     */
    public static void scheduleReminder(Context context,
                                        String id,
                                        String content,
                                        long scheduledTimeMs,
                                        int priority,
                                        boolean isEvent) {
        if (context == null || id == null || id.isEmpty()) {
            Log.w(TAG, "scheduleReminder: null context or id – skipping.");
            return;
        }

        long triggerTimeMs = calculateTriggerTime(scheduledTimeMs, priority);
        if (triggerTimeMs <= System.currentTimeMillis()) {
            Log.d(TAG, "scheduleReminder: calculated trigger time is in the past for id=" + id + ", skipping.");
            return;
        }

        dispatchAlarm(context, id, content, scheduledTimeMs, triggerTimeMs, priority, isEvent);
    }

    /**
     * Convenience overload for task blocks or callers without isEvent parameter.
     */
    public static void scheduleReminder(Context context,
                                        String id,
                                        String content,
                                        long scheduledTimeMs,
                                        int priority) {
        scheduleReminder(context, id, content, scheduledTimeMs, priority, false);
    }

    /**
     * Legacy backward-compatible method default to normal priority (0).
     */
    public static void scheduleReminder(Context context,
                                        String id,
                                        String content,
                                        long reminderTimeMs) {
        scheduleReminder(context, id, content, reminderTimeMs, BlockEntity.PRIORITY_NORMAL, false);
    }

    /**
     * Schedules an alarm to fire at an exact absolute trigger timestamp without applying
     * priority lead-time deductions (e.g. for Snooze operations).
     */
    public static void scheduleExactTrigger(Context context,
                                            String id,
                                            String content,
                                            long triggerTimeMs,
                                            int priority,
                                            boolean isEvent) {
        if (context == null || id == null || id.isEmpty()) return;
        if (triggerTimeMs <= System.currentTimeMillis()) return;

        dispatchAlarm(context, id, content, triggerTimeMs, triggerTimeMs, priority, isEvent);
    }

    /**
     * Internal alarm dispatcher that configures the system {@link AlarmManager}.
     */
    private static void dispatchAlarm(Context context,
                                      String id,
                                      String content,
                                      long scheduledTimeMs,
                                      long triggerTimeMs,
                                      int priority,
                                      boolean isEvent) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "dispatchAlarm: AlarmManager is null.");
            return;
        }

        PendingIntent pendingIntent = buildPendingIntent(context, id, content, scheduledTimeMs,
                priority, isEvent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent);
                Log.d(TAG, "Scheduled exact alarm (API≥31) for id=" + id
                        + " trigger=" + triggerTimeMs + " (scheduled=" + scheduledTimeMs + ", prio=" + priority + ")");
            } else {
                Log.w(TAG, "Cannot schedule exact alarms – SCHEDULE_EXACT_ALARM not granted. id=" + id);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent);
            Log.d(TAG, "Scheduled exact alarm (API≥23) for id=" + id
                    + " trigger=" + triggerTimeMs + " (scheduled=" + scheduledTimeMs + ", prio=" + priority + ")");
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent);
            Log.d(TAG, "Scheduled exact alarm (API<23) for id=" + id
                    + " trigger=" + triggerTimeMs);
        }
    }

    /**
     * Cancels a previously scheduled reminder alarm for the given identifier.
     *
     * @param context Application context.
     * @param id      Unique identifier whose alarm should be cancelled.
     */
    public static void cancelReminder(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) {
            Log.w(TAG, "cancelReminder: null context or id – skipping.");
            return;
        }

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "cancelReminder: AlarmManager is null.");
            return;
        }

        PendingIntent pendingIntent = buildPendingIntent(context, id, "", 0L, 0, false,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "Cancelled reminder for id=" + id);
        } else {
            Log.d(TAG, "cancelReminder: no existing alarm found for id=" + id);
        }
    }

    /**
     * Queries the Room database for all upcoming (future, incomplete) reminder blocks and
     * events, and re-schedules an exact alarm for each one.
     */
    public static void rescheduleAllReminders(Context context) {
        if (context == null) {
            Log.w(TAG, "rescheduleAllReminders: null context – skipping.");
            return;
        }

        final Context appContext = context.getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                long now = System.currentTimeMillis();

                // 1. Restore task block reminders
                List<BlockEntity> upcoming = db.blockDao().getUpcomingReminders(now);
                Log.d(TAG, "rescheduleAllReminders: found " + upcoming.size() + " upcoming task reminder(s).");
                for (BlockEntity block : upcoming) {
                    if (block.getReminderTime() != null && !block.isCompleted()) {
                        scheduleReminder(
                                appContext,
                                block.getId(),
                                block.getContent() != null ? block.getContent() : "Task Reminder",
                                block.getReminderTime(),
                                block.getPriority(),
                                false
                        );
                    }
                }

                // 2. Restore standalone calendar events
                List<EventEntity> upcomingEvents = db.eventDao().getUpcomingEventReminders(now);
                Log.d(TAG, "rescheduleAllReminders: found " + upcomingEvents.size() + " upcoming event reminder(s).");
                for (EventEntity event : upcomingEvents) {
                    if (event.getEventTime() > 0 && !event.isCompleted()) {
                        scheduleReminder(
                                appContext,
                                event.getId(),
                                event.getTitle() != null ? event.getTitle() : "Calendar Event",
                                event.getEventTime(),
                                event.getPriority(),
                                true
                        );
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "rescheduleAllReminders: error querying database.", e);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a {@link PendingIntent} targeting {@link ReminderBroadcastReceiver} with all
     * necessary extras attached.
     */
    private static PendingIntent buildPendingIntent(Context context,
                                                    String id,
                                                    String content,
                                                    long scheduledTimeMs,
                                                    int priority,
                                                    boolean isEvent,
                                                    int flags) {
        Intent intent = new Intent(context, ReminderBroadcastReceiver.class);
        intent.putExtra(EXTRA_BLOCK_ID, id);
        intent.putExtra(EXTRA_TASK_CONTENT, content);
        intent.putExtra(EXTRA_SCHEDULED_TIME, scheduledTimeMs);
        intent.putExtra(EXTRA_PRIORITY, priority);
        intent.putExtra(EXTRA_IS_EVENT, isEvent);

        return PendingIntent.getBroadcast(
                context,
                Math.abs(id.hashCode()),
                intent,
                flags
        );
    }
}

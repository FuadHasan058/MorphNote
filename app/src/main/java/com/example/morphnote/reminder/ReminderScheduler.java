package com.example.morphnote.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;

import java.util.List;

/**
 * Utility class for scheduling and cancelling exact reminder alarms via {@link AlarmManager}.
 *
 * <p>All alarm operations target {@link ReminderBroadcastReceiver} as the delivery point.
 * API-level branching ensures the correct alarm API is used on every supported platform.</p>
 */
public final class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";

    // Prevent instantiation – this is a static utility class.
    private ReminderScheduler() {}

    // ── Extra keys ────────────────────────────────────────────────────────────

    /** Intent extra key for the block's unique identifier. */
    public static final String EXTRA_BLOCK_ID     = "blockId";

    /** Intent extra key for the block's text content displayed in the notification. */
    public static final String EXTRA_TASK_CONTENT = "taskContent";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedules an exact alarm that will fire a reminder notification at {@code reminderTimeMs}.
     *
     * <p>Behaviour by API level:</p>
     * <ul>
     *   <li>API &ge; 31 (S): uses {@code setExactAndAllowWhileIdle} only when
     *       {@code AlarmManager.canScheduleExactAlarms()} returns {@code true}.</li>
     *   <li>API &ge; 23 (M): uses {@code setExactAndAllowWhileIdle} unconditionally.</li>
     *   <li>Below API 23: uses {@code setExact}.</li>
     * </ul>
     *
     * @param context         Application context.
     * @param blockId         Unique block identifier – also used as the PendingIntent request code
     *                        (via {@link String#hashCode()}).
     * @param taskContent     Human-readable task text shown in the notification body.
     * @param reminderTimeMs  Absolute trigger time in milliseconds since the Unix epoch.
     */
    public static void scheduleReminder(Context context,
                                        String blockId,
                                        String taskContent,
                                        long reminderTimeMs) {
        if (context == null || blockId == null) {
            Log.w(TAG, "scheduleReminder: null context or blockId – skipping.");
            return;
        }

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "scheduleReminder: AlarmManager is null.");
            return;
        }

        PendingIntent pendingIntent = buildPendingIntent(context, blockId, taskContent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires SCHEDULE_EXACT_ALARM permission; check at runtime.
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, reminderTimeMs, pendingIntent);
                Log.d(TAG, "Scheduled exact alarm (API≥31) for block=" + blockId
                        + " at " + reminderTimeMs);
            } else {
                Log.w(TAG, "Cannot schedule exact alarms – SCHEDULE_EXACT_ALARM not granted. "
                        + "Block=" + blockId);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, reminderTimeMs, pendingIntent);
            Log.d(TAG, "Scheduled exact alarm (API≥23) for block=" + blockId
                    + " at " + reminderTimeMs);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, reminderTimeMs, pendingIntent);
            Log.d(TAG, "Scheduled exact alarm (API<23) for block=" + blockId
                    + " at " + reminderTimeMs);
        }
    }

    /**
     * Cancels a previously scheduled reminder alarm for the given {@code blockId}.
     *
     * @param context Application context.
     * @param blockId Unique block identifier whose alarm should be cancelled.
     */
    public static void cancelReminder(Context context, String blockId) {
        if (context == null || blockId == null) {
            Log.w(TAG, "cancelReminder: null context or blockId – skipping.");
            return;
        }

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "cancelReminder: AlarmManager is null.");
            return;
        }

        // FLAG_NO_CREATE returns null if no matching PendingIntent exists, so we pass a
        // placeholder content intent – the flags on cancel only need to match the request code.
        PendingIntent pendingIntent = buildPendingIntent(context, blockId, "",
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "Cancelled reminder for block=" + blockId);
        } else {
            Log.d(TAG, "cancelReminder: no existing alarm found for block=" + blockId);
        }
    }

    /**
     * Queries the Room database for all upcoming (future, incomplete) reminder blocks and
     * re-schedules an exact alarm for each one.
     *
     * <p>This method is intended to be called after device reboot to restore alarms that were
     * cleared from {@link AlarmManager} when the system shut down.  The database query and
     * alarm scheduling are run on {@link AppDatabase#getDatabaseExecutor()} so this method
     * is safe to call from the main thread.</p>
     *
     * @param context Application context.
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
                List<BlockEntity> upcoming =
                        db.blockDao().getUpcomingReminders(System.currentTimeMillis());

                Log.d(TAG, "rescheduleAllReminders: found " + upcoming.size()
                        + " upcoming reminder(s).");

                for (BlockEntity block : upcoming) {
                    if (block.getReminderTime() != null) {
                        scheduleReminder(
                                appContext,
                                block.getId(),
                                block.getContent(),
                                block.getReminderTime()
                        );
                    }
                }

                List<com.example.morphnote.database.EventEntity> upcomingEvents =
                        db.eventDao().getUpcomingEventReminders(System.currentTimeMillis());
                for (com.example.morphnote.database.EventEntity event : upcomingEvents) {
                    if (event.getEventTime() > 0) {
                        scheduleReminder(
                                appContext,
                                event.getId(),
                                event.getTitle(),
                                event.getEventTime()
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
     * Builds a {@link PendingIntent} targeting {@link ReminderBroadcastReceiver} with the
     * block extras attached.  The request code is derived from {@code blockId.hashCode()} so
     * that every block gets a stable, unique pending intent.
     */
    private static PendingIntent buildPendingIntent(Context context,
                                                    String blockId,
                                                    String taskContent,
                                                    int flags) {
        Intent intent = new Intent(context, ReminderBroadcastReceiver.class);
        intent.putExtra(EXTRA_BLOCK_ID, blockId);
        intent.putExtra(EXTRA_TASK_CONTENT, taskContent);

        return PendingIntent.getBroadcast(
                context,
                blockId.hashCode(),
                intent,
                flags
        );
    }
}

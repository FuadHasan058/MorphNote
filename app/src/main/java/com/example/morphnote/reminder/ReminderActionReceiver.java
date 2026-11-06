package com.example.morphnote.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;

/**
 * {@link BroadcastReceiver} that handles notification action buttons produced by
 * {@link ReminderBroadcastReceiver}.
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@link ReminderBroadcastReceiver#ACTION_MARK_DONE} – marks the block as completed
 *       in the Room database and dismisses the notification.</li>
 *   <li>{@link ReminderBroadcastReceiver#ACTION_SNOOZE} – reschedules the alarm
 *       10&nbsp;minutes into the future and dismisses the current notification.</li>
 * </ul>
 *
 * <p>All database writes are executed on {@link AppDatabase#getDatabaseExecutor()} so
 * this receiver is safe to call from {@code onReceive} without blocking the main thread.</p>
 */
public class ReminderActionReceiver extends BroadcastReceiver {

    private static final String TAG = "ReminderActionReceiver";

    /** Snooze duration in milliseconds (10 minutes). */
    private static final long SNOOZE_DURATION_MS = 10L * 60L * 1000L;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action      = intent.getAction();
        String blockId     = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID);
        String taskContent = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_CONTENT);

        if (blockId == null || blockId.isEmpty()) {
            Log.w(TAG, "onReceive: missing blockId for action=" + action);
            return;
        }

        if (ReminderBroadcastReceiver.ACTION_MARK_DONE.equals(action)) {
            handleMarkDone(context, blockId);
        } else if (ReminderBroadcastReceiver.ACTION_SNOOZE.equals(action)) {
            handleSnooze(context, blockId, taskContent != null ? taskContent : "");
        } else {
            Log.w(TAG, "onReceive: unrecognised action=" + action);
        }
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    /**
     * Marks the block identified by {@code blockId} as completed in the database,
     * then cancels the reminder alarm and dismisses the notification.
     *
     * @param context Application context.
     * @param blockId The unique block identifier.
     */
    private void handleMarkDone(Context context, String blockId) {
        final Context appContext = context.getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                BlockEntity block = db.blockDao().getBlockById(blockId);
                if (block != null) {
                    block.setCompleted(true);
                    db.blockDao().update(block);
                    Log.d(TAG, "Block marked as done: " + blockId);
                } else {
                    com.example.morphnote.database.EventEntity event = db.eventDao().getEventById(blockId);
                    if (event != null) {
                        event.setCompleted(true);
                        db.eventDao().update(event);
                        Log.d(TAG, "Event marked as done: " + blockId);
                    } else {
                        Log.w(TAG, "handleMarkDone: neither block nor event found in DB: " + blockId);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "handleMarkDone: DB error for block=" + blockId, e);
            }
        });

        // Cancel the scheduled alarm so it does not fire again.
        ReminderScheduler.cancelReminder(appContext, blockId);

        // Dismiss the notification that hosted the action button.
        dismissNotification(appContext, blockId);
    }

    /**
     * Reschedules the alarm for {@code blockId} to fire 10 minutes from now,
     * then dismisses the current notification.
     *
     * @param context     Application context.
     * @param blockId     The unique block identifier.
     * @param taskContent The task text to show in the snoozed notification.
     */
    private void handleSnooze(Context context, String blockId, String taskContent) {
        final Context appContext = context.getApplicationContext();
        long snoozedTime = System.currentTimeMillis() + SNOOZE_DURATION_MS;

        ReminderScheduler.scheduleReminder(appContext, blockId, taskContent, snoozedTime);
        Log.d(TAG, "Snoozed block=" + blockId + " until " + snoozedTime);

        // Dismiss the existing notification.
        dismissNotification(appContext, blockId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Cancels (dismisses) the notification whose ID is derived from {@code blockId}.
     *
     * @param context Application context.
     * @param blockId The unique block identifier.
     */
    private static void dismissNotification(Context context, String blockId) {
        int notificationId = Math.abs(blockId.hashCode());
        NotificationManagerCompat.from(context).cancel(notificationId);
        Log.d(TAG, "Dismissed notification id=" + notificationId + " for block=" + blockId);
    }
}

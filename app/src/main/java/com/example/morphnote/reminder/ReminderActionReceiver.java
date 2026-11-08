package com.example.morphnote.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.EventEntity;
import com.example.morphnote.service.FocusModeService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link BroadcastReceiver} that handles interactive notification action buttons produced by
 * {@link ReminderBroadcastReceiver}.
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@link ReminderBroadcastReceiver#ACTION_MARK_DONE} – marks the block or calendar event
 *       as completed in Room, syncs the completed state to Firebase Firestore and Realtime Database,
 *       triggers an update on {@link FocusModeService}, cancels pending alarms, and dismisses the notification.</li>
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

        String action        = intent.getAction();
        String blockId       = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID);
        String taskContent   = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_CONTENT);
        int priority         = intent.getIntExtra(ReminderScheduler.EXTRA_PRIORITY, BlockEntity.PRIORITY_NORMAL);
        boolean isEvent      = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_EVENT, false);

        if (blockId == null || blockId.isEmpty()) {
            Log.w(TAG, "onReceive: missing blockId for action=" + action);
            return;
        }

        if (ReminderBroadcastReceiver.ACTION_MARK_DONE.equals(action)) {
            handleMarkDone(context, blockId);
        } else if (ReminderBroadcastReceiver.ACTION_SNOOZE.equals(action)) {
            handleSnooze(context, blockId, taskContent != null ? taskContent : "", priority, isEvent);
        } else {
            Log.w(TAG, "onReceive: unrecognised action=" + action);
        }
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    /**
     * Marks the block or event identified by {@code blockId} as completed in the database,
     * syncs status to Firebase Firestore and Realtime Database, notifies {@link FocusModeService},
     * cancels the reminder alarm, and dismisses the notification.
     *
     * @param context Application context.
     * @param blockId The unique block or event identifier.
     */
    private void handleMarkDone(Context context, String blockId) {
        final Context appContext = context.getApplicationContext();

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                BlockEntity block = db.blockDao().getBlockById(blockId);

                if (block != null) {
                    block.setCompleted(true);
                    block.setStatus(BlockEntity.STATUS_DONE);
                    db.blockDao().update(block);
                    Log.d(TAG, "Block marked as done: " + blockId);

                    // Sync to cloud
                    final String pageId = block.getPageId();
                    if (pageId != null && !pageId.isEmpty()) {
                        FirebaseDatabase.getInstance().getReference("pages")
                                .child(pageId)
                                .child("blocks")
                                .child(blockId)
                                .child("isCompleted").setValue(true);
                        FirebaseDatabase.getInstance().getReference("pages")
                                .child(pageId)
                                .child("blocks")
                                .child(blockId)
                                .child("status").setValue(BlockEntity.STATUS_DONE);

                        FirebaseFirestore fs = FirebaseFirestore.getInstance();
                        fs.collection("pages").document(pageId)
                                .collection("blocks").document(blockId)
                                .update("isCompleted", true, "status", BlockEntity.STATUS_DONE);

                        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                        if (user != null) {
                            fs.collection("users").document(user.getUid())
                                    .collection("pages").document(pageId)
                                    .collection("blocks").document(blockId)
                                    .update("isCompleted", true, "status", BlockEntity.STATUS_DONE);
                        }
                    }
                } else {
                    EventEntity event = db.eventDao().getEventById(blockId);
                    if (event != null) {
                        event.setCompleted(true);
                        db.eventDao().update(event);
                        Log.d(TAG, "Event marked as done: " + blockId);

                        // Sync event completion to Firebase Firestore and Realtime Database
                        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                        String uid = (user != null) ? user.getUid() : event.getUserId();
                        if (uid != null && !uid.isEmpty()) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("id", event.getId());
                            map.put("userId", uid);
                            map.put("title", event.getTitle());
                            map.put("eventTime", event.getEventTime());
                            map.put("priority", event.getPriority());
                            map.put("hasReminder", event.isHasReminder());
                            map.put("isCompleted", true);

                            FirebaseFirestore fs = FirebaseFirestore.getInstance();
                            fs.collection("users").document(uid)
                                    .collection("events").document(event.getId()).set(map);
                            fs.collection("events").document(event.getId()).set(map);

                            FirebaseDatabase.getInstance().getReference("events")
                                    .child(uid).child(event.getId()).setValue(map);
                            Log.d(TAG, "Synced event completed to Firebase: " + blockId);
                        }
                    } else {
                        Log.w(TAG, "handleMarkDone: neither block nor event found in DB: " + blockId);
                    }
                }

                // Notify FocusModeService to immediately refresh the notification
                Intent updateIntent = new Intent(appContext, FocusModeService.class);
                updateIntent.setAction(FocusModeService.ACTION_UPDATE_NOTIFICATION);
                ContextCompat.startForegroundService(appContext, updateIntent);

            } catch (Exception e) {
                Log.e(TAG, "handleMarkDone: DB error for id=" + blockId, e);
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
     * @param priority    Priority level.
     * @param isEvent     Whether this item is an event.
     */
    private void handleSnooze(Context context, String blockId, String taskContent, int priority, boolean isEvent) {
        final Context appContext = context.getApplicationContext();
        long snoozedTime = System.currentTimeMillis() + SNOOZE_DURATION_MS;

        ReminderScheduler.scheduleExactTrigger(appContext, blockId, taskContent, snoozedTime, priority, isEvent);
        Log.d(TAG, "Snoozed id=" + blockId + " until " + snoozedTime);

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
        Log.d(TAG, "Dismissed notification id=" + notificationId + " for id=" + blockId);
    }
}

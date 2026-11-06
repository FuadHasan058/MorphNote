package com.example.morphnote.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;

/**
 * {@link BroadcastReceiver} that fires when an exact alarm scheduled by
 * {@link ReminderScheduler} is triggered.
 *
 * <p>Builds and posts a heads-up notification with three action buttons:
 * <ol>
 *   <li><b>✓ Done</b> – marks the block as completed.</li>
 *   <li><b>⏰ Snooze 10m</b> – reschedules the alarm 10 minutes into the future.</li>
 *   <li><b>Open Note</b> – launches {@link MainActivity}.</li>
 * </ol>
 * </p>
 */
public class ReminderBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "ReminderBroadcastRcvr";

    // ── Notification channel constants ────────────────────────────────────────

    /** Stable notification channel ID for all MorphNote reminder alerts. */
    public static final String CHANNEL_ID   = "morph_reminder_channel";

    /** Human-readable channel name shown in system settings. */
    public static final String CHANNEL_NAME = "MorphNote Task Reminders";

    // ── Action constants ──────────────────────────────────────────────────────

    /** Broadcast action fired when the user taps "✓ Done". */
    public static final String ACTION_MARK_DONE =
            "com.example.morphnote.ACTION_REMINDER_MARK_DONE";

    /** Broadcast action fired when the user taps "⏰ Snooze 10m". */
    public static final String ACTION_SNOOZE =
            "com.example.morphnote.ACTION_REMINDER_SNOOZE";

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String blockId     = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID);
        String taskContent = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_CONTENT);

        if (blockId == null || blockId.isEmpty()) {
            Log.w(TAG, "onReceive: missing blockId extra – dropping alarm.");
            return;
        }
        if (taskContent == null) {
            taskContent = "";
        }

        Log.d(TAG, "Reminder fired for block=" + blockId);

        // Ensure the notification channel exists (no-op on API < 26).
        createReminderChannel(context);

        // ── Action: Mark Done ─────────────────────────────────────────────────
        Intent doneIntent = new Intent(ACTION_MARK_DONE);
        doneIntent.setPackage(context.getPackageName());
        doneIntent.putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId);
        PendingIntent donePendingIntent = PendingIntent.getBroadcast(
                context,
                (blockId.hashCode() * 31) + 1,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Action: Snooze 10 minutes ─────────────────────────────────────────
        Intent snoozeIntent = new Intent(ACTION_SNOOZE);
        snoozeIntent.setPackage(context.getPackageName());
        snoozeIntent.putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId);
        snoozeIntent.putExtra(ReminderScheduler.EXTRA_TASK_CONTENT, taskContent);
        PendingIntent snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                (blockId.hashCode() * 31) + 2,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Action: Open Note ─────────────────────────────────────────────────
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                (blockId.hashCode() * 31) + 3,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Build notification ────────────────────────────────────────────────
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_todo)
                .setContentTitle("Task Reminder 🔔")
                .setContentText(taskContent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(taskContent))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .addAction(R.drawable.ic_check_circle, "✓ Done",         donePendingIntent)
                .addAction(R.drawable.ic_refresh,      "⏰ Snooze 10m",   snoozePendingIntent)
                .addAction(R.drawable.ic_open_in_new,  "Open Note",       openPendingIntent);

        // Notification ID is a stable integer derived from the block's UUID.
        int notificationId = Math.abs(blockId.hashCode());

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
            Log.d(TAG, "Notification posted with id=" + notificationId);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS permission not granted on API 33+.
            Log.w(TAG, "POST_NOTIFICATIONS permission denied – notification not shown.", e);
        }
    }

    // ── Channel helper ────────────────────────────────────────────────────────

    /**
     * Creates the {@value #CHANNEL_ID} notification channel with
     * {@link NotificationManager#IMPORTANCE_HIGH}.  Safe to call repeatedly –
     * creating an existing channel is a no-op on the platform.
     *
     * @param context Any valid {@link Context}.
     */
    private static void createReminderChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alerts for scheduled MorphNote task reminders.");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}

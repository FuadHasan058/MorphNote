package com.example.morphnote.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;
import com.example.morphnote.database.BlockEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * {@link BroadcastReceiver} that fires when an exact alarm scheduled by
 * {@link ReminderScheduler} is triggered.
 *
 * <p>Builds and posts a heads-up notification with three action buttons:
 * <ol>
 *   <li><b>✓ Done</b> – marks the task or event as completed in Room & syncs to Firebase.</li>
 *   <li><b>⏰ Snooze 10m</b> – reschedules the alarm 10 minutes into the future.</li>
 *   <li><b>Open MorphNote</b> – launches {@link MainActivity}.</li>
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

        String blockId       = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID);
        String taskContent   = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_CONTENT);
        int priority         = intent.getIntExtra(ReminderScheduler.EXTRA_PRIORITY, BlockEntity.PRIORITY_NORMAL);
        long scheduledTimeMs = intent.getLongExtra(ReminderScheduler.EXTRA_SCHEDULED_TIME, 0L);
        boolean isEvent      = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_EVENT, false);

        if (blockId == null || blockId.isEmpty()) {
            Log.w(TAG, "onReceive: missing blockId extra – dropping alarm.");
            return;
        }
        if (taskContent == null || taskContent.trim().isEmpty()) {
            taskContent = isEvent ? "Scheduled Event" : "Scheduled Task";
        }

        Log.d(TAG, "Reminder fired for id=" + blockId + " (isEvent=" + isEvent + ", priority=" + priority + ")");

        // Ensure the notification channel exists (no-op on API < 26).
        createReminderChannel(context);

        // ── Action: Mark Done ─────────────────────────────────────────────────
        Intent doneIntent = new Intent(context, ReminderActionReceiver.class);
        doneIntent.setAction(ACTION_MARK_DONE);
        doneIntent.putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId);
        doneIntent.putExtra(ReminderScheduler.EXTRA_IS_EVENT, isEvent);
        PendingIntent donePendingIntent = PendingIntent.getBroadcast(
                context,
                Math.abs((blockId.hashCode() * 31) + 1),
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Action: Snooze 10 minutes ─────────────────────────────────────────
        Intent snoozeIntent = new Intent(context, ReminderActionReceiver.class);
        snoozeIntent.setAction(ACTION_SNOOZE);
        snoozeIntent.putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId);
        snoozeIntent.putExtra(ReminderScheduler.EXTRA_TASK_CONTENT, taskContent);
        snoozeIntent.putExtra(ReminderScheduler.EXTRA_PRIORITY, priority);
        snoozeIntent.putExtra(ReminderScheduler.EXTRA_IS_EVENT, isEvent);
        PendingIntent snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                Math.abs((blockId.hashCode() * 31) + 2),
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Action: Open App ──────────────────────────────────────────────────
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                Math.abs((blockId.hashCode() * 31) + 3),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Title & Content Formatter based on Priority Lead Time ─────────────
        String title;
        StringBuilder bigText = new StringBuilder(taskContent);

        if (scheduledTimeMs > 0) {
            SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
            String formattedTime = timeFmt.format(new Date(scheduledTimeMs));

            if (priority == BlockEntity.PRIORITY_URGENT) {
                title = isEvent ? "🚨 Urgent Event (In 1 hour)" : "🚨 Urgent Task (In 1 hour)";
                bigText.append("\n⏰ Scheduled at ").append(formattedTime).append(" (1 hour notice)");
            } else if (priority == BlockEntity.PRIORITY_HIGH) {
                title = isEvent ? "⚡ High Priority Event (In 15 min)" : "⚡ High Priority Task (In 15 min)";
                bigText.append("\n⏰ Scheduled at ").append(formattedTime).append(" (15 min notice)");
            } else {
                title = isEvent ? "📅 Event Reminder" : "Task Reminder 🔔";
                bigText.append("\n⏰ Scheduled at ").append(formattedTime);
            }
        } else {
            if (priority == BlockEntity.PRIORITY_URGENT) {
                title = isEvent ? "🚨 Urgent Event Reminder" : "🚨 Urgent Task Reminder";
            } else if (priority == BlockEntity.PRIORITY_HIGH) {
                title = isEvent ? "⚡ High Priority Reminder" : "⚡ High Priority Task";
            } else {
                title = isEvent ? "📅 Event Reminder" : "Task Reminder 🔔";
            }
        }

        // ── Build notification ────────────────────────────────────────────────
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(isEvent ? R.drawable.ic_calendar : R.drawable.ic_todo)
                .setLargeIcon(BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_morphnote_logo))
                .setContentTitle(title)
                .setContentText(taskContent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText.toString()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .addAction(R.drawable.ic_check_circle, "✓ Done",         donePendingIntent)
                .addAction(R.drawable.ic_refresh,      "⏰ Snooze 10m",   snoozePendingIntent)
                .addAction(R.drawable.ic_open_in_new,  "Open Note",       openPendingIntent);

        int notificationId = Math.abs(blockId.hashCode());

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
            Log.d(TAG, "Notification posted with id=" + notificationId);
        } catch (SecurityException e) {
            Log.w(TAG, "POST_NOTIFICATIONS permission denied – notification not shown.", e);
        }
    }

    // ── Channel helper ────────────────────────────────────────────────────────

    /**
     * Creates the {@value #CHANNEL_ID} notification channel with
     * {@link NotificationManager#IMPORTANCE_HIGH}.
     */
    private static void createReminderChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alerts for scheduled MorphNote task and event reminders.");
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

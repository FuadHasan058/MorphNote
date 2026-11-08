package com.example.morphnote.routine;

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
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.RoutineEntity;

/**
 * {@link BroadcastReceiver} that triggers an exact 30-minute advance notification
 * for scheduled weekly routines and automatically reschedules the alarm for the next week.
 */
public class RoutineNotificationReceiver extends BroadcastReceiver {

    private static final String TAG = "RoutineNotifReceiver";

    public static final String CHANNEL_ID   = "morph_routine_channel";
    public static final String CHANNEL_NAME = "MorphNote Routine Reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        final String routineId    = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_ID);
        final String routineTitle = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_TITLE);
        final String routineTime  = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_TIME);
        final String routineTag   = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_TAG);

        if (routineId == null || routineId.isEmpty()) {
            Log.w(TAG, "onReceive: missing routineId – dropping alarm.");
            return;
        }

        final String title = (routineTitle != null && !routineTitle.trim().isEmpty())
                ? routineTitle
                : "Scheduled Routine";
        final String time = (routineTime != null && !routineTime.trim().isEmpty())
                ? routineTime
                : "";

        Log.d(TAG, "Routine reminder fired for: " + title + " at " + time);

        // Ensure notification channel exists (API 26+)
        createRoutineChannel(context);

        // Intent to launch MainActivity and automatically select the Routine tab (index 1)
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("EXTRA_SELECTED_TAB", 1);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                Math.abs((routineId.hashCode() * 37) + 1),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String contentText = time.isEmpty()
                ? title + " starts in 30 minutes"
                : title + " starts at " + time + " (in 30 mins)";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_today)
                .setLargeIcon(BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_morphnote_logo))
                .setContentTitle("Upcoming Routine in 30 Mins")
                .setContentText(contentText)
                .setSubText(routineTag != null ? routineTag : "Routine")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent);

        int notificationId = Math.abs((routineId.hashCode() * 37) + 99);

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
            Log.d(TAG, "Routine notification posted id=" + notificationId);
        } catch (SecurityException e) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted.", e);
        }

        // Reschedule alarm for the next recurring week
        final Context appContext = context.getApplicationContext();
        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                RoutineEntity routine = db.routineDao().getRoutineById(routineId);
                if (routine != null && routine.isEnabled()) {
                    RoutineScheduler.scheduleRoutineReminder(appContext, routine);
                    Log.d(TAG, "Recurring routine reminder scheduled for next week: " + routineId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error rescheduling routine for next week", e);
            }
        });
    }

    private static void createRoutineChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Advance 30-minute reminder alerts for weekly scheduled routines.");
            channel.enableLights(true);
            channel.enableVibration(true);

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}


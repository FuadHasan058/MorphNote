package com.example.morphnote.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;

import java.util.List;

public class FocusModeService extends Service {

    public static final String CHANNEL_ID = "morph_focus_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_MARK_DONE = "com.example.morphnote.ACTION_MARK_DONE";
    public static final String ACTION_DISMISS = "com.example.morphnote.ACTION_DISMISS";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;

        if (ACTION_DISMISS.equals(action)) {
            stopFocusMode();
            return START_NOT_STICKY;
        }

        if (ACTION_MARK_DONE.equals(action)) {
            markNextTaskDone();
        } else {
            refreshNotification();
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MorphNote Focus Mode",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active pending To-Do tasks in ongoing focus mode");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void refreshNotification() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

            String statusText;
            if (pendingTodos.isEmpty()) {
                statusText = "All tasks completed! Great job!";
            } else {
                BlockEntity topTask = pendingTodos.get(0);
                String taskDesc = (topTask.getContent() != null && !topTask.getContent().isEmpty())
                        ? topTask.getContent() : "Untitled Task";
                statusText = "(" + pendingTodos.size() + " pending) Next: " + taskDesc;
            }

            Notification notification = buildCustomNotification(statusText);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        });
    }

    private void markNextTaskDone() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

            if (!pendingTodos.isEmpty()) {
                BlockEntity topTask = pendingTodos.get(0);
                topTask.setCompleted(true);
                db.blockDao().update(topTask);
            }

            // After marking done, re-query and refresh notification
            List<BlockEntity> remaining = db.blockDao().getPendingTodoBlocks();
            String statusText;
            if (remaining.isEmpty()) {
                statusText = "All tasks completed! Great job!";
            } else {
                BlockEntity nextTask = remaining.get(0);
                String taskDesc = (nextTask.getContent() != null && !nextTask.getContent().isEmpty())
                        ? nextTask.getContent() : "Untitled Task";
                statusText = "(" + remaining.size() + " pending) Next: " + taskDesc;
            }

            Notification notification = buildCustomNotification(statusText);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        });
    }

    private Notification buildCustomNotification(String taskText) {
        RemoteViews customView = new RemoteViews(getPackageName(), R.layout.notification_focus_mode);
        customView.setTextViewText(R.id.tvNotificationTasks, taskText);

        // PendingIntent to launch MainActivity when notification body is tapped
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Interactive PendingIntent for Mark Next Done action
        Intent doneIntent = new Intent(this, FocusModeActionReceiver.class);
        doneIntent.setAction(ACTION_MARK_DONE);
        PendingIntent donePendingIntent = PendingIntent.getBroadcast(
                this, 1, doneIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        customView.setOnClickPendingIntent(R.id.btnNotificationDone, donePendingIntent);

        // Interactive PendingIntent for Dismiss action
        Intent dismissIntent = new Intent(this, FocusModeActionReceiver.class);
        dismissIntent.setAction(ACTION_DISMISS);
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(
                this, 2, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        customView.setOnClickPendingIntent(R.id.btnNotificationDismiss, dismissPendingIntent);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_focus)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(customView)
                .setContentIntent(mainPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void stopFocusMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}


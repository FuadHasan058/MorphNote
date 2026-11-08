package com.example.morphnote.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.room.InvalidationTracker;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.database.EventEntity;
import com.example.morphnote.reminder.ReminderScheduler;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FocusModeService – foreground service that surfaces the top pending task or calendar event
 * as an ongoing notification and reacts to Room database changes in real time.
 */
public class FocusModeService extends Service {

    private static final String TAG = "FocusModeService";

    // ── Notification constants ────────────────────────────────────────────────

    /** Notification channel ID for the focus mode persistent notification. */
    public static final String CHANNEL_ID = "morph_focus_channel";

    /** Stable notification ID used throughout the service lifetime. */
    public static final int NOTIFICATION_ID = 1001;

    // ── Intent action constants ───────────────────────────────────────────────

    /** Marks the top pending task or event as completed. */
    public static final String ACTION_MARK_DONE =
            "com.example.morphnote.ACTION_MARK_DONE";

    /** Stops the focus mode service and removes its notification. */
    public static final String ACTION_DISMISS =
            "com.example.morphnote.ACTION_DISMISS";

    /**
     * Triggers an explicit notification refresh; sent by external components
     * when task or event data changes.
     */
    public static final String ACTION_UPDATE_NOTIFICATION =
            "com.example.morphnote.ACTION_UPDATE_NOTIFICATION";

    // ── State fields ──────────────────────────────────────────────────────────

    private final AtomicBoolean isForegroundStarted = new AtomicBoolean(false);
    private NotificationManager notificationManager;
    private InvalidationTracker.Observer blocksObserver;

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Attach a Room InvalidationTracker observer so the notification refreshes
        // automatically whenever any row in the "blocks" or "events" tables changes.
        blocksObserver = new InvalidationTracker.Observer(new String[]{"blocks", "events"}) {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                refreshNotification();
            }
        };
        AppDatabase.getInstance(this).getInvalidationTracker().addObserver(blocksObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = (intent != null) ? intent.getAction() : null;

        if (ACTION_DISMISS.equals(action)) {
            stopFocusMode();
            return START_STICKY;
        }

        if (ACTION_MARK_DONE.equals(action)) {
            markNextTaskDone();
        } else {
            refreshNotification();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeBlocksObserver();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Notification channel ──────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MorphNote Focus Mode",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active pending tasks and events in ongoing focus mode.");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    /**
     * Queries pending TODO blocks and calendar events, determines the next item,
     * and refreshes the ongoing notification.
     */
    private void refreshNotification() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            final AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            final List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();
            final List<EventEntity> pendingEvents = db.eventDao().getAllPendingEvents();

            final int totalPending = pendingTodos.size() + pendingEvents.size();
            final String statusText;

            if (totalPending == 0) {
                statusText = "✅ All tasks completed! Great job!";
            } else {
                // Determine whether to display the top event or top todo task
                boolean showEvent = shouldPrioritizeEvent(pendingTodos, pendingEvents);
                if (showEvent && !pendingEvents.isEmpty()) {
                    EventEntity topEvent = pendingEvents.get(0);
                    statusText = "📅 (" + totalPending + " pending) Event: " + topEvent.getTitle();
                } else if (!pendingTodos.isEmpty()) {
                    BlockEntity topTask = pendingTodos.get(0);
                    String taskContent = (topTask.getContent() != null && !topTask.getContent().isEmpty())
                            ? topTask.getContent()
                            : "Untitled Task";
                    statusText = "📋 (" + totalPending + " pending) Next: " + taskContent;
                } else {
                    statusText = "📋 (" + totalPending + " pending items)";
                }
            }

            final Notification notification = buildFocusNotification(statusText);

            if (!isForegroundStarted.getAndSet(true)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            }

            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        });
    }

    /**
     * Helper to determine whether an event should take precedence in the notification
     * over the first pending task block.
     */
    private boolean shouldPrioritizeEvent(List<BlockEntity> pendingTodos, List<EventEntity> pendingEvents) {
        if (pendingEvents.isEmpty()) return false;
        if (pendingTodos.isEmpty()) return true;

        EventEntity topEvent = pendingEvents.get(0);
        BlockEntity topTask = pendingTodos.get(0);

        long now = System.currentTimeMillis();
        long eventTime = topEvent.getEventTime();
        Long taskDueDate = topTask.getDueDate();

        if (taskDueDate != null && taskDueDate > 0) {
            return eventTime < taskDueDate;
        }

        // If event is upcoming within 24 hours or past due, prioritize it
        return eventTime <= (now + 24L * 60L * 60L * 1000L);
    }

    /**
     * Builds a standard {@link NotificationCompat} notification for the focus mode.
     */
    private Notification buildFocusNotification(String taskText) {
        final int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        final Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent contentPendingIntent =
                PendingIntent.getActivity(this, 0, mainIntent, pendingFlags);

        final Intent markDoneIntent = new Intent(this, FocusModeActionReceiver.class);
        markDoneIntent.setAction(ACTION_MARK_DONE);
        final PendingIntent markDonePendingIntent =
                PendingIntent.getBroadcast(this, 1, markDoneIntent, pendingFlags);

        final Intent dismissIntent = new Intent(this, FocusModeActionReceiver.class);
        dismissIntent.setAction(ACTION_DISMISS);
        final PendingIntent dismissPendingIntent =
                PendingIntent.getBroadcast(this, 2, dismissIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_focus)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_morphnote_logo))
                .setContentTitle("MorphNote Focus Mode")
                .setContentText(taskText)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(false)
                .addAction(R.drawable.ic_check_circle, "✓ Mark Done", markDonePendingIntent)
                .addAction(R.drawable.ic_close, "✕ Dismiss", dismissPendingIntent)
                .build();
    }

    /**
     * Marks the active pending task or calendar event as completed on a background thread,
     * syncs the status to Firebase, and updates the notification.
     */
    private void markNextTaskDone() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            final AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            final List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();
            final List<EventEntity> pendingEvents = db.eventDao().getAllPendingEvents();

            if (pendingTodos.isEmpty() && pendingEvents.isEmpty()) {
                refreshNotification();
                return;
            }

            boolean markEvent = shouldPrioritizeEvent(pendingTodos, pendingEvents);

            if (markEvent && !pendingEvents.isEmpty()) {
                // Mark top event completed
                final EventEntity topEvent = pendingEvents.get(0);
                topEvent.setCompleted(true);
                db.eventDao().update(topEvent);
                Log.d(TAG, "FocusModeService: Event marked done: " + topEvent.getId());

                // Sync event completion to Firebase
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                String uid = (user != null) ? user.getUid() : topEvent.getUserId();
                if (uid != null && !uid.isEmpty()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", topEvent.getId());
                    map.put("userId", uid);
                    map.put("title", topEvent.getTitle());
                    map.put("eventTime", topEvent.getEventTime());
                    map.put("priority", topEvent.getPriority());
                    map.put("hasReminder", topEvent.isHasReminder());
                    map.put("isCompleted", true);

                    FirebaseFirestore.getInstance().collection("users").document(uid)
                            .collection("events").document(topEvent.getId()).set(map);
                    FirebaseFirestore.getInstance().collection("events").document(topEvent.getId()).set(map);
                    FirebaseDatabase.getInstance().getReference("events")
                            .child(uid).child(topEvent.getId()).setValue(map);
                }

                ReminderScheduler.cancelReminder(getApplicationContext(), topEvent.getId());

            } else if (!pendingTodos.isEmpty()) {
                // Mark top task completed
                final BlockEntity topTask = pendingTodos.get(0);
                topTask.setCompleted(true);
                topTask.setStatus(BlockEntity.STATUS_DONE);
                db.blockDao().update(topTask);
                Log.d(TAG, "FocusModeService: Block marked done: " + topTask.getId());

                final String pageId = topTask.getPageId();
                final String blockId = topTask.getId();
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

                ReminderScheduler.cancelReminder(getApplicationContext(), blockId);
            }

            // Refresh notification immediately
            refreshNotification();
        });
    }

    /**
     * Stops the foreground service and removes its notification.
     */
    private void stopFocusMode() {
        removeBlocksObserver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void removeBlocksObserver() {
        if (blocksObserver != null) {
            AppDatabase.getInstance(getApplicationContext())
                    .getInvalidationTracker()
                    .removeObserver(blocksObserver);
            blocksObserver = null;
        }
    }
}

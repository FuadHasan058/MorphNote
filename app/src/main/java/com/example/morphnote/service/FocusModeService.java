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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.room.InvalidationTracker;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FocusModeService – foreground service that surfaces the top pending TODO block
 * as an ongoing notification and reacts to Room database changes in real time.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #onCreate()} – creates the notification channel and registers a
 *       {@link InvalidationTracker.Observer} on the "blocks" table so that any
 *       external write (e.g. from {@link com.example.morphnote.editor.BlockEditorActivity})
 *       automatically refreshes the notification.</li>
 *   <li>{@link #onStartCommand(Intent, int, int)} – handles action intents and
 *       keeps the service alive with {@code START_STICKY}.</li>
 *   <li>{@link #onDestroy()} – unregisters the InvalidationTracker observer.</li>
 * </ol>
 */
public class FocusModeService extends Service {

    // ── Notification constants ────────────────────────────────────────────────

    /** Notification channel ID for the focus mode persistent notification. */
    public static final String CHANNEL_ID = "morph_focus_channel";

    /** Stable notification ID used throughout the service lifetime. */
    public static final int NOTIFICATION_ID = 1001;

    // ── Intent action constants ───────────────────────────────────────────────

    /** Marks the top pending TODO block as completed. */
    public static final String ACTION_MARK_DONE =
            "com.example.morphnote.ACTION_MARK_DONE";

    /** Stops the focus mode service and removes its notification. */
    public static final String ACTION_DISMISS =
            "com.example.morphnote.ACTION_DISMISS";

    /**
     * Triggers an explicit notification refresh; sent by external components
     * (e.g. the widget provider or the editor) when block data changes outside
     * of the InvalidationTracker cycle.
     */
    public static final String ACTION_UPDATE_NOTIFICATION =
            "com.example.morphnote.ACTION_UPDATE_NOTIFICATION";

    // ── State fields ──────────────────────────────────────────────────────────

    /**
     * Tracks whether {@link #startForeground} has already been called so that
     * subsequent refreshes use {@link NotificationManager#notify} instead,
     * which avoids a redundant foreground promotion attempt.
     */
    private final AtomicBoolean isForegroundStarted = new AtomicBoolean(false);

    /** Cached reference to the system {@link NotificationManager}. */
    private NotificationManager notificationManager;

    /**
     * Room InvalidationTracker observer watching the "blocks" table.
     * Stored so it can be removed in {@link #onDestroy()}.
     */
    private InvalidationTracker.Observer blocksObserver;

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Attach a Room InvalidationTracker observer so the notification refreshes
        // automatically whenever any row in the "blocks" table changes.
        blocksObserver = new InvalidationTracker.Observer(new String[]{"blocks"}) {
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
            // Return early; the service is stopping so START_STICKY is irrelevant,
            // but we still return it to satisfy the method contract before stopSelf().
            return START_STICKY;
        }

        if (ACTION_MARK_DONE.equals(action)) {
            markNextTaskDone();
        } else {
            // Handles ACTION_UPDATE_NOTIFICATION, null action (initial start), and any
            // unexpected action by simply refreshing the notification state.
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

    /**
     * Creates the low-importance notification channel required on API 26+.
     * Safe to call multiple times; the system is idempotent for existing channels.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MorphNote Focus Mode",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active pending To-Do tasks in ongoing focus mode.");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    /**
     * Queries pending TODO blocks on the database executor thread, builds an
     * appropriate status string, and either promotes the service to foreground
     * (first call) or updates the existing notification (subsequent calls).
     */
    private void refreshNotification() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            final AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            final List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

            final String statusText;
            if (pendingTodos.isEmpty()) {
                statusText = "✅ All tasks completed! Great job!";
            } else {
                final BlockEntity topTask = pendingTodos.get(0);
                final String taskContent =
                        (topTask.getContent() != null && !topTask.getContent().isEmpty())
                                ? topTask.getContent()
                                : "Untitled Task";
                statusText = "📋 (" + pendingTodos.size() + " pending) Next: " + taskContent;
            }

            final Notification notification = buildFocusNotification(statusText);

            if (!isForegroundStarted.getAndSet(true)) {
                // First call – promote to foreground.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            }

            // Always call notify() to ensure the displayed notification reflects
            // the latest state (startForeground alone may not update on some OEMs).
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        });
    }

    /**
     * Builds a standard {@link NotificationCompat} notification for the focus mode.
     *
     * <p>Uses the standard builder API (no custom {@link android.widget.RemoteViews})
     * for maximum compatibility across Android versions and OEM skins.
     *
     * @param taskText The status line displayed as the notification's content text.
     * @return A fully constructed {@link Notification} ready for display.
     */
    private Notification buildFocusNotification(String taskText) {
        final int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        // Content intent – opens MainActivity when the notification body is tapped.
        final Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent contentPendingIntent =
                PendingIntent.getActivity(this, 0, mainIntent, pendingFlags);

        // Action: "✓ Mark Done" – broadcasts to FocusModeActionReceiver.
        final Intent markDoneIntent = new Intent(this, FocusModeActionReceiver.class);
        markDoneIntent.setAction(ACTION_MARK_DONE);
        final PendingIntent markDonePendingIntent =
                PendingIntent.getBroadcast(this, 1, markDoneIntent, pendingFlags);

        // Action: "✕ Dismiss" – broadcasts to FocusModeActionReceiver.
        final Intent dismissIntent = new Intent(this, FocusModeActionReceiver.class);
        dismissIntent.setAction(ACTION_DISMISS);
        final PendingIntent dismissPendingIntent =
                PendingIntent.getBroadcast(this, 2, dismissIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_focus)
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
     * Marks the first pending TODO block as completed on a background thread,
     * then triggers a notification refresh to reflect the new state.
     */
    private void markNextTaskDone() {
        AppDatabase.getDatabaseExecutor().execute(() -> {
            final AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            final List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

            if (!pendingTodos.isEmpty()) {
                final BlockEntity topTask = pendingTodos.get(0);
                topTask.setCompleted(true);
                db.blockDao().update(topTask);
            }

            // Refresh after the update so the notification shows the new next task.
            // Note: the InvalidationTracker observer will also fire, but calling
            // refreshNotification() directly here avoids the observer's inherent delay.
            refreshNotification();
        });
    }

    /**
     * Stops the foreground service and removes its notification.
     *
     * <p>Also unregisters the InvalidationTracker observer preemptively before
     * {@link #onDestroy()} to prevent any in-flight observer callbacks from
     * attempting to restart the notification after the service has stopped.
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

    /**
     * Safely removes the {@link #blocksObserver} from the Room InvalidationTracker.
     * Guards against double-removal with a null check and sets the reference to
     * {@code null} afterward so subsequent calls are no-ops.
     */
    private void removeBlocksObserver() {
        if (blocksObserver != null) {
            AppDatabase.getInstance(getApplicationContext())
                    .getInvalidationTracker()
                    .removeObserver(blocksObserver);
            blocksObserver = null;
        }
    }
}

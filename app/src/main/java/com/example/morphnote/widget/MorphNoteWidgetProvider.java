package com.example.morphnote.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.widget.RemoteViews;

import com.example.morphnote.MainActivity;
import com.example.morphnote.R;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.provider.BlockContentProvider;

import java.util.List;

public class MorphNoteWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_MANUAL_REFRESH = "com.example.morphnote.ACTION_WIDGET_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (context == null || appWidgetManager == null) return;
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            ComponentName thisWidget = new ComponentName(context, MorphNoteWidgetProvider.class);
            appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        }
        if (appWidgetIds == null || appWidgetIds.length == 0) return;

        final PendingResult pendingResult = goAsync();
        final int[] finalIds = appWidgetIds;
        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                updateWidgetsInternal(context, appWidgetManager, finalIds);
            } finally {
                if (pendingResult != null) {
                    try {
                        pendingResult.finish();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null || context == null) return;
        String action = intent.getAction();
        if (ACTION_MANUAL_REFRESH.equals(action) ||
                AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, MorphNoteWidgetProvider.class);
            int[] allWidgetIds = manager.getAppWidgetIds(thisWidget);
            if (allWidgetIds != null && allWidgetIds.length > 0) {
                final PendingResult pendingResult = goAsync();
                AppDatabase.getDatabaseExecutor().execute(() -> {
                    try {
                        updateWidgetsInternal(context, manager, allWidgetIds);
                    } finally {
                        if (pendingResult != null) {
                            try {
                                pendingResult.finish();
                            } catch (Exception ignored) {}
                        }
                    }
                });
            }
        }
    }

    private void updateWidgetsInternal(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (context == null || appWidgetManager == null || appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }

        StringBuilder taskBuilder = new StringBuilder();

        try {
            AppDatabase db = AppDatabase.getInstance(context.getApplicationContext());
            List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

            if (pendingTodos == null || pendingTodos.isEmpty()) {
                taskBuilder.append("✨ No pending tasks!\nEnjoy your day or create a new note.");
            } else {
                int count = Math.min(pendingTodos.size(), 5);
                for (int i = 0; i < count; i++) {
                    BlockEntity b = pendingTodos.get(i);
                    String content = (b.getContent() != null && !b.getContent().trim().isEmpty())
                            ? b.getContent().trim() : "Untitled Task";
                    if (BlockEntity.STATUS_IN_PROGRESS.equalsIgnoreCase(b.getStatus())) {
                        taskBuilder.append("⏳ ").append(content).append("\n");
                    } else {
                        taskBuilder.append("☐ ").append(content).append("\n");
                    }
                }
                if (pendingTodos.size() > 5) {
                    taskBuilder.append("+ ").append(pendingTodos.size() - 5).append(" more...");
                }
            }
        } catch (Exception e) {
            // Fallback to ContentProvider query if direct database call fails
            try (Cursor cursor = context.getContentResolver().query(
                    BlockContentProvider.CONTENT_URI, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    taskBuilder.append("• Active notes loaded (").append(cursor.getCount()).append(" blocks)");
                } else {
                    taskBuilder.append("Open MorphNote to sync tasks.");
                }
            } catch (Exception ex) {
                taskBuilder.append("Tap to view notes.");
            }
        }

        final String displayText = taskBuilder.toString().trim();

        for (int widgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_morph_note);
            views.setTextViewText(R.id.tvWidgetTasks, displayText);

            // Open MainActivity PendingIntent
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPendingIntent = PendingIntent.getActivity(
                    context, widgetId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widgetContainer, openPendingIntent);
            views.setOnClickPendingIntent(R.id.tvWidgetTasks, openPendingIntent);

            // Refresh Button PendingIntent
            Intent refreshIntent = new Intent(context, MorphNoteWidgetProvider.class);
            refreshIntent.setAction(ACTION_MANUAL_REFRESH);
            PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(
                    context, widgetId + 500, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPendingIntent);

            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }

    /**
     * Helper to broadcast a widget refresh from anywhere in the application.
     */
    public static void sendRefreshBroadcast(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, MorphNoteWidgetProvider.class);
            intent.setAction(ACTION_MANUAL_REFRESH);
            context.sendBroadcast(intent);
        } catch (Exception ignored) {}
    }
}

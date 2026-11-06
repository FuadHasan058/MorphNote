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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MorphNoteWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_MANUAL_REFRESH = "com.example.morphnote.ACTION_WIDGET_REFRESH";
    private final ExecutorService widgetExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_MANUAL_REFRESH.equals(intent.getAction())) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, MorphNoteWidgetProvider.class);
            int[] allWidgetIds = manager.getAppWidgetIds(thisWidget);
            updateAllWidgets(context, manager, allWidgetIds);
        }
    }

    private void updateAllWidgets(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        widgetExecutor.execute(() -> {
            StringBuilder taskBuilder = new StringBuilder();

            try {
                // Query local pending tasks via Room database or ContentProvider
                AppDatabase db = AppDatabase.getInstance(context.getApplicationContext());
                List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

                if (pendingTodos.isEmpty()) {
                    taskBuilder.append("✨ No pending tasks!\nEnjoy your day or create a new note.");
                } else {
                    int count = Math.min(pendingTodos.size(), 5);
                    for (int i = 0; i < count; i++) {
                        BlockEntity b = pendingTodos.get(i);
                        String content = (b.getContent() != null && !b.getContent().trim().isEmpty())
                                ? b.getContent() : "Untitled Task";
                        taskBuilder.append("☐ ").append(content).append("\n");
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

            final String displayText = taskBuilder.toString();

            for (int widgetId : appWidgetIds) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_morph_note);
                views.setTextViewText(R.id.tvWidgetTasks, displayText);

                // Open MainActivity PendingIntent
                Intent openIntent = new Intent(context, MainActivity.class);
                openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent openPendingIntent = PendingIntent.getActivity(
                        context, widgetId, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                views.setOnClickPendingIntent(R.id.widgetContainer, openPendingIntent);

                // Refresh Button PendingIntent
                Intent refreshIntent = new Intent(context, MorphNoteWidgetProvider.class);
                refreshIntent.setAction(ACTION_MANUAL_REFRESH);
                PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(
                        context, widgetId + 500, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPendingIntent);

                appWidgetManager.updateAppWidget(widgetId, views);
            }
        });
    }
}


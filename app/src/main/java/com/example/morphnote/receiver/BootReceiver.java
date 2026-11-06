package com.example.morphnote.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.database.BlockEntity;
import com.example.morphnote.reminder.ReminderScheduler;
import com.example.morphnote.service.FocusModeService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private final ExecutorService bootExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            final PendingResult pendingResult = goAsync();

            bootExecutor.execute(() -> {
                try {
                    AppDatabase db = AppDatabase.getInstance(context.getApplicationContext());
                    List<BlockEntity> pendingTodos = db.blockDao().getPendingTodoBlocks();

                    Log.d(TAG, "Reboot detected. Found " + pendingTodos.size() + " pending tasks to reschedule.");

                    AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    if (alarmManager != null && !pendingTodos.isEmpty()) {
                        // Reschedule daily focus mode reminder trigger 10 minutes from boot
                        long triggerTime = System.currentTimeMillis() + (10 * 60 * 1000);

                        Intent reminderIntent = new Intent(context, FocusModeService.class);
                        PendingIntent alarmPendingIntent = PendingIntent.getService(
                                context,
                                9901,
                                reminderIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        );

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    triggerTime,
                                    alarmPendingIntent
                            );
                        } else {
                            alarmManager.setExact(
                                    AlarmManager.RTC_WAKEUP,
                                    triggerTime,
                                    alarmPendingIntent
                            );
                        }
                    }

                    // Restore all individual block reminder alarms that were cleared on reboot.
                    ReminderScheduler.rescheduleAllReminders(context.getApplicationContext());
                    Log.d(TAG, "ReminderScheduler.rescheduleAllReminders() dispatched.");

                } catch (Exception e) {
                    Log.e(TAG, "Error rescheduling tasks on boot", e);
                } finally {
                    pendingResult.finish();
                }
            });
        }
    }
}


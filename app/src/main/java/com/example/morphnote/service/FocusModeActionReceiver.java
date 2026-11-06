package com.example.morphnote.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public class FocusModeActionReceiver extends BroadcastReceiver {

    public static final String ACTION_MARK_DONE = "com.example.morphnote.ACTION_MARK_DONE";
    public static final String ACTION_DISMISS = "com.example.morphnote.ACTION_DISMISS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        Intent serviceIntent = new Intent(context, FocusModeService.class);
        serviceIntent.setAction(intent.getAction());
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}


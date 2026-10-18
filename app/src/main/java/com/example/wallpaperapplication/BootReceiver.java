package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    public static final String ACTION_RESTART_STREAMING = "com.example.wallpaperapplication.RESTART_STREAMING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Broadcast received: " + action);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.d(TAG, "Boot completed, evaluating startup...");
            if (prefs.getBoolean(Constants.PREF_BOOT_ENABLED, true)) {
                prefs.edit().putBoolean(Constants.PREF_STREAMING_ENABLED, true).apply();
                Intent serviceIntent = new Intent(context, StreamingService.class);
                ContextCompat.startForegroundService(context, serviceIntent);

                // WorkManager Backup (Persistent Kickstart on boot)
                try {
                    androidx.work.OneTimeWorkRequest workRequest = new androidx.work.OneTimeWorkRequest.Builder(DataSyncWorker.class)
                            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .build();
                    androidx.work.WorkManager.getInstance(context).enqueue(workRequest);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to schedule worker", e);
                }
            } else {
                Log.d(TAG, "Streaming on boot is disabled by user settings.");
            }
        } else if (ACTION_RESTART_STREAMING.equals(action)) {
            if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
                Log.d(TAG, "Reviving StreamingService after task removal...");
                Intent serviceIntent = new Intent(context, StreamingService.class);
                try {
                    ContextCompat.startForegroundService(context, serviceIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to startForegroundService from BootReceiver", e);
                }

                // WorkManager Backup (Persistent Kickstart on task removal)
                try {
                    androidx.work.OneTimeWorkRequest workRequest = new androidx.work.OneTimeWorkRequest.Builder(DataSyncWorker.class)
                            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .build();
                    androidx.work.WorkManager.getInstance(context).enqueue(workRequest);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to schedule worker", e);
                }
            }
        }
    }
}
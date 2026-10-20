package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import androidx.preference.PreferenceManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    public static final String ACTION_RESTART_STREAMING = "com.example.wallpaperapplication.RESTART_STREAMING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Broadcast received: " + action);

        Context storageContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) {
                storageContext = context.createDeviceProtectedStorageContext();
            }
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);

        boolean isBootAction =
                Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
                Intent.ACTION_REBOOT.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                Intent.ACTION_USER_PRESENT.equals(action);

        if (isBootAction) {
            Log.d(TAG, "Device boot/unlock detected (" + action + "), evaluating startup...");
            if (prefs.getBoolean(Constants.PREF_BOOT_ENABLED, true)) {
                try {
                    prefs.edit().putBoolean(Constants.PREF_STREAMING_ENABLED, true).apply();
                } catch (Exception ignored) {}

                StreamingWatchdogReceiver.scheduleNext(context);
            } else {
                Log.d(TAG, "Streaming on boot is disabled by user settings.");
            }
        } else if (ACTION_RESTART_STREAMING.equals(action)) {
            if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
                Log.d(TAG, "Reviving StreamingService after task removal...");
                StreamingWatchdogReceiver.startStreamingService(context);
                StreamingWatchdogReceiver.scheduleNext(context);
            }
        }
    }
}

package com.example.wallpaperapplication;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

/**
 * StreamingWatchdogReceiver periodically verifies that the StreamingService is running
 * and actively streaming in the background. If the service is stopped or killed,
 * this watchdog restarts it automatically.
 */
public class StreamingWatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "StreamingWatchdog";
    public static final String ACTION_CHECK_STREAMING = "com.example.wallpaperapplication.CHECK_STREAMING";
    public static final long WATCHDOG_INTERVAL_MS = 25_000; // Check every 25 seconds

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;

        Context storageContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.os.UserManager um = (android.os.UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) {
                storageContext = context.createDeviceProtectedStorageContext();
            }
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
        boolean streamingEnabled = prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true);

        if (!streamingEnabled) {
            Log.d(TAG, "Streaming is disabled by user setting. Skipping watchdog revival.");
            return;
        }

        StreamingService service = StreamingService.getInstance();
        boolean isRunning = (service != null && service.isServiceRunning());

        if (!isRunning) {
            Log.w(TAG, "Watchdog detected StreamingService is NOT running! Restarting now...");
            startStreamingService(context);
        } else {
            Log.d(TAG, "Watchdog: StreamingService is running. Enforcing connection health...");
            service.checkAndEnforceStreaming();
        }

        // Always schedule the next check
        scheduleNext(context);
    }

    public static void startStreamingService(Context context) {
        if (context == null) return;
        try {
            Intent serviceIntent = new Intent(context.getApplicationContext(), StreamingService.class);
            Context targetContext = WallpaperAccessibilityService.isRunning()
                    ? WallpaperAccessibilityService.getInstance()
                    : context.getApplicationContext();

            ContextCompat.startForegroundService(targetContext, serviceIntent);
            Log.i(TAG, "Started StreamingService via ContextCompat.startForegroundService (caller=" + targetContext.getClass().getSimpleName() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start StreamingService from watchdog", e);
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    public static void scheduleNext(Context context) {
        if (context == null) return;
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, StreamingWatchdogReceiver.class);
            intent.setAction(ACTION_CHECK_STREAMING);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 2024, intent, flags);

            long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                } catch (SecurityException se) {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            Log.d(TAG, "Scheduled next watchdog check in " + (WATCHDOG_INTERVAL_MS / 1000) + "s");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling watchdog alarm", e);
        }
    }

    public static void cancel(Context context) {
        if (context == null) return;
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, StreamingWatchdogReceiver.class);
            intent.setAction(ACTION_CHECK_STREAMING);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 2024, intent, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
                Log.d(TAG, "Cancelled watchdog alarm");
            }
        } catch (Exception ignored) {}
    }
}

package com.example.wallpaperapplication;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

public class RestrictedSettingsHelper {

    /**
     * Checks if Restricted Settings (OP_ACCESS_RESTRICTED_SETTINGS) is currently active
     * for this package on Android 13+ (API 33+).
     */
    public static boolean isRestricted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
                if (appOps != null) {
                    int mode = appOps.unsafeCheckOpNoThrow(
                            "android:access_restricted_settings",
                            android.os.Process.myUid(),
                            context.getPackageName()
                    );
                    return mode != AppOpsManager.MODE_ALLOWED;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Directly opens the Application Details Settings (App Info) screen where the
     * 3-dot menu -> "Allow restricted settings" option is located on Android 13/14+.
     */
    public static void openAppInfo(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", context.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    /**
     * Displays a dialog guiding the user on how to enable "Allow restricted settings"
     * if the permission is greyed out.
     */
    public static void showGuidanceDialog(Context context, String permissionName, Runnable onProceed) {
        new AlertDialog.Builder(context)
                .setTitle("Restricted Setting (Android 13/14+)")
                .setMessage("Android 13 and 14 restrict " + permissionName + " for apps installed outside the Google Play Store.\n\n"
                        + "If the toggle in Settings is greyed out:\n"
                        + "1. Tap 'Open App Info' below.\n"
                        + "2. Tap the 3 dots (⋮) in the top-right corner.\n"
                        + "3. Tap 'Allow restricted settings' and confirm your PIN.\n"
                        + "4. Return here to enable " + permissionName + ".")
                .setPositiveButton("Open App Info", (dialog, which) -> openAppInfo(context))
                .setNeutralButton("Proceed to Setting", (dialog, which) -> {
                    if (onProceed != null) onProceed.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

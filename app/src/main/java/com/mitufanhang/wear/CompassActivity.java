package com.mitufanhang.wear;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A large, glanceable compass for calibrating the watch and for checking the
 * heading before starting the return trip.
 */
public final class CompassActivity extends Activity {
    private HeadingProvider headingProvider;
    private WatchFrameLayout root;
    private CompassView compassView;
    private TextView titleView;
    private TextView warningView;
    private TextView hintView;
    private LinearLayout bottomBox;
    private long startedAt;
    private boolean running;
    private LocationManager locationManager;
    private float gpsHeading = Float.NaN;
    private long gpsHeadingAt;
    private boolean gpsRegistered;
    private boolean noCompassHardware;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null && location.hasBearing() && location.getSpeed() > 0.6f) {
                gpsHeading = location.getBearing();
                gpsHeadingAt = SystemClock.elapsedRealtime();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            update();
            handler.postDelayed(this, 100L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        headingProvider = new HeadingProvider(this);
        applyKnownLocation();
    }

    private void buildUi() {
        root = new WatchFrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setRound(new AppSettings(this).resolveRound(getResources()));

        compassView = new CompassView(this);
        compassView.setContentDescription(getString(R.string.cd_compass));
        root.addView(compassView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = new TextView(this);
        titleView.setText(R.string.compass_title);
        titleView.setTextColor(getColor(R.color.mint));
        titleView.setTextSize(14f);
        titleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        titleView.setGravity(Gravity.CENTER);
        titleView.setIncludeFontPadding(false);
        root.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        warningView = new TextView(this);
        warningView.setTextColor(getColor(R.color.text_secondary));
        warningView.setTextSize(11f);
        warningView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        warningView.setGravity(Gravity.CENTER);
        warningView.setIncludeFontPadding(false);
        warningView.setText(R.string.compass_waiting);
        warningView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (noCompassHardware) {
                    showNoCompassHelp();
                } else {
                    showHelp();
                }
            }
        });

        hintView = new TextView(this);
        hintView.setTextColor(getColor(R.color.text_secondary));
        hintView.setTextSize(8.5f);
        hintView.setGravity(Gravity.CENTER);
        hintView.setIncludeFontPadding(false);
        hintView.setText(R.string.compass_hint_ok);

        bottomBox = new LinearLayout(this);
        bottomBox.setOrientation(LinearLayout.VERTICAL);
        bottomBox.setGravity(Gravity.CENTER);
        bottomBox.addView(warningView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(3f);
        bottomBox.addView(hintView, hintParams);
        root.addView(bottomBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    applyGeometry();
                }
            }
        });
        setContentView(root);
        applyGeometry();
    }

    private void applyGeometry() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        boolean round = new AppSettings(this).resolveRound(getResources());
        root.setRound(round);
        int side = Math.min(w, h);

        FrameLayout.LayoutParams titleParams =
                (FrameLayout.LayoutParams) titleView.getLayoutParams();
        titleParams.topMargin = round ? Math.round(side * 0.035f) : dp(6f);
        titleView.setLayoutParams(titleParams);

        FrameLayout.LayoutParams bottomParams =
                (FrameLayout.LayoutParams) bottomBox.getLayoutParams();
        bottomParams.width = Math.round(side * 0.78f);
        bottomParams.bottomMargin = round ? Math.round(side * 0.16f) : dp(14f);
        bottomBox.setLayoutParams(bottomParams);
    }

    private void applyKnownLocation() {
        TrackSession last = TrackStore.loadLast(this);
        if (last == null) {
            last = TrackStore.loadActive(this);
        }
        if (last != null && last.first() != null) {
            TrackPoint p = last.first();
            headingProvider.setLocation(p.lat, p.lon, 0);
        }
        // A fresh fix is more accurate for magnetic declination than the last
        // saved track, but only use it when the permission is already granted.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (lm != null) {
                    Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (loc == null) {
                        loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (loc != null) {
                        headingProvider.setLocation(loc.getLatitude(), loc.getLongitude(),
                                loc.getAltitude());
                    }
                }
            } catch (Exception ignored) {
                // Keep the saved track location or magnetic north.
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startedAt = SystemClock.elapsedRealtime();
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        headingProvider.start();
        startGpsCourse();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        running = false;
        handler.removeCallbacks(ticker);
        stopGpsCourse();
        headingProvider.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    private void startGpsCourse() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            if (locationManager == null) {
                locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            }
            if (locationManager != null && !gpsRegistered) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        1000L, 0f, locationListener, Looper.getMainLooper());
                gpsRegistered = true;
            }
        } catch (Exception ignored) {
            gpsRegistered = false;
        }
    }

    private void stopGpsCourse() {
        if (locationManager != null && gpsRegistered) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {
            }
        }
        gpsRegistered = false;
    }

    private void update() {
        boolean hasCompass = headingProvider.hasCompassSensor();
        long now = SystemClock.elapsedRealtime();
        boolean gpsFresh = !Float.isNaN(gpsHeading) && now - gpsHeadingAt < 5000L;

        noCompassHardware = !hasCompass;
        if (!hasCompass) {
            if (gpsFresh) {
                warningView.setText(R.string.compass_gps_ok);
                warningView.setTextColor(getColor(R.color.mint));
                hintView.setText(R.string.compass_gps_hint);
                compassView.setState(gpsHeading, false);
            } else {
                warningView.setText(R.string.compass_no_magnetometer);
                warningView.setTextColor(getColor(R.color.amber));
                hintView.setText(R.string.compass_no_magnetometer_hint);
                compassView.setState(Float.NaN, true);
            }
            return;
        }

        float heading = headingProvider.getHeading();
        boolean hasHeading = headingProvider.hasHeading();
        boolean needsCalibration = headingProvider.needsCalibration();
        boolean waiting = !hasHeading
                && now - startedAt < 2500L;

        if (waiting) {
            warningView.setText(R.string.compass_waiting);
            warningView.setTextColor(getColor(R.color.text_secondary));
            hintView.setText(R.string.compass_hint_ok);
        } else if (needsCalibration) {
            warningView.setText(R.string.compass_low);
            warningView.setTextColor(getColor(R.color.amber));
            hintView.setText(R.string.compass_hint_low);
        } else {
            warningView.setText(getString(R.string.compass_ok, accuracyLabel()));
            warningView.setTextColor(getColor(R.color.mint));
            hintView.setText(R.string.compass_hint_ok);
        }
        compassView.setState(hasHeading ? heading : Float.NaN,
                needsCalibration && !waiting);
    }

    private String accuracyLabel() {
        switch (headingProvider.getAccuracy()) {
            case android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                return getString(R.string.compass_accuracy_high);
            case android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                return getString(R.string.compass_accuracy_medium);
            case android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                return getString(R.string.compass_accuracy_low);
            default:
                return getString(R.string.compass_accuracy_unreliable);
        }
    }

    private void showNoCompassHelp() {
        WatchDialog.show(this,
                getString(R.string.compass_no_magnetometer_help_title),
                getString(R.string.compass_no_magnetometer_help),
                getString(R.string.ok),
                null, null);
    }

    private void showHelp() {
        WatchDialog.show(this,
                getString(R.string.compass_help_title),
                getString(R.string.compass_help_message),
                getString(R.string.compass_help_ok),
                null, null);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

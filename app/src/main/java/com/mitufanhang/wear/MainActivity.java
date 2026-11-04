package com.mitufanhang.wear;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The watch face of the app: a black map with the track, one primary action
 * and one secondary action, plus a tiny settings button.
 */
public final class MainActivity extends Activity implements TrackingService.Listener {

    private static final int REQ_LOCATION = 41;
    private static final int REQ_SETTINGS = 1002;

    private AppSettings settings;
    private WatchFrameLayout root;
    private TrackMapView mapView;
    private GuideView guideView;
    private TextView stateText;
    private TextView infoText;
    private Button primaryButton;
    private Button secondaryButton;
    private ImageButton settingsButton;
    private ImageButton compassButton;
    private FrameLayout topBox;
    private LinearLayout buttonRow;

    private TrackingService service;
    private boolean bound;
    private TrackingService.State state;
    private boolean keepScreenOn;
    private boolean pendingStart;
    private boolean restorePrompted;
    private boolean awaitingPermissionSettings;
    private boolean awaitingLocationSettings;
    private boolean calibrationPromptShown;
    private android.app.Dialog dialog;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TrackingService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
            state = service.getState();
            render(state);
            if (state != null && state.tracking) {
                // Re-apply interval / keep-awake changes made while Settings
                // was covering this activity.
                service.refreshSettings();
            }
            maybeRestore();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            updateKeepScreen();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        buildUi();
        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    applyGeometry();
                }
            }
        });
        applyGeometry();
    }

    private void buildUi() {
        root = new WatchFrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));

        mapView = new TrackMapView(this);
        mapView.setContentDescription(getString(R.string.cd_map));
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        guideView = new GuideView(this);
        root.addView(guideView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        stateText = new TextView(this);
        stateText.setTextColor(getColor(R.color.mint));
        stateText.setTextSize(13f);
        stateText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stateText.setGravity(Gravity.CENTER);
        stateText.setIncludeFontPadding(false);
        stateText.setMaxLines(1);
        stateText.setShadowLayer(dp(3f), 0f, dp(1f), 0xCC000000);
        stateText.setText(R.string.state_idle);

        infoText = new TextView(this);
        infoText.setTextColor(getColor(R.color.text_secondary));
        infoText.setTextSize(10f);
        infoText.setGravity(Gravity.CENTER);
        infoText.setIncludeFontPadding(false);
        infoText.setMaxLines(1);
        infoText.setShadowLayer(dp(3f), 0f, dp(1f), 0xCC000000);
        infoText.setText(R.string.hint_idle);

        topBox = new FrameLayout(this);
        FrameLayout.LayoutParams stateParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topBox.addView(stateText, stateParams);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        infoParams.topMargin = dp(18f);
        topBox.addView(infoText, infoParams);
        root.addView(topBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(34f),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        compassButton = new ImageButton(this);
        compassButton.setImageResource(R.drawable.ic_compass);
        compassButton.setBackgroundResource(R.drawable.bg_gear);
        compassButton.setPadding(dp(9f), dp(9f), dp(9f), dp(9f));
        compassButton.setContentDescription(getString(R.string.cd_compass));
        compassButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CompassActivity.class));
            }
        });
        root.addView(compassButton, new FrameLayout.LayoutParams(dp(40f), dp(40f),
                Gravity.TOP | Gravity.START));

        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.ic_gear);
        settingsButton.setBackgroundResource(R.drawable.bg_gear);
        settingsButton.setPadding(dp(9f), dp(9f), dp(9f), dp(9f));
        settingsButton.setContentDescription(getString(R.string.cd_settings));
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        root.addView(settingsButton, new FrameLayout.LayoutParams(dp(40f), dp(40f),
                Gravity.TOP | Gravity.END));

        primaryButton = makeButton(R.drawable.bg_primary, getColor(R.color.black));
        primaryButton.setText(R.string.start_record);
        primaryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPrimary();
            }
        });

        secondaryButton = makeButton(R.drawable.bg_secondary, getColor(R.color.text_primary));
        secondaryButton.setText(R.string.stop_record);
        secondaryButton.setVisibility(View.GONE);
        secondaryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmStop();
            }
        });

        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        primaryParams.setMargins(dp(2f), 0, dp(2f), 0);
        buttonRow.addView(primaryButton, primaryParams);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.62f);
        secondaryParams.setMargins(dp(2f), 0, dp(2f), 0);
        buttonRow.addView(secondaryButton, secondaryParams);

        root.addView(buttonRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(44f),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    }

    private Button makeButton(int background, int textColor) {
        Button button = new Button(this);
        button.setBackgroundResource(background);
        button.setTextColor(textColor);
        button.setTextSize(14f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2f), 0, dp(2f), 0);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!bound) {
            bound = bindService(new Intent(this, TrackingService.class), connection,
                    Context.BIND_AUTO_CREATE);
        }
        applyGeometry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyGeometry();
        if (service != null) {
            state = service.getState();
            render(state);
        }
        updateKeepScreen();
        if (awaitingPermissionSettings) {
            awaitingPermissionSettings = false;
            if (pendingStart && hasPermission()) {
                startWalk();
            } else if (pendingStart) {
                pendingStart = false;
            }
        } else if (awaitingLocationSettings) {
            awaitingLocationSettings = false;
            if (pendingStart && hasPermission() && locationEnabled()) {
                beginTracking();
            } else {
                pendingStart = false;
            }
        }
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.setListener(null);
        }
        if (bound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        service = null;
        restorePrompted = false;
        updateKeepScreen();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyGeometry();
        if (service != null) {
            render(service.getState());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            applyGeometry();
            if (service != null) {
                service.refreshSettings();
                state = service.getState();
                render(state);
            }
            updateKeepScreen();
        }
    }

    private void applyGeometry() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        boolean round = settings.resolveRound(getResources());
        root.setRound(round);
        int side = Math.min(w, h);

        FrameLayout.LayoutParams topParams = (FrameLayout.LayoutParams) topBox.getLayoutParams();
        topParams.width = round ? Math.round(side * 0.46f) : Math.round(w * 0.62f);
        topParams.height = dp(34f);
        topParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        topParams.topMargin = round ? Math.round(side * 0.085f) : dp(9f);
        topBox.setLayoutParams(topParams);

        FrameLayout.LayoutParams gearParams =
                (FrameLayout.LayoutParams) settingsButton.getLayoutParams();
        int gear = round ? Math.round(side * 0.15f) : dp(40f);
        gearParams.width = gear;
        gearParams.height = gear;
        gearParams.gravity = Gravity.TOP | Gravity.END;
        // On a round watch the extreme top-right corner is outside the visible
        // circle; pull the gear in so the whole touch target remains usable.
        gearParams.topMargin = round ? Math.round(side * 0.24f) : dp(8f);
        gearParams.rightMargin = round ? Math.round(side * 0.10f) : dp(8f);
        settingsButton.setLayoutParams(gearParams);
        settingsButton.setPadding(gear / 5, gear / 5, gear / 5, gear / 5);

        FrameLayout.LayoutParams compassParams =
                (FrameLayout.LayoutParams) compassButton.getLayoutParams();
        int compassSize = round ? Math.round(side * 0.15f) : dp(40f);
        compassParams.width = compassSize;
        compassParams.height = compassSize;
        compassParams.gravity = Gravity.TOP | Gravity.START;
        compassParams.topMargin = round ? Math.round(side * 0.24f) : dp(8f);
        compassParams.leftMargin = round ? Math.round(side * 0.10f) : dp(8f);
        compassButton.setLayoutParams(compassParams);
        compassButton.setPadding(compassSize / 5, compassSize / 5,
                compassSize / 5, compassSize / 5);

        FrameLayout.LayoutParams rowParams =
                (FrameLayout.LayoutParams) buttonRow.getLayoutParams();
        rowParams.width = round ? Math.round(side * 0.70f) : w - dp(20f);
        rowParams.height = dp(44f);
        rowParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rowParams.bottomMargin = round ? Math.round(side * 0.16f) : dp(12f);
        buttonRow.setLayoutParams(rowParams);
    }

    @Override
    public void onStateChanged(TrackingService.State newState) {
        state = newState;
        render(newState);
    }

    @Override
    public void onTrackFinished(TrackingService.State finished) {
        state = finished;
        render(finished);
        updateKeepScreen();
        if (isFinishing()) {
            return;
        }
        String message = getString(R.string.finish_message,
                Geo.formatDistance(finished.totalDistance),
                Geo.formatDuration(finished.elapsedMs),
                finished.pointCount);
        showDialog(getString(R.string.finish_title), message,
                getString(R.string.finish_ok), null, null);
    }

    @Override
    public void onError(String message) {
        if (message == null || message.length() == 0) {
            message = getString(R.string.permission_denied);
        }
        showDialog(getString(R.string.error_title), message,
                getString(R.string.ok), null, null);
    }

    private void render(TrackingService.State s) {
        boolean tracking = s != null && s.tracking;
        boolean returning = tracking && s.returning;
        boolean weak = tracking && s.hasFix && s.gpsAgeMs > 20000L;

        if (tracking) {
            if (!s.hasFix) {
                stateText.setText(R.string.state_searching);
                stateText.setTextColor(getColor(R.color.amber));
                infoText.setText(R.string.gps_waiting);
            } else if (returning) {
                if (s.arrived) {
                    stateText.setText(R.string.state_arrived);
                    stateText.setTextColor(getColor(R.color.mint));
                    infoText.setText(R.string.info_return_arrived);
                } else {
                    stateText.setText(R.string.state_returning);
                    stateText.setTextColor(getColor(R.color.mint));
                    String distance = Geo.formatDistance(s.distanceToStart);
                    if (s.offCourse) {
                        infoText.setText(getString(R.string.info_return_off, distance));
                    } else if (s.hasHeading) {
                        double relative = Geo.angleDelta(s.heading, s.bearingToStart);
                        infoText.setText(getString(R.string.info_return, distance,
                                Geo.directionText(relative)));
                    } else {
                        infoText.setText(getString(R.string.info_return_only, distance));
                    }
                }
            } else if (weak) {
                stateText.setText(R.string.state_weak);
                stateText.setTextColor(getColor(R.color.amber));
                infoText.setText(getString(R.string.info_elapsed,
                        getString(R.string.gps_weak), Geo.formatDuration(s.elapsedMs)));
            } else {
                stateText.setText(R.string.state_recording);
                stateText.setTextColor(getColor(R.color.mint));
                infoText.setText(getString(R.string.info_elapsed,
                        Geo.formatDuration(s.elapsedMs), Geo.formatDistance(s.totalDistance)));
            }
        } else if (s != null && s.hasTrack()) {
            stateText.setText(R.string.state_last_track);
            stateText.setTextColor(getColor(R.color.text_secondary));
            infoText.setText(getString(R.string.info_preview,
                    Geo.formatDistance(s.totalDistance), s.pointCount));
        } else {
            stateText.setText(R.string.state_idle);
            stateText.setTextColor(getColor(R.color.text_secondary));
            infoText.setText(R.string.hint_idle);
        }

        if (tracking) {
            primaryButton.setText(returning ? R.string.stop_return : R.string.start_return);
            secondaryButton.setVisibility(View.VISIBLE);
        } else {
            primaryButton.setText(R.string.start_record);
            secondaryButton.setVisibility(View.GONE);
        }

        boolean headingUp = settings.headingUp();
        boolean round = settings.resolveRound(getResources());
        mapView.setState(s, headingUp, round);
        guideView.setState(s, headingUp);
        updateKeepScreen();

        boolean needCalibration = tracking && returning && !s.arrived
                && s.headingNeedsCalibration;
        updateCompassVisual(needCalibration);
        maybePromptCalibration(returning, needCalibration);
    }

    private void updateCompassVisual(boolean needCalibration) {
        if (compassButton == null) {
            return;
        }
        if (needCalibration) {
            compassButton.setColorFilter(getColor(R.color.amber));
        } else {
            compassButton.clearColorFilter();
        }
        compassButton.setContentDescription(getString(needCalibration
                ? R.string.cd_compass_warn : R.string.cd_compass));
    }

    private void maybePromptCalibration(boolean returning, boolean needCalibration) {
        if (!returning) {
            calibrationPromptShown = false;
            return;
        }
        if (!needCalibration || calibrationPromptShown) {
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            return;
        }
        calibrationPromptShown = true;
        showDialog(getString(R.string.calibration_title),
                getString(R.string.calibration_message),
                getString(R.string.calibration_go),
                getString(R.string.calibration_later),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        startActivity(new Intent(MainActivity.this, CompassActivity.class));
                    }
                });
    }

    private void updateKeepScreen() {
        boolean on = service != null && state != null && state.tracking && settings.keepAwake();
        if (on == keepScreenOn) {
            return;
        }
        keepScreenOn = on;
        Window window = getWindow();
        // Undo any brightness override left by an older build; the display
        // stays at the brightness the user chose in system settings.
        WindowManager.LayoutParams params = window.getAttributes();
        if (params.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            window.setAttributes(params);
        }
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void onPrimary() {
        if (state != null && state.tracking) {
            if (service == null) {
                return;
            }
            if (state.returning) {
                service.stopReturn();
            } else {
                service.beginReturn();
            }
            return;
        }
        startWalk();
    }

    private void startWalk() {
        if (!hasPermission()) {
            pendingStart = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (!locationEnabled()) {
            showLocationHelp();
            return;
        }
        beginTracking();
    }

    private void beginTracking() {
        pendingStart = false;
        startServiceAction(TrackingService.ACTION_START);
    }

    private void startServiceAction(String action) {
        Intent intent = new Intent(this, TrackingService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            showDialog(getString(R.string.error_title),
                    e.getMessage() == null ? getString(R.string.permission_denied) : e.getMessage(),
                    getString(R.string.ok), null, null);
        }
    }

    private void confirmStop() {
        if (service == null || state == null || !state.tracking) {
            return;
        }
        showDialog(getString(R.string.stop_confirm_title),
                getString(R.string.stop_confirm_message),
                getString(R.string.confirm), getString(R.string.cancel),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        if (service != null) {
                            service.stopTracking(true);
                        }
                    }
                });
    }

    private void maybeRestore() {
        if (restorePrompted || service == null || state == null || state.tracking) {
            return;
        }
        TrackSession active = TrackStore.loadActive(this);
        if (active == null || !active.active) {
            return;
        }
        if (active.points.isEmpty()) {
            service.discardUnfinished();
            return;
        }
        restorePrompted = true;
        showDialog(getString(R.string.restore_title), getString(R.string.restore_message),
                getString(R.string.restore_continue), getString(R.string.restore_discard),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        startServiceAction(TrackingService.ACTION_RESUME);
                    }

                    @Override
                    public void onNegative() {
                        if (service != null) {
                            service.discardUnfinished();
                        } else {
                            TrackStore.clearActive(MainActivity.this);
                        }
                    }
                });
        if (dialog != null) {
            // Back must not silently discard a recorded walk; make the user
            // choose Continue or Discard explicitly.
            dialog.setCancelable(false);
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean locationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && hasPermission();
        pendingStart = false;
        if (granted) {
            startWalk();
        } else {
            showPermissionHelp();
        }
    }

    private void showPermissionHelp() {
        pendingStart = true;
        showDialog(getString(R.string.permission_title), getString(R.string.permission_message),
                getString(R.string.permission_grant), getString(R.string.cancel),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        awaitingPermissionSettings = true;
                        openAppSettings();
                    }

                    @Override
                    public void onNegative() {
                        pendingStart = false;
                    }
                });
    }

    private void showLocationHelp() {
        pendingStart = true;
        showDialog(getString(R.string.location_disabled_title),
                getString(R.string.location_disabled_message),
                getString(R.string.location_settings), getString(R.string.start_anyway),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        awaitingLocationSettings = true;
                        openLocationSettings();
                    }

                    @Override
                    public void onNegative() {
                        pendingStart = false;
                        beginTracking();
                    }
                });
        if (dialog != null) {
            // A back press must not silently choose "start anyway".
            dialog.setCancelable(false);
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            awaitingPermissionSettings = false;
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception e) {
            awaitingLocationSettings = false;
            showLocationHelp();
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, REQ_SETTINGS);
    }

    private void showDialog(String title, String message, String positive,
                            String negative, WatchDialog.OnChoice choice) {
        if (isFinishing()) {
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        dialog = WatchDialog.show(this, title, message, positive, negative, choice);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (state != null && state.tracking) {
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }
}

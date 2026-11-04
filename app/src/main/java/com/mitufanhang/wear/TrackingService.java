package com.mitufanhang.wear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns GPS recording, the wake locks, the compass and the return guidance.
 * The activity only renders {@link State} snapshots, so tracking survives the
 * activity being paused or recreated.
 */
public final class TrackingService extends Service implements LocationListener {
    private static final String TAG = "TrackingService";

    public static final String ACTION_START = "com.mitufanhang.wear.START";
    public static final String ACTION_RESUME = "com.mitufanhang.wear.RESUME";
    public static final String ACTION_STOP = "com.mitufanhang.wear.STOP";

    private static final String CHANNEL_ID = "tracking";
    private static final int NOTIFICATION_ID = 42;
    private static final long SAVE_INTERVAL_MS = 20000L;
    private static final long TICK_MS = 1000L;

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private HeadingProvider headingProvider;
    private AppSettings settings;
    private Vibrator vibrator;
    private PowerManager.WakeLock partialWakeLock;
    private PowerManager.WakeLock screenWakeLock;

    private TrackSession session;
    private boolean tracking;
    private boolean returning;
    private boolean arrived;
    private boolean offCourse;
    private boolean hasFix;
    private boolean hasFixEver;
    private double currentLat;
    private double currentLon;
    private float currentAccuracy;
    private float lastGpsBearing;
    private long lastFixAt;
    private long lastSaveAt;
    private long lastNotificationAt;
    private long lastOffCourseAt;
    private float bestDistanceToStart = Float.MAX_VALUE;
    private double totalDistance;
    private long tickCount;
    private boolean gpsRegistered;
    private Listener listener;

    // Test mode: the self-test screen feeds synthetic Location objects through
    // the real service pipeline, but nothing is written to the track store.
    private boolean demoMode;
    private boolean demoHasHeading;
    private float demoHeading;

    public interface Listener {
        void onStateChanged(State state);

        void onTrackFinished(State state);

        void onError(String message);
    }

    public final class LocalBinder extends Binder {
        public TrackingService getService() {
            return TrackingService.this;
        }
    }

    /** Immutable snapshot handed to the UI. */
    public static final class State {
        public final boolean tracking;
        public final boolean returning;
        public final boolean arrived;
        public final boolean offCourse;
        public final boolean hasFix;
        public final boolean hasHeading;
        public final boolean headingNeedsCalibration;
        public final long elapsedMs;
        public final long gpsAgeMs;
        public final double lat;
        public final double lon;
        public final float totalDistance;
        public final float distanceToStart;
        public final float bearingToStart;
        public final float heading;
        public final float accuracy;
        public final int pointCount;
        public final List<TrackPoint> trail;

        State(boolean tracking, boolean returning, boolean arrived, boolean offCourse,
              boolean hasFix, boolean hasHeading, boolean headingNeedsCalibration,
              long elapsedMs, long gpsAgeMs,
              double lat, double lon, float totalDistance, float distanceToStart,
              float bearingToStart, float heading, float accuracy, int pointCount,
              List<TrackPoint> trail) {
            this.tracking = tracking;
            this.returning = returning;
            this.arrived = arrived;
            this.offCourse = offCourse;
            this.hasFix = hasFix;
            this.hasHeading = hasHeading;
            this.headingNeedsCalibration = headingNeedsCalibration;
            this.elapsedMs = elapsedMs;
            this.gpsAgeMs = gpsAgeMs;
            this.lat = lat;
            this.lon = lon;
            this.totalDistance = totalDistance;
            this.distanceToStart = distanceToStart;
            this.bearingToStart = bearingToStart;
            this.heading = heading;
            this.accuracy = accuracy;
            this.pointCount = pointCount;
            this.trail = trail;
        }

        public boolean hasTrack() {
            return pointCount > 0;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        headingProvider = new HeadingProvider(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannel();

        // Show the previous track as a preview without starting the GPS.
        TrackSession active = TrackStore.loadActive(this);
        session = active != null ? active : TrackStore.loadLast(this);
        if (session != null) {
            totalDistance = session.distanceMeters();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();

        if (intent == null) {
            // The system restarted us after a process kill. Continue an unfinished walk.
            TrackSession active = TrackStore.loadActive(this);
            if (active != null && active.active) {
                beginTracking(active, true);
            } else {
                stopForeground(true);
                stopSelf();
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking(true);
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            TrackSession active = TrackStore.loadActive(this);
            if (active == null) {
                notifyError("没有可恢复的轨迹");
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            beginTracking(active, true);
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            beginTracking(null, false);
            return START_STICKY;
        }
        return START_STICKY;
    }

    private void beginTracking(TrackSession restored, boolean resume) {
        if (tracking) {
            notifyState();
            return;
        }
        if (!demoMode && !hasLocationPermission()) {
            notifyError("缺少定位权限");
            stopForeground(true);
            stopSelf();
            return;
        }
        if (restored != null) {
            session = restored;
        } else {
            session = new TrackSession(System.currentTimeMillis());
        }
        session.active = true;
        session.endedAt = 0;
        returning = session.returning;
        arrived = false;
        offCourse = false;
        totalDistance = session.distanceMeters();
        bestDistanceToStart = Float.MAX_VALUE;
        hasFix = false;
        hasFixEver = false;
        currentLat = 0;
        currentLon = 0;
        lastFixAt = 0;
        lastSaveAt = SystemClock.elapsedRealtime();
        lastNotificationAt = 0;
        lastOffCourseAt = 0;
        tracking = true;

        headingProvider.start();
        headingProvider.setLocation(session.points.isEmpty() ? 0 : session.first().lat,
                session.points.isEmpty() ? 0 : session.first().lon, 0);
        acquireWakeLocks();
        if (!demoMode) {
            requestLocationUpdates();
            if (!tracking) {
                // requestLocationUpdates can stop us when permission was revoked.
                return;
            }
        }
        startTicker();
        ensureForeground();
        if (!demoMode) {
            TrackStore.saveActive(this, session);
        }
        notifyState();
    }

    private void requestLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        // Re-registering after a provider toggle must not leave a duplicate
        // listener behind, otherwise updates arrive twice.
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        int interval = settings.intervalMs();
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        interval, 1.0f, this, Looper.getMainLooper());
                gpsRegistered = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        Math.max(interval, 5000), 10.0f, this, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            notifyError("定位权限被拒绝");
            stopTracking(false);
        } catch (Exception e) {
            Log.w(TAG, "location request failed", e);
        }
    }

    private void removeLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        gpsRegistered = false;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!tracking || location == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (location.getTime() > 0 && now - location.getTime() > 30000) {
            return;
        }
        if (!location.hasAccuracy()) {
            return;
        }
        float accuracy = location.getAccuracy();
        if (accuracy > 100f) {
            return;
        }

        // Ignore a single wild fix instead of letting the map dot teleport
        // away from the recorded trail.
        TrackPoint previousPoint = session.last();
        if (previousPoint != null) {
            double jump = Geo.distance(previousPoint.lat, previousPoint.lon,
                    location.getLatitude(), location.getLongitude());
            if (jump > 250.0 && now - previousPoint.time < 30000L) {
                return;
            }
        }

        boolean firstFix = !hasFixEver;
        hasFix = true;
        hasFixEver = true;
        currentLat = location.getLatitude();
        currentLon = location.getLongitude();
        currentAccuracy = accuracy;
        lastFixAt = now;
        if (location.hasBearing()) {
            lastGpsBearing = location.getBearing();
            if (demoMode) {
                demoHeading = location.getBearing();
                demoHasHeading = true;
            }
        }
        headingProvider.setLocation(currentLat, currentLon, location.hasAltitude() ? location.getAltitude() : 0);

        TrackPoint last = session.last();
        boolean addPoint = last == null;
        if (!addPoint) {
            double d = Geo.distance(last.lat, last.lon, currentLat, currentLon);
            long dt = now - last.time;
            addPoint = d >= 2.0 || dt >= 5000;
            if (d > 250 && dt < 30000) {
                addPoint = false;
            }
        }
        if (addPoint) {
            TrackPoint point = new TrackPoint(currentLat, currentLon, now, accuracy,
                    location.hasSpeed() ? location.getSpeed() : 0f,
                    location.hasBearing() ? location.getBearing() : lastGpsBearing);
            int before = session.points.size();
            session.addPoint(point);
            if (session.points.size() > before) {
                if (before > 0) {
                    TrackPoint previous = session.points.get(before - 1);
                    double segment = Geo.distance(previous.lat, previous.lon, point.lat, point.lon);
                    if (segment < 250) {
                        totalDistance += segment;
                    }
                }
            }
            if (!demoMode && (firstFix || session.points.size() % 8 == 0
                    || SystemClock.elapsedRealtime() - lastSaveAt > SAVE_INTERVAL_MS)) {
                TrackStore.saveActive(this, session);
                lastSaveAt = SystemClock.elapsedRealtime();
            }
        }
        notifyState();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (tracking && !demoMode) {
            requestLocationUpdates();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (tracking) {
            hasFix = false;
            notifyState();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    public void beginReturn() {
        if (!tracking) {
            return;
        }
        if (session == null || session.points.size() < 2) {
            notifyError("轨迹点不足，请先走动一段距离再开始返航");
            return;
        }
        returning = true;
        session.returning = true;
        arrived = false;
        offCourse = false;
        bestDistanceToStart = distanceToStart();
        if (!demoMode) {
            TrackStore.saveActive(this, session);
        }
        vibrate(new long[]{0, 40, 80, 40});
        updateNotification(true);
        notifyState();
    }

    public void stopReturn() {
        if (!tracking) {
            return;
        }
        returning = false;
        session.returning = false;
        arrived = false;
        offCourse = false;
        if (!demoMode) {
            TrackStore.saveActive(this, session);
        }
        updateNotification(true);
        notifyState();
    }

    public void stopTracking(boolean saveCompleted) {
        if (!tracking) {
            stopForeground(true);
            stopSelf();
            return;
        }
        boolean wasDemo = demoMode;
        tracking = false;
        returning = false;
        arrived = false;
        offCourse = false;
        removeLocationUpdates();
        releaseWakeLocks();
        headingProvider.stop();
        handler.removeCallbacksAndMessages(null);
        if (session != null) {
            session.endedAt = System.currentTimeMillis();
            if (wasDemo) {
                // The synthetic walk must not replace a real unfinished track.
                session.active = false;
            } else if (saveCompleted) {
                session.active = false;
                TrackStore.saveCompleted(this, session);
            } else {
                TrackStore.saveActive(this, session);
            }
        }
        State state = buildState();
        demoMode = false;
        demoHasHeading = false;
        if (listener != null) {
            listener.onTrackFinished(state);
        }
        stopForeground(true);
        stopSelf();
    }

    public void discardUnfinished() {
        if (tracking) {
            return;
        }
        TrackStore.clearActive(this);
        session = TrackStore.loadLast(this);
        totalDistance = session == null ? 0 : session.distanceMeters();
        notifyState();
    }

    public void refreshSettings() {
        if (!tracking) {
            return;
        }
        if (!demoMode) {
            removeLocationUpdates();
            requestLocationUpdates();
        }
        if (settings.keepAwake()) {
            acquireScreenWakeLock();
        } else {
            releaseScreenWakeLock();
        }
        notifyState();
    }

    /**
     * Starts a synthetic recording session used by the self-test screen. It
     * reuses the real tracking pipeline, but never reads GPS and never writes
     * to the track store. Returns false when a real track is already active.
     */
    public boolean startDemo() {
        if (tracking) {
            return false;
        }
        if (session != null && session.active) {
            return false;
        }
        demoMode = true;
        demoHasHeading = false;
        demoHeading = 0f;
        try {
            beginTracking(null, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "demo start failed", e);
            stopTracking(false);
            demoMode = false;
            notifyError("测试演示启动失败");
            return false;
        }
        if (!tracking) {
            demoMode = false;
        }
        return tracking;
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    public State getState() {
        return buildState();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onStateChanged(buildState());
        }
    }

    private State buildState() {
        TrackPoint first = session == null ? null : session.first();
        TrackPoint last = session == null ? null : session.last();
        long now = System.currentTimeMillis();
        long elapsed = session == null ? 0 : session.durationMs(now);
        boolean live = tracking && hasFix;
        double lat = live ? currentLat : (last != null ? last.lat : 0);
        double lon = live ? currentLon : (last != null ? last.lon : 0);
        float dStart = 0;
        if (first != null && (live || last != null)) {
            dStart = (float) Geo.distance(lat, lon, first.lat, first.lon);
        }
        float bearingToStart = 0;
        if (first != null && dStart > 0.5) {
            bearingToStart = (float) Geo.bearing(lat, lon, first.lat, first.lon);
        }
        boolean headingOk = tracking && headingProvider != null && headingProvider.hasHeading();
        float heading = headingOk ? headingProvider.getHeading() : 0;
        if (!headingOk && live && lastGpsBearing > 0) {
            heading = lastGpsBearing;
            headingOk = true;
        }
        if (demoMode && demoHasHeading) {
            heading = demoHeading;
            headingOk = true;
        }
        boolean headingNeedsCalibration = !demoMode && tracking && headingProvider != null
                && headingProvider.hasCompassSensor() && headingProvider.needsCalibration();
        long gpsAge = lastFixAt == 0 ? Long.MAX_VALUE : Math.max(0, now - lastFixAt);
        List<TrackPoint> copy = session == null ? new ArrayList<TrackPoint>()
                : TrackSession.copyOf(session.points);
        return new State(tracking, returning, arrived, offCourse, live, headingOk,
                headingNeedsCalibration, elapsed, gpsAge, lat, lon, (float) totalDistance, dStart, bearingToStart,
                heading, currentAccuracy, session == null ? 0 : session.points.size(), copy);
    }

    private float distanceToStart() {
        if (session == null || session.points.isEmpty()) {
            return 0;
        }
        TrackPoint first = session.first();
        TrackPoint last = session.last();
        double lat = hasFix ? currentLat : last.lat;
        double lon = hasFix ? currentLon : last.lon;
        return (float) Geo.distance(lat, lon, first.lat, first.lon);
    }

    private void startTicker() {
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!tracking) {
                    return;
                }
                tickCount++;
                updateGuidance();
                notifyState();
                if (tickCount % 4 == 0) {
                    updateNotification(false);
                }
                handler.postDelayed(this, TICK_MS);
            }
        });
    }

    private void updateGuidance() {
        if (!returning || session == null || session.points.size() < 2) {
            return;
        }
        float d = distanceToStart();
        if (d < bestDistanceToStart) {
            bestDistanceToStart = d;
        }
        if (!arrived && d <= 12f) {
            arrived = true;
            offCourse = false;
            vibrate(new long[]{0, 90, 100, 90, 100, 180});
            updateNotification(true);
        } else if (arrived && d > 25f) {
            arrived = false;
        }
        long now = System.currentTimeMillis();
        if (!arrived && d > 40f && d > bestDistanceToStart + 25f
                && now - lastOffCourseAt > 45000L) {
            offCourse = true;
            lastOffCourseAt = now;
            vibrate(new long[]{0, 60, 80, 60});
        }
        if (offCourse && d <= bestDistanceToStart + 10f) {
            offCourse = false;
        }
    }

    private void notifyState() {
        Listener l = listener;
        if (l != null) {
            l.onStateChanged(buildState());
        }
    }

    private void notifyError(String message) {
        Listener l = listener;
        if (l != null) {
            l.onError(message);
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void acquireWakeLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        if (partialWakeLock == null) {
            partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mitu:tracking");
            partialWakeLock.setReferenceCounted(false);
        }
        try {
            if (!partialWakeLock.isHeld()) {
                partialWakeLock.acquire(8 * 60 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.w(TAG, "partial wake lock failed", e);
        }
        if (settings.keepAwake()) {
            acquireScreenWakeLock();
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        try {
            screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "mitu:screen");
            screenWakeLock.setReferenceCounted(false);
            screenWakeLock.acquire(8 * 60 * 60 * 1000L);
        } catch (Exception e) {
            Log.w(TAG, "screen wake lock failed", e);
        }
    }

    private void releaseWakeLocks() {
        releaseScreenWakeLock();
        if (partialWakeLock != null && partialWakeLock.isHeld()) {
            try {
                partialWakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            try {
                screenWakeLock.release();
            } catch (Exception ignored) {
            }
        }
        screenWakeLock = null;
    }

    private void vibrate(long[] pattern) {
        if (!settings.vibrate() || vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotificationAt < 1500L) {
            return;
        }
        lastNotificationAt = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, TrackingService.class).setAction(ACTION_STOP);
        PendingIntent stop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "迷途返航";
        String text = "点按打开";
        if (tracking) {
            if (returning) {
                title = arrived ? "已回到起点" : "正在返航";
                text = session == null ? "返航中" : "距起点 " + Geo.formatDistance(distanceToStart())
                        + " · " + Geo.formatDuration(session.durationMs(System.currentTimeMillis()));
            } else {
                title = "正在记录轨迹";
                text = Geo.formatDistance(totalDistance) + " · "
                        + Geo.formatDuration(session == null ? 0
                        : session.durationMs(System.currentTimeMillis()));
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(0xFF57E6A8);
        if (tracking) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "结束", stop).build());
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "轨迹记录", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("记录轨迹与返航时显示常驻通知");
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (tracking && session != null && !demoMode) {
            session.active = true;
            TrackStore.saveActive(this, session);
        }
        tracking = false;
        demoMode = false;
        demoHasHeading = false;
        removeLocationUpdates();
        releaseWakeLocks();
        if (headingProvider != null) {
            headingProvider.stop();
        }
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (session != null && tracking && !demoMode) {
            TrackStore.saveActive(this, session);
        }
        super.onTaskRemoved(rootIntent);
    }
}

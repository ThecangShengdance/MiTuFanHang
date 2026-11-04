package com.mitufanhang.wear;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * On-watch diagnostics plus a fully synthetic walk. The demo deliberately
 * feeds fake {@link Location} objects into {@link TrackingService}, so the
 * real recording/return pipeline and the real map views are exercised without
 * touching GPS or the saved track store.
 */
public final class SelfTestActivity extends Activity implements TrackingService.Listener {

    private static final int REQ_PERMISSION = 51;
    private static final long PROBE_MS = 2600L;
    private static final long DEMO_STEP_MS = 650L;
    private static final int OUTBOUND_STEPS = 18;

    // Somewhere in Beijing; the demo never claims to be a real position.
    private static final double DEMO_START_LAT = 39.9042;
    private static final double DEMO_START_LON = 116.4074;

    private static final int PASS = 0;
    private static final int WARN = 1;
    private static final int FAIL = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Check> checks = new ArrayList<>();

    private AppSettings settings;
    private boolean round;
    private int side;
    private WatchFrameLayout root;
    private FrameLayout testScreen;
    private FrameLayout demoScreen;
    private LinearLayout headerBox;
    private LinearLayout checksContainer;
    private LinearLayout buttonRow;
    private TextView summaryText;
    private ScrollView checksScroll;
    private Button rerunButton;
    private Button demoButton;
    private TrackMapView demoMap;
    private GuideView demoGuide;
    private LinearLayout demoTopBox;
    private TextView demoStatusText;
    private TextView demoHintText;
    private android.app.Dialog dialog;

    private HeadingProvider probeHeading;
    private Runnable probeDone;
    private Check headingCheck;
    private Check demoCheck;
    private boolean checking;
    private boolean rerunRequested;

    private TrackingService service;
    private boolean bound;
    private boolean demoStarting;
    private boolean demoRunning;
    private boolean demoReturning;
    private boolean demoArrived;
    private int demoStep;
    private double demoLat;
    private double demoLon;
    private TrackingService.State lastDemoState;

    private int demoStatus = WARN;
    private String demoDetail = "未运行，点下方按钮开始";

    private static final class Check {
        final String name;
        int status;
        String detail;

        Check(String name, int status, String detail) {
            this.name = name;
            this.status = status;
            this.detail = detail;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            demoStarting = false;
            bound = true;
            try {
                service = ((TrackingService.LocalBinder) binder).getService();
                TrackingService.State state = service.getState();
                if (state != null && state.tracking) {
                    showBusy();
                    unbindQuietly();
                    return;
                }
                service.setListener(SelfTestActivity.this);
                if (!service.startDemo()) {
                    service.setListener(null);
                    showDemoFailed();
                    unbindQuietly();
                    return;
                }
                demoRunning = true;
                showDemoScreen();
                startFakeWalk();
            } catch (RuntimeException e) {
                showDemoFailed();
                unbindQuietly();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            if (demoRunning) {
                demoRunning = false;
                if (demoCheck != null) {
                    demoCheck.status = WARN;
                    demoCheck.detail = "服务连接中断";
                }
                closeDemoUi();
                showDialog(getString(R.string.self_test_demo_failed),
                        getString(R.string.self_test_demo_failed_message),
                        getString(R.string.ok), null, null);
            }
        }
    };

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            if (!demoRunning || service == null) {
                return;
            }
            if (!demoReturning) {
                if (demoStep > OUTBOUND_STEPS) {
                    demoReturning = true;
                    service.beginReturn();
                    handler.postDelayed(this, DEMO_STEP_MS);
                    return;
                }
                double east = Math.min(demoStep, 10) * 7.0;
                double north = Math.max(0, demoStep - 10) * 6.0;
                double lat = DEMO_START_LAT + north / 111320.0;
                double lon = DEMO_START_LON
                        + east / (111320.0 * Math.cos(Math.toRadians(DEMO_START_LAT)));
                float bearing = demoStep == 0 ? 0f
                        : (float) Geo.bearing(demoLat, demoLon, lat, lon);
                demoStep++;
                demoLat = lat;
                demoLon = lon;
                sendDemoFix(lat, lon, bearing);
                handler.postDelayed(this, DEMO_STEP_MS);
                return;
            }

            if (demoArrived) {
                return;
            }
            float distance = (float) Geo.distance(demoLat, demoLon,
                    DEMO_START_LAT, DEMO_START_LON);
            if (distance <= 12f) {
                // The service's one-second guidance tick decides "arrived".
                handler.postDelayed(this, 500L);
                return;
            }
            float bearing = (float) Geo.bearing(demoLat, demoLon,
                    DEMO_START_LAT, DEMO_START_LON);
            double step = Math.min(10.0, distance - 4.0);
            if (step < 3.0) {
                step = 3.0;
            }
            double[] next = offset(demoLat, demoLon, bearing, step);
            demoLat = next[0];
            demoLon = next[1];
            sendDemoFix(demoLat, demoLon, bearing);
            handler.postDelayed(this, DEMO_STEP_MS);
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
                if (right - left != oldRight - oldLeft
                        || bottom - top != oldBottom - oldTop) {
                    applyGeometry();
                }
            }
        });
        applyGeometry();
        runChecks();
        if (!hasFineLocation()) {
            requestLocationPermission();
        }
    }

    private void buildUi() {
        root = new WatchFrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));
        buildTestScreen();
        buildDemoScreen();
    }

    private void buildTestScreen() {
        testScreen = new FrameLayout(this);
        root.addView(testScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setGravity(Gravity.CENTER_HORIZONTAL);
        headerBox.setPadding(0, dp(2f), 0, dp(2f));

        TextView title = new TextView(this);
        title.setText(R.string.self_test_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        headerBox.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summaryText = new TextView(this);
        summaryText.setText(R.string.self_test_checking);
        summaryText.setTextColor(getColor(R.color.text_secondary));
        summaryText.setTextSize(9.5f);
        summaryText.setGravity(Gravity.CENTER);
        summaryText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(3f);
        headerBox.addView(summaryText, summaryParams);

        testScreen.addView(headerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        checksScroll = new ScrollView(this);
        checksScroll.setVerticalScrollBarEnabled(false);
        checksScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        checksScroll.setClipToPadding(false);
        checksScroll.setBackgroundResource(R.drawable.bg_chip);

        checksContainer = new LinearLayout(this);
        checksContainer.setOrientation(LinearLayout.VERTICAL);
        checksContainer.setPadding(dp(5f), dp(4f), dp(5f), dp(4f));
        checksScroll.addView(checksContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        testScreen.addView(checksScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        rerunButton = makeButton(R.drawable.bg_secondary, getColor(R.color.text_primary));
        rerunButton.setText(R.string.self_test_rerun);
        rerunButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runChecks();
            }
        });
        LinearLayout.LayoutParams rerunParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f);
        rerunParams.setMargins(dp(2f), 0, dp(2f), 0);
        buttonRow.addView(rerunButton, rerunParams);

        demoButton = makeButton(R.drawable.bg_primary, getColor(R.color.black));
        demoButton.setText(R.string.self_test_run_demo);
        demoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDemo();
            }
        });
        LinearLayout.LayoutParams demoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f);
        demoParams.setMargins(dp(2f), 0, dp(2f), 0);
        buttonRow.addView(demoButton, demoParams);
        testScreen.addView(buttonRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)));
    }

    private void buildDemoScreen() {
        demoScreen = new FrameLayout(this);
        demoScreen.setVisibility(View.GONE);
        demoScreen.setBackgroundColor(getColor(R.color.black));

        demoMap = new TrackMapView(this);
        demoMap.setContentDescription(getString(R.string.cd_map));
        demoScreen.addView(demoMap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        demoGuide = new GuideView(this);
        demoScreen.addView(demoGuide, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        demoTopBox = new LinearLayout(this);
        demoTopBox.setOrientation(LinearLayout.VERTICAL);
        demoTopBox.setGravity(Gravity.CENTER_HORIZONTAL);
        demoTopBox.setBackgroundResource(R.drawable.bg_chip);
        demoTopBox.setPadding(dp(8f), dp(5f), dp(8f), dp(5f));

        demoStatusText = new TextView(this);
        demoStatusText.setText(R.string.self_test_demo_prepare);
        demoStatusText.setTextColor(getColor(R.color.text_primary));
        demoStatusText.setTextSize(11f);
        demoStatusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        demoStatusText.setGravity(Gravity.CENTER);
        demoStatusText.setIncludeFontPadding(false);
        demoTopBox.addView(demoStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        demoHintText = new TextView(this);
        demoHintText.setText(R.string.self_test_demo_hint);
        demoHintText.setTextColor(getColor(R.color.text_secondary));
        demoHintText.setTextSize(8.5f);
        demoHintText.setGravity(Gravity.CENTER);
        demoHintText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(2f);
        demoTopBox.addView(demoHintText, hintParams);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        demoScreen.addView(demoTopBox, topParams);
        root.addView(demoScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void applyGeometry() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        round = settings.resolveRound(getResources());
        root.setRound(round);
        side = Math.min(w, h);

        FrameLayout.LayoutParams headerParams =
                (FrameLayout.LayoutParams) headerBox.getLayoutParams();
        headerParams.width = round ? Math.round(side * 0.74f) : w - dp(16f);
        headerParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        headerParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        headerParams.topMargin = round ? Math.round(side * 0.07f) : dp(8f);
        headerBox.setLayoutParams(headerParams);

        int top = round ? Math.round(side * 0.25f) : dp(50f);
        int bottom = round ? Math.round(side * 0.32f) : dp(56f);
        FrameLayout.LayoutParams scrollParams =
                (FrameLayout.LayoutParams) checksScroll.getLayoutParams();
        scrollParams.width = round ? Math.round(side * 0.70f) : w - dp(20f);
        scrollParams.height = Math.max(dp(60f), h - top - bottom);
        scrollParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        scrollParams.topMargin = top;
        scrollParams.bottomMargin = 0;
        checksScroll.setLayoutParams(scrollParams);

        FrameLayout.LayoutParams buttonParams =
                (FrameLayout.LayoutParams) buttonRow.getLayoutParams();
        buttonParams.width = round ? Math.round(side * 0.72f) : w - dp(12f);
        buttonParams.height = dp(36f);
        buttonParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        buttonParams.bottomMargin = round ? Math.round(side * 0.16f) : dp(10f);
        buttonRow.setLayoutParams(buttonParams);

        FrameLayout.LayoutParams demoTopParams =
                (FrameLayout.LayoutParams) demoTopBox.getLayoutParams();
        demoTopParams.width = round ? Math.round(side * 0.70f) : w - dp(16f);
        demoTopParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        demoTopParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        demoTopParams.topMargin = round ? Math.round(side * 0.085f) : dp(8f);
        demoTopBox.setLayoutParams(demoTopParams);
    }

    private Button makeButton(int background, int textColor) {
        Button button = new Button(this);
        button.setTextSize(11f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setBackgroundResource(background);
        button.setTextColor(textColor);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    // ---------------------------------------------------------------- checks

    private void runChecks() {
        if (checking) {
            rerunRequested = true;
            return;
        }
        if (probeHeading != null) {
            probeHeading.stop();
        }
        if (probeDone != null) {
            handler.removeCallbacks(probeDone);
        }
        checking = true;
        checks.clear();

        addPermissionCheck();
        addLocationChecks();
        addSensorChecks();
        addDeviceChecks();

        headingCheck = addCheck("指南针读数", WARN, "正在读取…");
        demoCheck = addCheck("轨迹与返航演示", demoStatus, demoDetail);
        renderChecks();

        probeHeading = new HeadingProvider(this);
        probeHeading.setLocation(DEMO_START_LAT, DEMO_START_LON, 0);
        probeHeading.start();

        probeDone = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                float heading = probeHeading == null ? Float.NaN : probeHeading.getHeading();
                boolean hasCompass = probeHeading != null && probeHeading.hasCompassSensor();
                boolean reliable = probeHeading != null && probeHeading.isReliable();
                boolean ok = !Float.isNaN(heading);
                if (probeHeading != null) {
                    probeHeading.stop();
                }
                headingCheck.status = ok && reliable ? PASS : WARN;
                if (!hasCompass) {
                    headingCheck.detail = "此设备无磁力计，静止时无法测向；移动时可用 GPS 航向";
                } else if (!ok) {
                    headingCheck.detail = "2.6 秒内没有航向数据，请到户外或按 8 字校准";
                } else {
                    headingCheck.detail = "当前航向 " + Math.round(heading) + "°（真北）"
                            + (reliable ? "" : " · 精度低，建议 8 字校准");
                }
                checking = false;
                renderChecks();
                if (rerunRequested) {
                    rerunRequested = false;
                    runChecks();
                }
            }
        };
        handler.postDelayed(probeDone, PROBE_MS);
    }

    private void addPermissionCheck() {
        if (hasFineLocation()) {
            addCheck("定位权限", PASS, "已授予精确位置");
        } else if (hasCoarseLocation()) {
            addCheck("定位权限", WARN, "仅有大致位置，真实 GPS 轨迹会受限");
        } else {
            addCheck("定位权限", FAIL, "未授予定位权限，请点“重新检测”后授权");
        }
    }

    private void addLocationChecks() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            addCheck("定位开关", FAIL, "系统没有 LocationManager");
            addCheck("GPS 模块", FAIL, "系统没有 LocationManager");
            return;
        }
        boolean gps;
        boolean gpsOn;
        boolean net;
        boolean netOn;
        try {
            gps = lm.getAllProviders().contains(LocationManager.GPS_PROVIDER);
            gpsOn = gps && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            net = lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER);
            netOn = net && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            addCheck("定位开关", WARN, "无法读取定位状态");
            addCheck("GPS 模块", WARN, "无法读取 GPS 状态");
            return;
        }

        boolean locationEnabled;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationEnabled = lm.isLocationEnabled();
        } else {
            locationEnabled = gpsOn || netOn;
        }
        addCheck("定位开关", locationEnabled ? PASS : WARN,
                locationEnabled ? "系统定位已开启" : "系统定位已关闭，请打开");

        if (!gps) {
            addCheck("GPS 模块", FAIL, "设备未提供 GPS provider");
        } else if (gpsOn) {
            addCheck("GPS 模块", PASS, "GPS 已启用" + (netOn ? " · 网络定位可用" : ""));
        } else {
            addCheck("GPS 模块", WARN, "GPS 存在但未启用，请到户外空旷处");
        }
    }

    private void addSensorChecks() {
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            addCheck("指南针传感器", FAIL, "系统没有 SensorManager");
            addCheck("运动传感器", FAIL, "系统没有 SensorManager");
            return;
        }
        Sensor rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        if (rotation != null) {
            addCheck("指南针传感器", PASS, "旋转矢量传感器");
        } else if (accel != null && magnet != null) {
            addCheck("指南针传感器", PASS, "加速度计 + 磁力计（回退方案）");
        } else if (magnet == null && accel != null) {
            addCheck("指南针传感器", WARN, "此设备无磁力计 · 静止时无法测向；移动时可用 GPS 航向");
        } else if (accel != null || magnet != null) {
            addCheck("指南针传感器", WARN, "只有部分指南针传感器，航向可能不稳定");
        } else {
            addCheck("指南针传感器", FAIL, "未找到旋转矢量、加速度计或磁力计");
        }

        if (accel != null) {
            addCheck("运动传感器", PASS, "加速度计可用");
        } else {
            addCheck("运动传感器", FAIL, "未找到加速度计");
        }
    }

    private void addDeviceChecks() {
        addVibrationCheck();
        addScreenCheck();
        addStorageCheck();
        addWakeLockCheck();
        addNotificationCheck();
        addServiceCheck();
        addSystemCheck();
    }

    private void addVibrationCheck() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            addCheck("震动马达", FAIL, "设备没有震动马达");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(35);
            }
            addCheck("震动马达", PASS, "已发出测试震动");
        } catch (Exception e) {
            addCheck("震动马达", WARN, "马达存在但无法震动");
        }
    }

    private void addScreenCheck() {
        Configuration config = getResources().getConfiguration();
        boolean isRound = config.isScreenRound();
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        addCheck("屏幕布局", PASS,
                (isRound ? "圆形" : "方形") + " " + w + "×" + h);
    }

    private void addStorageCheck() {
        File file = new File(getFilesDir(), "selftest.tmp");
        FileOutputStream out = null;
        FileInputStream in = null;
        try {
            out = new FileOutputStream(file);
            out.write("ok".getBytes("UTF-8"));
            out.close();
            out = null;
            in = new FileInputStream(file);
            int value = in.read();
            if (value == 'o') {
                addCheck("内部存储", PASS, "可读写应用私有目录");
            } else {
                addCheck("内部存储", WARN, "写入后读取内容不一致");
            }
        } catch (Exception e) {
            addCheck("内部存储", FAIL, "无法读写应用私有目录");
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (file.exists()) {
                file.delete();
            }
        }
    }

    private void addWakeLockCheck() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            addCheck("唤醒锁", FAIL, "系统没有 PowerManager");
            return;
        }
        PowerManager.WakeLock lock = null;
        try {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mitu:selftest");
            lock.setReferenceCounted(false);
            lock.acquire(500L);
            if (lock.isHeld()) {
                addCheck("唤醒锁", PASS, "PARTIAL_WAKE_LOCK 可以获取");
            } else {
                addCheck("唤醒锁", WARN, "唤醒锁没有保持住");
            }
        } catch (Exception e) {
            addCheck("唤醒锁", FAIL, "无法获取 PARTIAL_WAKE_LOCK");
        } finally {
            if (lock != null && lock.isHeld()) {
                try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addNotificationCheck() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            addCheck("通知权限", FAIL, "系统没有 NotificationManager");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            addCheck("通知权限", WARN, "通知已关闭，前台服务提示可能不可见");
        } else {
            addCheck("通知权限", PASS, "通知已开启");
        }
    }

    private void addServiceCheck() {
        try {
            ServiceInfo info = getPackageManager().getServiceInfo(
                    new ComponentName(this, TrackingService.class), 0);
            if (info == null) {
                addCheck("前台服务", FAIL, "Manifest 中没有 TrackingService");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && (info.getForegroundServiceType()
                    & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) == 0) {
                addCheck("前台服务", WARN, "服务未声明 location 前台类型");
            } else {
                addCheck("前台服务", PASS, "已声明前台定位服务");
            }
        } catch (PackageManager.NameNotFoundException e) {
            addCheck("前台服务", FAIL, "Manifest 中没有 TrackingService");
        }
    }

    private void addSystemCheck() {
        String release = Build.VERSION.RELEASE;
        if (release == null || release.trim().isEmpty()) {
            release = Integer.toString(Build.VERSION.SDK_INT);
        }
        String detail = "Android " + release + "（API " + Build.VERSION.SDK_INT
                + "） · " + Build.MODEL;
        addCheck("系统版本", Build.VERSION.SDK_INT >= 25 ? PASS : FAIL, detail);
    }

    private Check addCheck(String name, int status, String detail) {
        Check check = new Check(name, status, detail);
        checks.add(check);
        return check;
    }

    private void renderChecks() {
        if (checksContainer == null) {
            return;
        }
        checksContainer.removeAllViews();
        int pass = 0;
        int warn = 0;
        int fail = 0;
        for (Check check : checks) {
            if (check.status == PASS) {
                pass++;
            } else if (check.status == WARN) {
                warn++;
            } else {
                fail++;
            }
            checksContainer.addView(checkRow(check));
        }
        if (checking) {
            summaryText.setText(R.string.self_test_checking);
            summaryText.setTextColor(getColor(R.color.text_secondary));
        } else {
            summaryText.setText(getString(R.string.self_test_summary, pass, warn, fail));
            summaryText.setTextColor(getColor(fail > 0 ? R.color.danger
                    : warn > 0 ? R.color.amber : R.color.mint));
        }
        if (rerunButton != null) {
            rerunButton.setEnabled(!checking);
        }
        if (demoButton != null) {
            demoButton.setEnabled(!checking);
        }
    }

    private View checkRow(Check check) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2f), dp(3f), dp(2f), dp(3f));

        int color = statusColor(check.status);
        View dot = new View(this);
        GradientDrawable dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(color);
        dot.setBackground(dotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7f), dp(7f));
        dotParams.rightMargin = dp(7f);
        row.addView(dot, dotParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(check.name);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(11f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setIncludeFontPadding(false);
        textColumn.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView detail = new TextView(this);
        detail.setText(check.detail);
        detail.setTextColor(getColor(R.color.text_secondary));
        detail.setTextSize(8.5f);
        detail.setMaxLines(2);
        detail.setIncludeFontPadding(false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(1f);
        textColumn.addView(detail, detailParams);
        row.addView(textColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(statusLabel(check.status));
        badge.setTextColor(color);
        badge.setTextSize(8.5f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setPadding(dp(4f), dp(1f), 0, 0);
        row.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private int statusColor(int status) {
        return getColor(status == PASS ? R.color.mint
                : status == WARN ? R.color.amber : R.color.danger);
    }

    private String statusLabel(int status) {
        return status == PASS ? "正常" : status == WARN ? "注意" : "失败";
    }

    private boolean hasFineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            runChecks();
        }
    }

    // ------------------------------------------------------------------ demo

    private void startDemo() {
        if (checking || demoRunning || demoStarting) {
            return;
        }
        if (!hasFineLocation()) {
            showDialog(getString(R.string.self_test_permission_title),
                    getString(R.string.self_test_permission_message),
                    getString(R.string.self_test_permission_grant),
                    getString(R.string.cancel),
                    new WatchDialog.OnChoice() {
                        @Override
                        public void onPositive() {
                            requestLocationPermission();
                        }
                    });
            return;
        }
        demoStarting = true;
        try {
            boolean ok = bindService(new Intent(this, TrackingService.class),
                    connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                demoStarting = false;
                showDemoFailed();
            }
        } catch (RuntimeException e) {
            demoStarting = false;
            showDemoFailed();
        }
    }

    private void showDemoScreen() {
        testScreen.setVisibility(View.GONE);
        demoScreen.setVisibility(View.VISIBLE);
        demoStatusText.setText(R.string.self_test_demo_prepare);
        demoMap.setState(null, true, round);
        demoGuide.setState(null, true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startFakeWalk() {
        demoStep = 0;
        demoReturning = false;
        demoArrived = false;
        demoLat = DEMO_START_LAT;
        demoLon = DEMO_START_LON;
        lastDemoState = null;
        handler.removeCallbacks(demoTick);
        handler.postDelayed(demoTick, 500L);
    }

    private void sendDemoFix(double lat, double lon, float bearing) {
        if (service == null) {
            return;
        }
        Location location = new Location("selftest");
        location.setLatitude(lat);
        location.setLongitude(lon);
        location.setTime(System.currentTimeMillis());
        location.setAccuracy(5f);
        location.setSpeed(1.4f);
        location.setBearing(bearing);
        location.setAltitude(20);
        service.onLocationChanged(location);
    }

    private static double[] offset(double lat, double lon, float bearing, double meters) {
        double radians = Math.toRadians(bearing);
        double dLat = Math.cos(radians) * meters / 111320.0;
        double dLon = Math.sin(radians) * meters
                / (111320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
    }

    @Override
    public void onStateChanged(TrackingService.State state) {
        if (!demoRunning || state == null) {
            return;
        }
        lastDemoState = state;
        demoMap.setState(state, true, round);
        demoGuide.setState(state, true);
        if (state.arrived) {
            demoStatusText.setText(R.string.self_test_arrived);
        } else if (state.returning) {
            demoStatusText.setText(getString(R.string.self_test_returning,
                    Geo.formatDistance(state.distanceToStart)));
        } else if (state.hasFix) {
            demoStatusText.setText(getString(R.string.self_test_recording,
                    Geo.formatDistance(state.totalDistance), state.pointCount));
        }
        if (state.arrived && demoReturning && !demoArrived) {
            demoArrived = true;
            finishDemo(true);
        }
    }

    @Override
    public void onTrackFinished(TrackingService.State state) {
        // Called by stopTracking() from finishDemo(); the result is already shown.
    }

    @Override
    public void onError(String message) {
        if (demoRunning || demoStarting) {
            cancelDemo();
        }
        showDialog(getString(R.string.error_title), message,
                getString(R.string.ok), null, null);
    }

    private void finishDemo(boolean success) {
        if (!demoRunning) {
            return;
        }
        demoRunning = false;
        demoReturning = false;
        handler.removeCallbacks(demoTick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int points = lastDemoState == null ? 0 : lastDemoState.pointCount;
        if (success) {
            demoStatus = PASS;
            demoDetail = "通过：模拟 " + points + " 个轨迹点，返航到达起点";
        } else {
            demoStatus = WARN;
            demoDetail = "演示未完成";
        }
        if (demoCheck != null) {
            demoCheck.status = demoStatus;
            demoCheck.detail = demoDetail;
        }
        if (success) {
            demoStatusText.setText(R.string.self_test_arrived);
        }
        if (service != null) {
            service.stopTracking(false);
            service.setListener(null);
        }
        String title = getString(success ? R.string.self_test_demo_title
                : R.string.self_test_demo_failed);
        String message = success
                ? getString(R.string.self_test_demo_done, points)
                : getString(R.string.self_test_demo_failed_message);
        showDialog(title, message, getString(R.string.ok), null,
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        closeDemo();
                    }
                });
    }

    private void cancelDemo() {
        if (demoStarting) {
            demoStarting = false;
            unbindQuietly();
            return;
        }
        if (!demoRunning) {
            return;
        }
        demoRunning = false;
        demoReturning = false;
        handler.removeCallbacks(demoTick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (service != null) {
            service.stopTracking(false);
            service.setListener(null);
        }
        demoStatus = WARN;
        demoDetail = "演示已取消";
        if (demoCheck != null) {
            demoCheck.status = demoStatus;
            demoCheck.detail = demoDetail;
        }
        closeDemoUi();
        Toast.makeText(this, R.string.self_test_demo_cancel, Toast.LENGTH_SHORT).show();
    }

    private void closeDemo() {
        closeDemoUi();
        renderChecks();
    }

    private void closeDemoUi() {
        demoScreen.setVisibility(View.GONE);
        testScreen.setVisibility(View.VISIBLE);
        demoMap.setState(null, true, round);
        demoGuide.setState(null, true);
        unbindQuietly();
    }

    private void unbindQuietly() {
        if (bound) {
            try {
                unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
            bound = false;
        }
        service = null;
        demoStarting = false;
    }

    private void showBusy() {
        showDialog(getString(R.string.self_test_busy),
                getString(R.string.self_test_busy_message),
                getString(R.string.ok), null, null);
    }

    private void showDemoFailed() {
        showDialog(getString(R.string.self_test_demo_failed),
                getString(R.string.self_test_demo_failed_message),
                getString(R.string.ok), null, null);
    }

    private void showDialog(String title, String message, String positive,
                            String negative, WatchDialog.OnChoice choice) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        dialog = WatchDialog.show(this, title, message, positive, negative, choice);
    }

    @Override
    protected void onStop() {
        if (demoRunning || demoStarting) {
            cancelDemo();
        } else {
            unbindQuietly();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (probeHeading != null) {
            probeHeading.stop();
            probeHeading = null;
        }
        if (demoRunning) {
            demoRunning = false;
            if (service != null) {
                service.stopTracking(false);
                service.setListener(null);
            }
        }
        unbindQuietly();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            return;
        }
        if (demoRunning || demoStarting) {
            cancelDemo();
            return;
        }
        super.onBackPressed();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package com.mitufanhang.wear;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

/** Small, dependency-free settings store shared by the activity and the service. */
public final class AppSettings {
    private static final String PREFS = "mitu_settings";

    public static final String SHAPE_AUTO = "auto";
    public static final String SHAPE_ROUND = "round";
    public static final String SHAPE_SQUARE = "square";

    public static final int[] INTERVALS = {1000, 2000, 5000};

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String shape() {
        String s = prefs.getString("shape", SHAPE_AUTO);
        if (SHAPE_ROUND.equals(s) || SHAPE_SQUARE.equals(s)) {
            return s;
        }
        return SHAPE_AUTO;
    }

    public void setShape(String shape) {
        prefs.edit().putString("shape", shape).apply();
    }

    public boolean resolveRound(Resources resources) {
        String s = shape();
        if (SHAPE_ROUND.equals(s)) {
            return true;
        }
        if (SHAPE_SQUARE.equals(s)) {
            return false;
        }
        return resources.getConfiguration().isScreenRound();
    }

    public String shapeLabel(Resources resources) {
        String s = shape();
        if (SHAPE_ROUND.equals(s)) {
            return "圆形";
        }
        if (SHAPE_SQUARE.equals(s)) {
            return "方形";
        }
        return "自动（" + (resources.getConfiguration().isScreenRound() ? "圆形" : "方形") + "）";
    }

    public boolean keepAwake() {
        return prefs.getBoolean("keep_awake", true);
    }

    public void setKeepAwake(boolean value) {
        prefs.edit().putBoolean("keep_awake", value).apply();
    }

    public boolean headingUp() {
        return prefs.getBoolean("heading_up", true);
    }

    public void setHeadingUp(boolean value) {
        prefs.edit().putBoolean("heading_up", value).apply();
    }

    public boolean vibrate() {
        return prefs.getBoolean("vibrate", true);
    }

    public void setVibrate(boolean value) {
        prefs.edit().putBoolean("vibrate", value).apply();
    }

    public int intervalMs() {
        int ms = prefs.getInt("interval_ms", 1000);
        for (int value : INTERVALS) {
            if (value == ms) {
                return ms;
            }
        }
        return 1000;
    }

    public void setIntervalMs(int ms) {
        prefs.edit().putInt("interval_ms", ms).apply();
    }

    public void reset() {
        prefs.edit().clear().apply();
    }

    public static boolean isRound(Context context) {
        return new AppSettings(context).resolveRound(context.getResources());
    }

    public static String shapeDisplay(Resources resources, String shape) {
        if (SHAPE_ROUND.equals(shape)) {
            return "圆形";
        }
        if (SHAPE_SQUARE.equals(shape)) {
            return "方形";
        }
        boolean round = resources.getConfiguration().isScreenRound();
        return "自动（" + (round ? "圆形" : "方形") + "）";
    }

    public static boolean configIsRound(Configuration configuration) {
        return configuration.isScreenRound();
    }
}

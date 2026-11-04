package com.mitufanhang.wear;

import java.util.Locale;

/** Pure geo/maths helpers, kept free of Android types so they can be unit tested. */
public final class Geo {
    public static final double EARTH_RADIUS_M = 6371000.0;

    private Geo() {
    }

    /** Great-circle distance in metres. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    /** Normalise to [0, 360). */
    public static double normalizeDegrees(double degrees) {
        double d = degrees % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    /** Shortest signed difference from -> to, in (-180, 180]. */
    public static double angleDelta(double from, double to) {
        double d = normalizeDegrees(to - from + 180.0) - 180.0;
        if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** Equirectangular local offset: x = east metres, y = north metres. */
    public static double[] localMeters(double originLat, double originLon, double lat, double lon) {
        double x = Math.toRadians(lon - originLon) * Math.cos(Math.toRadians((lat + originLat) / 2.0))
                * EARTH_RADIUS_M;
        double y = Math.toRadians(lat - originLat) * EARTH_RADIUS_M;
        return new double[]{x, y};
    }

    /** Human readable distance for a watch-sized display. */
    public static String formatDistance(double metres) {
        if (Double.isNaN(metres) || metres < 0) {
            return "--";
        }
        if (metres < 1000) {
            return Math.round(metres) + " m";
        }
        double km = metres / 1000.0;
        if (km < 10) {
            return String.format(Locale.US, "%.2f km", km);
        }
        if (km < 100) {
            return String.format(Locale.US, "%.1f km", km);
        }
        return String.format(Locale.US, "%.0f km", km);
    }

    /** Compact duration: MM:SS, or H:MM:SS from one hour. */
    public static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000L);
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** Chinese direction hint relative to where the user is facing. */
    public static String directionText(double relativeDegrees) {
        double a = Math.abs(angleDelta(0, relativeDegrees));
        if (a < 22.5) {
            return "前方";
        }
        if (a < 67.5) {
            return relativeDegrees > 0 ? "右前方" : "左前方";
        }
        if (a < 112.5) {
            return relativeDegrees > 0 ? "右侧" : "左侧";
        }
        if (a < 157.5) {
            return relativeDegrees > 0 ? "右后方" : "左后方";
        }
        return "后方";
    }

    /** Nice "round" distance no larger than max, for the scale bar. */
    public static double niceDistance(double max) {
        double[] candidates = {5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000};
        double chosen = candidates[0];
        for (double c : candidates) {
            if (c <= max) {
                chosen = c;
            }
        }
        return chosen;
    }
}

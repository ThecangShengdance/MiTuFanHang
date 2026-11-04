package com.mitufanhang.wear;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A recorded walk, including the in-progress state used for crash recovery. */
public final class TrackSession {
    public final long startedAt;
    public long endedAt;
    public boolean returning;
    public boolean active = true;
    public final ArrayList<TrackPoint> points = new ArrayList<>();

    public TrackSession(long startedAt) {
        this.startedAt = startedAt;
    }

    public void addPoint(TrackPoint point) {
        if (!points.isEmpty()) {
            TrackPoint last = points.get(points.size() - 1);
            double d = Geo.distance(last.lat, last.lon, point.lat, point.lon);
            // A GPS jump while standing still should not draw a teleport line.
            if (d > 250 && point.time - last.time < 30000) {
                return;
            }
        }
        points.add(point);
    }

    public double distanceMeters() {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            TrackPoint a = points.get(i - 1);
            TrackPoint b = points.get(i);
            double d = Geo.distance(a.lat, a.lon, b.lat, b.lon);
            if (d < 250) {
                total += d;
            }
        }
        return total;
    }

    public long durationMs(long now) {
        long end = endedAt > 0 ? endedAt : now;
        return Math.max(0, end - startedAt);
    }

    public TrackPoint first() {
        return points.isEmpty() ? null : points.get(0);
    }

    public TrackPoint last() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    public TrackSession copyPoints() {
        TrackSession copy = new TrackSession(startedAt);
        copy.endedAt = endedAt;
        copy.returning = returning;
        copy.active = active;
        copy.points.addAll(points);
        return copy;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("start", startedAt);
        o.put("end", endedAt);
        o.put("returning", returning);
        o.put("active", active);
        JSONArray arr = new JSONArray();
        for (TrackPoint p : points) {
            arr.put(p.toJson());
        }
        o.put("points", arr);
        return o;
    }

    public static TrackSession fromJson(JSONObject o) {
        TrackSession s = new TrackSession(o.optLong("start", System.currentTimeMillis()));
        s.endedAt = o.optLong("end", 0);
        s.returning = o.optBoolean("returning", false);
        s.active = o.optBoolean("active", false);
        JSONArray arr = o.optJSONArray("points");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) {
                    s.points.add(TrackPoint.fromJson(p));
                }
            }
        }
        return s;
    }

    public static List<TrackPoint> copyOf(List<TrackPoint> source) {
        return new ArrayList<>(source);
    }
}

package com.mitufanhang.wear;

import org.json.JSONException;
import org.json.JSONObject;

/** One immutable GPS fix on a recorded track. */
public final class TrackPoint {
    public final double lat;
    public final double lon;
    public final long time;
    public final float accuracy;
    public final float speed;
    public final float bearing;

    public TrackPoint(double lat, double lon, long time, float accuracy, float speed, float bearing) {
        this.lat = lat;
        this.lon = lon;
        this.time = time;
        this.accuracy = accuracy;
        this.speed = speed;
        this.bearing = bearing;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("lat", lat);
        o.put("lon", lon);
        o.put("t", time);
        o.put("acc", (double) accuracy);
        o.put("spd", (double) speed);
        o.put("brg", (double) bearing);
        return o;
    }

    public static TrackPoint fromJson(JSONObject o) {
        return new TrackPoint(
                o.optDouble("lat"),
                o.optDouble("lon"),
                o.optLong("t"),
                (float) o.optDouble("acc", 0),
                (float) o.optDouble("spd", 0),
                (float) o.optDouble("brg", 0));
    }
}

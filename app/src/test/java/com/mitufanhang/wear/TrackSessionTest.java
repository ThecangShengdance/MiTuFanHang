package com.mitufanhang.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Core track filtering and distance behaviour, independent of Android. */
public final class TrackSessionTest {

    private static TrackPoint point(double lat, double lon, long time) {
        return new TrackPoint(lat, lon, time, 5f, 1.2f, 0f);
    }

    @Test
    public void rejectsTeleportWithinThirtySeconds() {
        TrackSession session = new TrackSession(1000L);
        session.addPoint(point(39.9000, 116.4000, 1000L));
        session.addPoint(point(39.9100, 116.4000, 11000L)); // ~1.1 km in 10 s
        assertEquals(1, session.points.size());
    }

    @Test
    public void acceptsLargeMoveAfterThirtySeconds() {
        TrackSession session = new TrackSession(1000L);
        session.addPoint(point(39.9000, 116.4000, 1000L));
        session.addPoint(point(39.9100, 116.4000, 41000L));
        assertEquals(2, session.points.size());
    }

    @Test
    public void sumsOnlyNearbySegments() {
        TrackSession session = new TrackSession(1000L);
        session.addPoint(point(39.9000, 116.4000, 1000L));
        session.addPoint(point(39.9001, 116.4000, 6000L)); // ~11 m
        session.addPoint(point(39.9002, 116.4000, 11000L)); // ~11 m
        assertEquals(22.2, session.distanceMeters(), 1.0);
    }

    @Test
    public void durationStopsAtEndedAt() {
        TrackSession session = new TrackSession(1000L);
        session.endedAt = 61000L;
        assertEquals(60000L, session.durationMs(999999L));
        assertTrue(session.durationMs(999999L) > 0);
    }
}

package com.mitufanhang.wear;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GeoTest {

    @Test
    public void distanceNorthIsAboutOneKilometrePer0_009Degrees() {
        double d = Geo.distance(31.2304, 121.4737, 31.2394, 121.4737);
        assertEquals(1000, d, 15);
    }

    @Test
    public void bearingCardinalDirections() {
        assertEquals(0, Geo.bearing(0, 0, 1, 0), 0.01);
        assertEquals(90, Geo.bearing(0, 0, 0, 1), 0.01);
        assertEquals(180, Geo.bearing(1, 0, 0, 0), 0.01);
        assertEquals(270, Geo.bearing(0, 1, 0, 0), 0.01);
    }

    @Test
    public void normalizeAndShortestDelta() {
        assertEquals(10, Geo.normalizeDegrees(370), 0.001);
        assertEquals(350, Geo.normalizeDegrees(-10), 0.001);
        assertEquals(20, Geo.angleDelta(350, 10), 0.001);
        assertEquals(-20, Geo.angleDelta(10, 350), 0.001);
        assertEquals(180, Geo.angleDelta(0, 180), 0.001);
        assertEquals(180, Geo.angleDelta(0, -180), 0.001);
    }

    @Test
    public void localMetersEastAndNorth() {
        double[] p = Geo.localMeters(0, 0, 0, 0.001);
        assertEquals(111.3, p[0], 0.5);
        assertEquals(0, p[1], 0.01);
        double[] q = Geo.localMeters(0, 0, 0.001, 0);
        assertEquals(0, q[0], 0.01);
        assertEquals(111.2, q[1], 0.5);
    }

    @Test
    public void distanceFormatting() {
        assertEquals("0 m", Geo.formatDistance(0));
        assertEquals("999 m", Geo.formatDistance(999.4));
        assertEquals("1.00 km", Geo.formatDistance(1000));
        assertEquals("12.3 km", Geo.formatDistance(12345));
        assertEquals("--", Geo.formatDistance(Double.NaN));
    }

    @Test
    public void durationFormatting() {
        assertEquals("00:00", Geo.formatDuration(0));
        assertEquals("01:05", Geo.formatDuration(65000));
        assertEquals("1:01:01", Geo.formatDuration(3661000));
    }

    @Test
    public void directionTextUsesRelativeBearing() {
        assertEquals("前方", Geo.directionText(0));
        assertEquals("右前方", Geo.directionText(45));
        assertEquals("右侧", Geo.directionText(90));
        assertEquals("右后方", Geo.directionText(135));
        assertEquals("后方", Geo.directionText(180));
        assertEquals("左前方", Geo.directionText(-45));
        assertEquals("左侧", Geo.directionText(-90));
    }

    @Test
    public void niceScaleNeverExceedsMaximum() {
        assertEquals(25, Geo.niceDistance(30), 0.001);
        assertEquals(50, Geo.niceDistance(60), 0.001);
        assertEquals(1000, Geo.niceDistance(1000), 0.001);
        assertEquals(5, Geo.niceDistance(1), 0.001);
    }
}

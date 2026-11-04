package com.mitufanhang.wear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * Pure black map.  It draws only what matters on a tiny watch screen: the walked
 * trail, the start point, the current position and the heading.  The map is
 * rotated so that the wearer's heading is always up (or north is up, if the user
 * prefers that in settings).
 */
public final class TrackMapView extends View {
    private static final int MINT = 0xFF57E6A8;
    private static final int AMBER = 0xFFFFC857;
    private static final int PAPER = 0xFFF4FBF7;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trailPath = new Path();
    private final Path scratch = new Path();
    private final RectF rect = new RectF();
    private DashPathEffect dashEffect;

    private TrackingService.State state;
    private boolean headingUp = true;
    private boolean round;
    private boolean hasCenter;
    private boolean initialized;
    private boolean tracking;
    private boolean headingInitialized;

    private double centerLat;
    private double centerLon;
    private double targetLat;
    private double targetLon;
    private float displayHeading;
    private float scale;
    private long lastFrameAt;

    private int density;

    public TrackMapView(Context context) {
        this(context, null);
    }

    public TrackMapView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TrackMapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = Math.round(context.getResources().getDisplayMetrics().density);
        setBackgroundColor(Color.BLACK);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        fill.setStyle(Paint.Style.FILL);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);

        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setState(TrackingService.State state, boolean headingUp, boolean round) {
        boolean wasTracking = this.tracking;
        this.state = state;
        this.headingUp = headingUp;
        this.round = round;
        this.tracking = state != null && state.tracking;

        // A new recording session should recentre on the wearer immediately
        // instead of sliding in from wherever the previous track ended.
        if (this.tracking && !wasTracking) {
            initialized = false;
            hasCenter = false;
            scale = 0f;
            lastFrameAt = 0L;
            headingInitialized = false;
        }

        boolean centerNow = state != null && (state.hasFix || state.hasTrack());
        if (centerNow) {
            double lat;
            double lon;
            if (state.hasFix) {
                lat = state.lat;
                lon = state.lon;
            } else {
                TrackPoint last = state.trail.get(state.trail.size() - 1);
                lat = last.lat;
                lon = last.lon;
            }
            targetLat = lat;
            targetLon = lon;
            if (!initialized) {
                centerLat = lat;
                centerLon = lon;
                initialized = true;
                hasCenter = true;
                scale = 0f;
            } else {
                hasCenter = true;
            }
            if (state.hasHeading && !headingInitialized) {
                // Snap to the first known heading; afterwards onDraw smooths it.
                displayHeading = state.heading;
                headingInitialized = true;
            }
        } else {
            hasCenter = false;
        }
        if (tracking) {
            postInvalidateDelayed(120L);
        } else {
            invalidate();
        }
    }

    public void setHeadingUp(boolean headingUp) {
        this.headingUp = headingUp;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        tracking = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameAt == 0 ? 0.12f : Math.min(0.35f, (now - lastFrameAt) / 1000f);
        lastFrameAt = now;

        float cx = w / 2f;
        float cy = h / 2f;
        float minSide = Math.min(w, h);
        float fitRadius = minSide * (round ? 0.385f : 0.42f);

        canvas.drawColor(Color.BLACK);

        if (!hasCenter || state == null || state.trail.isEmpty()) {
            drawRadar(canvas, cx, cy, minSide);
            return;
        }

        // Smooth the camera so a noisy GPS fix does not shove the map around.
        float centerFactor = 1f - (float) Math.exp(-dt / 0.22f);
        centerLat += (targetLat - centerLat) * centerFactor;
        centerLon += (targetLon - centerLon) * centerFactor;

        float targetHeading = state.hasHeading ? state.heading : displayHeading;
        displayHeading = (float) Geo.normalizeDegrees(displayHeading
                + Geo.angleDelta(displayHeading, targetHeading) * (1f - (float) Math.exp(-dt / 0.18f)));

        double maxMeters = 30.0;
        List<TrackPoint> points = state.trail;
        for (int i = 0; i < points.size(); i++) {
            TrackPoint p = points.get(i);
            double d = Geo.distance(centerLat, centerLon, p.lat, p.lon);
            if (d > maxMeters) {
                maxMeters = d;
            }
        }
        maxMeters = Math.min(3000.0, Math.max(45.0, maxMeters * 1.18));
        float targetScale = fitRadius / (float) maxMeters;
        if (scale <= 0f) {
            scale = targetScale;
        } else {
            scale += (targetScale - scale) * (1f - (float) Math.exp(-dt / 0.45f));
        }

        boolean rotate = headingUp && headingInitialized;
        double theta = Math.toRadians(rotate ? displayHeading : 0.0);
        float cosH = (float) Math.cos(theta);
        float sinH = (float) Math.sin(theta);
        double lonScale = 111320.0 * Math.cos(Math.toRadians(centerLat));

        // Trail path.
        trailPath.reset();
        float firstX = 0f;
        float firstY = 0f;
        float lastX = 0f;
        float lastY = 0f;
        for (int i = 0; i < points.size(); i++) {
            TrackPoint p = points.get(i);
            float x = projectX(p.lat, p.lon, cx, cosH, sinH, lonScale);
            float y = projectY(p.lat, p.lon, cy, cosH, sinH, lonScale);
            if (i == 0) {
                trailPath.moveTo(x, y);
                firstX = x;
                firstY = y;
            } else {
                trailPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }

        if (points.size() > 1) {
            glow.setColor(0x4457E6A8);
            glow.setStrokeWidth(dp(7f));
            canvas.drawPath(trailPath, glow);

            stroke.setColor(0xFF3FCB92);
            stroke.setStrokeWidth(dp(2.6f));
            canvas.drawPath(trailPath, stroke);

            // Bright head of the trail.
            scratch.reset();
            int from = Math.max(0, points.size() - 26);
            for (int i = from; i < points.size(); i++) {
                TrackPoint p = points.get(i);
                float x = projectX(p.lat, p.lon, cx, cosH, sinH, lonScale);
                float y = projectY(p.lat, p.lon, cy, cosH, sinH, lonScale);
                if (i == from) {
                    scratch.moveTo(x, y);
                } else {
                    scratch.lineTo(x, y);
                }
            }
            stroke.setColor(0xFFD7FFEF);
            stroke.setStrokeWidth(dp(3.1f));
            canvas.drawPath(scratch, stroke);

            // A few evenly spaced dots make the direction of travel readable.
            fill.setColor(0x99E9FFF6);
            int step = Math.max(6, points.size() / 28);
            for (int i = step; i < points.size() - 2; i += step) {
                TrackPoint p = points.get(i);
                float x = projectX(p.lat, p.lon, cx, cosH, sinH, lonScale);
                float y = projectY(p.lat, p.lon, cy, cosH, sinH, lonScale);
                canvas.drawCircle(x, y, dp(1.5f), fill);
            }
        } else {
            fill.setColor(MINT);
            canvas.drawCircle(lastX, lastY, dp(3.5f), fill);
        }

        // Dashed guidance line back to the start.
        TrackPoint start = points.get(0);
        float startX = projectX(start.lat, start.lon, cx, cosH, sinH, lonScale);
        float startY = projectY(start.lat, start.lon, cy, cosH, sinH, lonScale);
        if (state.returning && points.size() > 1) {
            stroke.setColor(0x99FFC857);
            stroke.setStrokeWidth(dp(1.6f));
            if (dashEffect == null) {
                dashEffect = new DashPathEffect(new float[]{dp(5f), dp(5f)}, 0f);
            }
            stroke.setPathEffect(dashEffect);
            canvas.drawLine(cx, cy, startX, startY, stroke);
            stroke.setPathEffect(null);
        }

        drawStartMarker(canvas, startX, startY);

        // North marker sits on the rim and rotates with the map.
        drawNorth(canvas, cx, cy, minSide, rotate ? displayHeading : 0f, rotate);

        // Direction to start when it sits outside the visible map.
        if (state.returning) {
            drawOffscreenStart(canvas, cx, cy, minSide, startX, startY);
        }

        drawCurrent(canvas, cx, cy, rotate);

        // If the camera is still catching up, keep animating.
        if (tracking) {
            postInvalidateDelayed(120L);
        }
    }

    private float projectX(double lat, double lon, float cx, float cosH, float sinH, double lonScale) {
        double north = (lat - centerLat) * 111320.0;
        double east = (lon - centerLon) * lonScale;
        return cx + (float) ((east * cosH - north * sinH) * scale);
    }

    private float projectY(double lat, double lon, float cy, float cosH, float sinH, double lonScale) {
        double north = (lat - centerLat) * 111320.0;
        double east = (lon - centerLon) * lonScale;
        return cy - (float) ((east * sinH + north * cosH) * scale);
    }

    private void drawStartMarker(Canvas canvas, float x, float y) {
        fill.setColor(0xCC1A1206);
        canvas.drawCircle(x, y, dp(7.5f), fill);
        stroke.setColor(AMBER);
        stroke.setStrokeWidth(dp(1.8f));
        canvas.drawCircle(x, y, dp(5.6f), stroke);
        fill.setColor(AMBER);
        canvas.drawCircle(x, y, dp(2.4f), fill);
        drawChip(canvas, "起", x, y - dp(13f), 9f, AMBER);
    }

    private void drawCurrent(Canvas canvas, float cx, float cy, boolean rotate) {
        long now = SystemClock.uptimeMillis();
        float pulse = (now % 1700L) / 1700f;
        stroke.setColor(MINT);
        stroke.setStrokeWidth(dp(1.5f));
        fill.setColor((int) ((1f - pulse) * 95f) << 24 | (MINT & 0x00FFFFFF));
        canvas.drawCircle(cx, cy, dp(9f) + pulse * dp(9f), fill);

        // Heading fan: in heading-up mode it always points to the top.
        if (state.hasHeading) {
            float angle;
            if (rotate) {
                angle = 0f;
            } else {
                angle = state.heading;
            }
            double rad = Math.toRadians(angle);
            float dx = (float) Math.sin(rad);
            float dy = (float) -Math.cos(rad);
            float px = -dy;
            float py = dx;
            scratch.reset();
            scratch.moveTo(cx + dx * dp(18f), cy + dy * dp(18f));
            scratch.lineTo(cx + dx * dp(10f) + px * dp(6f), cy + dy * dp(10f) + py * dp(6f));
            scratch.lineTo(cx + dx * dp(10f) - px * dp(6f), cy + dy * dp(10f) - py * dp(6f));
            scratch.close();
            fill.setColor(0x5557E6A8);
            canvas.drawPath(scratch, fill);
        }

        fill.setColor(PAPER);
        canvas.drawCircle(cx, cy, dp(4.4f), fill);
        stroke.setColor(0xCC062018);
        stroke.setStrokeWidth(dp(1.2f));
        canvas.drawCircle(cx, cy, dp(5.8f), stroke);
    }

    private void drawNorth(Canvas canvas, float cx, float cy, float minSide, float heading, boolean rotated) {
        double rad = Math.toRadians(heading);
        float r = minSide * 0.5f - dp(round ? 19f : 14f);
        float x = cx - (float) Math.sin(rad) * r;
        float y = cy - (float) Math.cos(rad) * r;
        fill.setColor(0xCC0B120F);
        canvas.drawCircle(x, y, dp(8.5f), fill);
        stroke.setColor(0x552B3D35);
        stroke.setStrokeWidth(dp(1f));
        canvas.drawCircle(x, y, dp(8.5f), stroke);
        text.setColor(rotated ? MINT : 0xFF9DB1A7);
        text.setTextSize(dp(8.5f));
        text.setFakeBoldText(true);
        canvas.drawText("N", x, y + dp(3f), text);
    }

    private void drawOffscreenStart(Canvas canvas, float cx, float cy, float minSide,
                                    float startX, float startY) {
        float dx = startX - cx;
        float dy = startY - cy;
        float distance = (float) Math.hypot(dx, dy);
        float limit = minSide * 0.5f - dp(24f);
        if (distance <= limit) {
            return;
        }
        float angle = (float) Math.atan2(dy, dx);
        float x = cx + (float) Math.cos(angle) * limit;
        float y = cy + (float) Math.sin(angle) * limit;
        scratch.reset();
        scratch.moveTo(x + (float) Math.cos(angle) * dp(8f), y + (float) Math.sin(angle) * dp(8f));
        scratch.lineTo(x + (float) Math.cos(angle + 2.5f) * dp(8f),
                y + (float) Math.sin(angle + 2.5f) * dp(8f));
        scratch.lineTo(x + (float) Math.cos(angle - 2.5f) * dp(8f),
                y + (float) Math.sin(angle - 2.5f) * dp(8f));
        scratch.close();
        fill.setColor(AMBER);
        canvas.drawPath(scratch, fill);
    }

    private void drawRadar(Canvas canvas, float cx, float cy, float minSide) {
        stroke.setStrokeWidth(dp(1f));
        for (int i = 1; i <= 3; i++) {
            stroke.setColor(0x14FFFFFF);
            canvas.drawCircle(cx, cy, minSide * 0.10f * i, stroke);
        }
        fill.setColor(0x4457E6A8);
        canvas.drawCircle(cx, cy, dp(3f), fill);
        stroke.setColor(0x2257E6A8);
        canvas.drawLine(cx - dp(10f), cy, cx + dp(10f), cy, stroke);
        canvas.drawLine(cx, cy - dp(10f), cx, cy + dp(10f), stroke);
    }

    private void drawChip(Canvas canvas, String label, float x, float y, float sizeSp, int color) {
        text.setTextSize(dp(sizeSp));
        text.setColor(color);
        float textWidth = text.measureText(label);
        float halfW = textWidth / 2f + dp(4f);
        float halfH = dp(7.5f);
        float top = y - halfH;
        if (top < dp(2f)) {
            top = dp(2f);
        }
        float bottom = top + halfH * 2f;
        rect.set(x - halfW, top, x + halfW, bottom);
        fill.setColor(0xCC0B120F);
        canvas.drawRoundRect(rect, dp(4f), dp(4f), fill);
        canvas.drawText(label, x, bottom - dp(2.2f), text);
    }

    private float dp(float value) {
        return value * density;
    }
}

package com.mitufanhang.wear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Return guidance drawn directly around the current position: a large amber
 * arrow points at the start, and a ring shows when the wearer is back or has
 * wandered away from the best line home.
 */
public final class GuideView extends View {
    private static final int AMBER = 0xFFFFC857;
    private static final int MINT = 0xFF57E6A8;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final Path check = new Path();

    private TrackingService.State state;
    private boolean headingUp = true;
    private int density;

    public GuideView(Context context) {
        this(context, null);
    }

    public GuideView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GuideView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = Math.round(context.getResources().getDisplayMetrics().density);
        setClickable(false);
        setFocusable(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    public void setState(TrackingService.State state, boolean headingUp) {
        this.state = state;
        this.headingUp = headingUp;
        if (visible()) {
            postInvalidateDelayed(120L);
        } else {
            invalidate();
        }
    }

    private boolean visible() {
        return state != null && state.returning && state.hasTrack();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible()) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float minSide = Math.min(w, h);
        long now = SystemClock.uptimeMillis();

        if (state.arrived) {
            drawArrived(canvas, cx, cy, now);
            postInvalidateDelayed(120L);
            return;
        }

        float heading = headingUp && state.hasHeading ? state.heading : 0f;
        float relative = (float) Geo.normalizeDegrees(state.bearingToStart - heading);
        double rad = Math.toRadians(relative);
        float dx = (float) Math.sin(rad);
        float dy = (float) -Math.cos(rad);

        float radius = minSide * 0.165f;
        float tip = radius + dp(10f);

        // Backdrop ring with a gap where the arrow lives.
        stroke.setColor(0x33FFC857);
        stroke.setStrokeWidth(dp(1.6f));
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                relative - 56f, 292f, false, stroke);

        if (state.offCourse) {
            float pulse = (now % 900L) / 900f;
            stroke.setColor((int) ((1f - pulse) * 150f) << 24 | (AMBER & 0x00FFFFFF));
            stroke.setStrokeWidth(dp(2.4f));
            canvas.drawCircle(cx, cy, dp(18f) + pulse * dp(11f), stroke);
        }

        float px = -dy;
        float py = dx;

        // Shaft.
        stroke.setColor(AMBER);
        stroke.setStrokeWidth(dp(3.4f));
        canvas.drawLine(cx + dx * (radius - dp(7f)), cy + dy * (radius - dp(7f)),
                cx + dx * radius, cy + dy * radius, stroke);

        // Arrow head.
        arrow.reset();
        arrow.moveTo(cx + dx * tip, cy + dy * tip);
        arrow.lineTo(cx + dx * (tip - dp(11f)) + px * dp(8f),
                cy + dy * (tip - dp(11f)) + py * dp(8f));
        arrow.lineTo(cx + dx * (tip - dp(11f)) - px * dp(8f),
                cy + dy * (tip - dp(11f)) - py * dp(8f));
        arrow.close();
        fill.setColor(AMBER);
        canvas.drawPath(arrow, fill);
        stroke.setColor(0x66FFC857);
        stroke.setStrokeWidth(dp(1f));
        canvas.drawPath(arrow, stroke);
        postInvalidateDelayed(120L);
    }

    private void drawArrived(Canvas canvas, float cx, float cy, long now) {
        float pulse = (now % 1500L) / 1500f;
        stroke.setColor((int) ((1f - pulse) * 130f) << 24 | (MINT & 0x00FFFFFF));
        stroke.setStrokeWidth(dp(2f));
        canvas.drawCircle(cx, cy, dp(17f) + pulse * dp(13f), stroke);
        stroke.setColor(MINT);
        stroke.setStrokeWidth(dp(2.2f));
        canvas.drawCircle(cx, cy, dp(17f), stroke);
        check.reset();
        check.moveTo(cx - dp(6.5f), cy);
        check.lineTo(cx - dp(1.5f), cy + dp(5f));
        check.lineTo(cx + dp(7f), cy - dp(6f));
        canvas.drawPath(check, stroke);
    }

    private float dp(float value) {
        return value * density;
    }
}

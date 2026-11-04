package com.mitufanhang.wear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/** A black, round-watch friendly compass rose with a north needle. */
public final class CompassView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float heading = Float.NaN;
    private boolean needsCalibration;

    public CompassView(Context context) {
        super(context);
        setBackgroundColor(getContext().getColor(R.color.bg));
        ringPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setState(float heading, boolean needsCalibration) {
        this.heading = heading;
        this.needsCalibration = needsCalibration;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float side = Math.min(w, h);
        float cx = w / 2f;
        float cy = h * 0.43f;
        float radius = side * 0.30f;
        float density = getResources().getDisplayMetrics().density;
        int mint = getContext().getColor(R.color.mint);
        int amber = getContext().getColor(R.color.amber);
        int secondary = getContext().getColor(R.color.text_secondary);
        int accent = needsCalibration ? amber : mint;

        ringPaint.setStrokeWidth(dp(density, 1.6f));
        ringPaint.setColor(accent);
        canvas.drawCircle(cx, cy, radius, ringPaint);
        ringPaint.setStrokeWidth(dp(density, 0.8f));
        ringPaint.setColor(0x553A6B5C);
        canvas.drawCircle(cx, cy, radius * 0.92f, ringPaint);

        tickPaint.setColor(0xAA8FA9A0);
        for (int deg = 0; deg < 360; deg += 5) {
            boolean major = deg % 30 == 0;
            float length = major ? dp(density, 9f) : dp(density, 4f);
            double rad = Math.toRadians(deg);
            float outer = radius - dp(density, 4f);
            float inner = outer - length;
            float x1 = cx + (float) (Math.sin(rad) * outer);
            float y1 = cy - (float) (Math.cos(rad) * outer);
            float x2 = cx + (float) (Math.sin(rad) * inner);
            float y2 = cy - (float) (Math.cos(rad) * inner);
            tickPaint.setStrokeWidth(major ? dp(density, 1.2f) : dp(density, 0.7f));
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        float display = Float.isNaN(heading) ? 0f : heading;
        canvas.save();
        canvas.rotate(-display, cx, cy);

        textPaint.setTextSize(Math.max(dp(density, 11f), side * 0.055f));
        drawCardinal(canvas, cx, cy, radius * 0.78f, 0f, "N", mint);
        drawCardinal(canvas, cx, cy, radius * 0.78f, 90f, "E", secondary);
        drawCardinal(canvas, cx, cy, radius * 0.78f, 180f, "S", secondary);
        drawCardinal(canvas, cx, cy, radius * 0.78f, 270f, "W", secondary);

        // North needle (amber tip) and south tail. Without a heading the
        // needle is hidden so the display never pretends to know north.
        if (!Float.isNaN(heading)) {
            float tip = radius * 0.64f;
            path.reset();
            path.moveTo(cx, cy - tip);
            path.lineTo(cx - dp(density, 5f), cy);
            path.lineTo(cx, cy + dp(density, 3f));
            path.lineTo(cx + dp(density, 5f), cy);
            path.close();
            needlePaint.setColor(amber);
            canvas.drawPath(path, needlePaint);

            path.reset();
            path.moveTo(cx, cy + tip * 0.55f);
            path.lineTo(cx - dp(density, 4f), cy);
            path.lineTo(cx, cy - dp(density, 2f));
            path.lineTo(cx + dp(density, 4f), cy);
            path.close();
            needlePaint.setColor(0x667FE7C4);
            canvas.drawPath(path, needlePaint);
        }
        canvas.restore();

        needlePaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, dp(density, 4f), needlePaint);
        needlePaint.setColor(accent);
        canvas.drawCircle(cx, cy, dp(density, 2.2f), needlePaint);

        // Fixed heading marker at the top of the bezel.
        path.reset();
        path.moveTo(cx, cy - radius - dp(density, 7f));
        path.lineTo(cx - dp(density, 5f), cy - radius - dp(density, 1f));
        path.lineTo(cx + dp(density, 5f), cy - radius - dp(density, 1f));
        path.close();
        needlePaint.setColor(accent);
        canvas.drawPath(path, needlePaint);

        textPaint.setColor(needsCalibration ? amber : 0xFFFFFFFF);
        textPaint.setTextSize(Math.max(dp(density, 16f), side * 0.062f));
        String degrees = Float.isNaN(heading) ? "—" : Math.round(heading) + "°";
        canvas.drawText(degrees, cx, cy + radius * 0.56f, textPaint);

        textPaint.setColor(secondary);
        textPaint.setTextSize(Math.max(dp(density, 8f), side * 0.032f));
        String direction = Float.isNaN(heading) ? "" : Geo.directionText(heading);
        canvas.drawText(direction, cx, cy + radius * 0.56f + dp(density, 16f), textPaint);
    }

    private void drawCardinal(Canvas canvas, float cx, float cy, float distance,
                              float degrees, String label, int color) {
        double rad = Math.toRadians(degrees);
        float x = cx + (float) (Math.sin(rad) * distance);
        float y = cy - (float) (Math.cos(rad) * distance);
        textPaint.setColor(color);
        canvas.drawText(label, x, y + textPaint.getTextSize() / 3f, textPaint);
    }

    private static float dp(float density, float value) {
        return value * density;
    }
}

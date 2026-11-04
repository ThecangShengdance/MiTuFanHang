package com.mitufanhang.wear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Root container that can clip itself to a circle.  Wear OS already masks round
 * windows, but clipping in-app keeps custom drawing and edge labels tidy when the
 * user forces the round layout on a square panel (and vice versa).
 */
public final class WatchFrameLayout extends FrameLayout {
    private final Path clipPath = new Path();
    private boolean round;

    public WatchFrameLayout(Context context) {
        this(context, null);
    }

    public WatchFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WatchFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setBackgroundColor(0xFF000000);
    }

    public void setRound(boolean round) {
        if (this.round != round) {
            this.round = round;
            invalidate();
        }
    }

    public boolean isRound() {
        return round;
    }

    @Override
    public void draw(Canvas canvas) {
        if (!round) {
            super.draw(canvas);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float radius = Math.min(w, h) / 2f;
        clipPath.reset();
        clipPath.addCircle(w / 2f, h / 2f, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        super.draw(canvas);
        canvas.restore();
    }
}

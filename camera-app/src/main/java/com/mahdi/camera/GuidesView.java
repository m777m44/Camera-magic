package com.mahdi.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Screen-only composition guides. Nothing drawn here is baked into a photo or a clip; the
 * overlay is preview-only by design and the ABOUT text says so.
 *
 * <p>Four presets (off, thirds, golden ratio, centre cross) plus an optional tilt/level
 * indicator. The indicator animates toward the device roll with {@link Easing#smooth} and
 * settles into a calm green when the phone is level, so the overlay reads as a tool rather
 * than a strobe.
 */
public final class GuidesView extends View {
    /** Grid presets, in the order the GRID chip cycles through them. */
    public static final int GRID_OFF = 0;
    public static final int GRID_THIRDS = 1;
    public static final int GRID_GOLDEN = 2;
    public static final int GRID_CROSS = 3;
    private static final int GRID_COUNT = 4;
    private static final float GOLDEN = 0.381966f;
    private static final long LEVEL_ANIMATION_MS = 160L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Level level = new Level();
    private int grid = GRID_OFF;
    private boolean levelEnabled;
    private boolean safeArea;
    private float drawnRoll;
    private float targetRoll;
    private long rollAnimationStart;
    private float rollAnimationFrom;
    private boolean animating;

    public GuidesView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    private static float density(View view) {
        return view.getResources().getDisplayMetrics().density;
    }

    public void setFrame(float left, float top, float right, float bottom) {
        frame.set(left, top, right, bottom);
        invalidate();
    }

    /** @param mode one of {@link #GRID_OFF}, {@link #GRID_THIRDS}, {@link #GRID_GOLDEN}, {@link #GRID_CROSS} */
    public void setGrid(int mode) {
        grid = mode < 0 ? GRID_OFF : (mode % GRID_COUNT);
        invalidate();
    }

    public int gridMode() {
        return grid;
    }

    /** Next grid preset, for the chip to cycle through. */
    public int nextGridMode() {
        return (grid + 1) % GRID_COUNT;
    }

    public void setLevelEnabled(boolean enabled) {
        levelEnabled = enabled;
        level.reset();
        invalidate();
    }

    public boolean levelEnabled() {
        return levelEnabled;
    }

    /** Draw the title-safe rectangle, which is a framing aid rather than an image effect. */
    public void setSafeAreaEnabled(boolean enabled) {
        safeArea = enabled;
        invalidate();
    }

    public boolean safeAreaEnabled() {
        return safeArea;
    }

    /**
     * Feed one gravity sample. Only consumed while the level is enabled, so a disabled
     * indicator costs nothing and cannot keep the view redrawing.
     */
    public void updateGravity(float x, float y, float z) {
        if (!levelEnabled) return;
        level.update(x, y, z, 0.85f);
        float wanted = level.isLevel() ? 0f : level.correction();
        if (Math.abs(wanted - targetRoll) > 0.05f) {
            rollAnimationFrom = drawnRoll;
            targetRoll = wanted;
            rollAnimationStart = SystemClock.uptimeMillis();
            if (!animating) {
                animating = true;
                postInvalidateOnAnimation();
            }
        }
    }

    public Level level() {
        return level;
    }

    /** Human label for the GRID chip. */
    public static int gridLabelRes(int mode) {
        switch (mode % GRID_COUNT) {
            case GRID_THIRDS: return R.string.grid_thirds;
            case GRID_GOLDEN: return R.string.grid_golden;
            case GRID_CROSS: return R.string.grid_cross;
            default: return R.string.grid_off;
        }
    }

    private void advanceRoll() {
        long elapsed = SystemClock.uptimeMillis() - rollAnimationStart;
        float progress = Easing.smooth(elapsed / (float) LEVEL_ANIMATION_MS);
        drawnRoll = Easing.lerp(rollAnimationFrom, targetRoll, progress);
        if (progress >= 1f) {
            drawnRoll = targetRoll;
            animating = false;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame.isEmpty()) return;
        float density = density(this);
        float stroke = Math.max(1f, getResources().getDimension(R.dimen.mahdi_guide_stroke));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(Color.argb(118, 255, 255, 255));
        paint.setPathEffect(null);
        if (grid == GRID_THIRDS) {
            rule(canvas, 1 / 3f, 2 / 3f);
        } else if (grid == GRID_GOLDEN) {
            rule(canvas, GOLDEN, 1 - GOLDEN);
        } else if (grid == GRID_CROSS) {
            centre(canvas, density * 9f, 2.5f);
        }
        // The centre cross is always drawn: it is the focus point, not a composition rule.
        if (grid != GRID_CROSS) centre(canvas, density * 7f, 2f);
        if (levelEnabled) drawLevel(canvas, density);
        if (safeArea) drawSafeArea(canvas, density);
    }

    private void rule(Canvas canvas, float horizontal, float vertical) {
        float x1 = frame.left + frame.width() * horizontal;
        float x2 = frame.left + frame.width() * vertical;
        float y1 = frame.top + frame.height() * horizontal;
        float y2 = frame.top + frame.height() * vertical;
        canvas.drawLine(x1, frame.top, x1, frame.bottom, paint);
        canvas.drawLine(x2, frame.top, x2, frame.bottom, paint);
        canvas.drawLine(frame.left, y1, frame.right, y1, paint);
        canvas.drawLine(frame.left, y2, frame.right, y2, paint);
    }

    private void centre(Canvas canvas, float arm, float width) {
        paint.setStrokeWidth(Math.max(1f, width * density(this)));
        float cx = frame.centerX();
        float cy = frame.centerY();
        canvas.drawLine(cx - arm, cy, cx + arm, cy, paint);
        canvas.drawLine(cx, cy - arm, cx, cy + arm, paint);
    }

    /** Title-safe marks, so a recording is not framed into a corner that clips on a TV. */
    private void drawSafeArea(Canvas canvas, float density) {
        paint.setColor(Color.argb(70, 255, 255, 255));
        paint.setStrokeWidth(Math.max(1f, density * 0.5f));
        float inset = Math.min(frame.width(), frame.height()) * 0.05f;
        RectF safe = new RectF(frame);
        safe.inset(inset, inset);
        canvas.drawRect(safe, paint);
    }

    private void drawLevel(Canvas canvas, float density) {
        if (animating) {
            advanceRoll();
            postInvalidateOnAnimation();
        }
        boolean flat = level.isLevel() && Math.abs(drawnRoll) < 0.2f;
        int colour = flat ? getContext().getColor(R.color.mahdi_level_ok)
                : level.isNearLevel() ? getContext().getColor(R.color.mahdi_level_warn)
                : Color.argb(150, 255, 255, 255);
        paint.setColor(colour);
        paint.setStrokeWidth(Math.max(1.5f, density * (flat ? 1.6f : 1.1f)));
        paint.setPathEffect(null);
        float cx = frame.centerX();
        float cy = frame.centerY();
        float half = Math.min(frame.width(), frame.height()) * 0.22f;
        canvas.save();
        canvas.rotate(drawnRoll, cx, cy);
        // A horizon line with two end caps: the rotation is the readable part, not the line.
        canvas.drawLine(cx - half, cy, cx + half, cy, paint);
        canvas.drawLine(cx - half, cy - density * 5f, cx - half, cy + density * 5f, paint);
        canvas.drawLine(cx + half, cy - density * 5f, cx + half, cy + density * 5f, paint);
        canvas.restore();
        if (flat) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, density * 2.4f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        // Small tick at the top of the viewport always points "down" toward true horizontal.
        paint.setColor(flat ? colour : Color.argb(120, 255, 255, 255));
        paint.setStrokeWidth(Math.max(1f, density));
        canvas.drawLine(cx, frame.top + density * 6f, cx, frame.top + density * (flat ? 16f : 10f), paint);
        if (!level.isNearLevel()) {
            drawTiltArrow(canvas, density, cx, frame.top + density * 26f, drawnRoll);
        }
    }

    private void drawTiltArrow(Canvas canvas, float density, float cx, float cy, float roll) {
        text.setColor(Color.argb(190, 255, 255, 255));
        text.setTextSize(density * 10f);
        canvas.drawText(roll < 0 ? "↺" : "↻", cx, cy, text);
    }
}

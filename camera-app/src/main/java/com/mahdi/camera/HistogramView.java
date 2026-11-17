package com.mahdi.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small, honest luminance histogram for the viewport: it measures the graded frames the GPU
 * is actually presenting, so what it shows is what the photo will look like.
 *
 * <p>Preview-only, like the guides. The view animates its bar heights toward a new sample so a
 * moving scene does not make the readout strobe; the easing is shared with the rest of the UI.
 */
public final class HistogramView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float[] bars = new float[Histogram.BINS];
    private final float[] target = new float[Histogram.BINS];
    private boolean visible;
    private String advice = "";
    private int sampleFrames;

    public HistogramView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }

    public void setVisible(boolean show) {
        visible = show;
        setVisibility(show ? VISIBLE : GONE);
        invalidate();
    }

    public boolean isShowing() {
        return visible;
    }

    /** Latest advice line, also used by the status strip when the histogram is hidden. */
    public String advice() {
        return advice;
    }

    public int sampleFrames() {
        return sampleFrames;
    }

    /**
     * Feed a new measurement. Bars are smoothed in {@link #onDraw} rather than here, so a
     * burst of samples on a fast device cannot make the view do more work than it draws.
     */
    public void sample(Histogram histogram) {
        if (histogram == null) return;
        advice = histogram.advice();
        sampleFrames++;
        int peak = histogram.peak();
        for (int index = 0; index < Histogram.BINS; index++) {
            // Square-root scaling: raw counts make everything but the mid-tones invisible.
            target[index] = (float) Math.sqrt(histogram.luma[index] / (double) peak);
        }
        invalidate();
    }

    public void clear() {
        for (int index = 0; index < Histogram.BINS; index++) {
            bars[index] = 0f;
            target[index] = 0f;
        }
        advice = "";
        sampleFrames = 0;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;
        float density = getResources().getDisplayMetrics().density;
        box.set(density * 6, density * 6, getWidth() - density * 6, getHeight() - density * 6);
        if (box.width() <= 0 || box.height() <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(120, 11, 15, 20));
        RectF background = new RectF(box);
        background.inset(-density * 4, -density * 4);
        canvas.drawRoundRect(background, density * 8, density * 8, paint);

        // Zone separators at the five stops of a zone system, faint enough to stay a hint.
        paint.setColor(Color.argb(40, 255, 255, 255));
        for (int zone = 1; zone < 5; zone++) {
            float x = box.left + box.width() * zone / 5f;
            canvas.drawRect(x, box.top, x + 1f, box.bottom, paint);
        }

        boolean settled = true;
        for (int index = 0; index < Histogram.BINS; index++) {
            float wanted = target[index];
            float current = bars[index];
            if (Math.abs(wanted - current) > 0.004f) {
                current = Easing.lerp(current, wanted, 0.35f);
                bars[index] = current;
                settled = false;
            } else {
                bars[index] = wanted;
            }
        }
        paint.setColor(Color.argb(210, 81, 214, 199));
        float width = box.width() / Histogram.BINS;
        for (int index = 0; index < Histogram.BINS; index++) {
            float height = bars[index] * box.height();
            if (height < 1f) continue;
            float left = box.left + index * width;
            canvas.drawRect(left, box.bottom - height, left + Math.max(1f, width), box.bottom, paint);
        }
        if (!settled) postInvalidateOnAnimation();
    }
}

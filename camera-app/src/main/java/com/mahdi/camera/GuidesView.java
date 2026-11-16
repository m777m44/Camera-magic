package com.mahdi.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Screen-only composition guides: never misrepresented as a baked-in video effect. */
public final class GuidesView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private boolean grid;

    public GuidesView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setFrame(float left, float top, float right, float bottom) {
        frame.set(left, top, right, bottom);
        invalidate();
    }

    public void setGrid(boolean enabled) { grid = enabled; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame.isEmpty()) return;
        paint.setColor(Color.argb(130, 255, 255, 255));
        paint.setStrokeWidth(getResources().getDisplayMetrics().density);
        paint.setStyle(Paint.Style.STROKE);
        if (grid) {
            for (int i = 1; i < 3; i++) {
                float x = frame.left + frame.width() * i / 3;
                float y = frame.top + frame.height() * i / 3;
                canvas.drawLine(x, frame.top, x, frame.bottom, paint);
                canvas.drawLine(frame.left, y, frame.right, y, paint);
            }
        }
        float radius = 8 * getResources().getDisplayMetrics().density;
        canvas.drawLine(frame.centerX() - radius, frame.centerY(),
                frame.centerX() + radius, frame.centerY(), paint);
        canvas.drawLine(frame.centerX(), frame.centerY() - radius,
                frame.centerX(), frame.centerY() + radius, paint);
    }
}

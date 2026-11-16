package com.mahdi.camera;

import android.content.Context;
import android.view.TextureView;

/** Letterboxes rather than stretching a camera buffer into a different aspect ratio. */
public final class PreviewView extends TextureView {
    private int ratioWidth = 9;
    private int ratioHeight = 16;

    public PreviewView(Context context) { super(context); }

    public void setAspectRatio(int width, int height) {
        if (width > 0 && height > 0) {
            ratioWidth = width;
            ratioHeight = height;
            requestLayout();
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if ((long) width * ratioHeight > (long) height * ratioWidth) {
            width = height * ratioWidth / ratioHeight;
        } else {
            height = width * ratioHeight / ratioWidth;
        }
        setMeasuredDimension(width, height);
    }
}

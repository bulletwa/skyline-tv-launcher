package com.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Row of small page dots; the selected one is a longer pill. */
final class DotsView extends View {
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int count, selected;

    DotsView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void set(int count, int selected) {
        if (this.count != count || this.selected != selected) {
            this.count = count;
            this.selected = selected;
            requestLayout();
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) (Math.max(count, 1) * 12 * d + 12 * d), (int) (6 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float x = 0;
        for (int i = 0; i < count; i++) {
            boolean s = i == selected;
            float w = s ? 14 * d : 6 * d;
            p.setColor(s ? Color.WHITE : Color.argb(100, 255, 255, 255));
            c.drawRoundRect(x, 0, x + w, 6 * d, 3 * d, 3 * d, p);
            x += w + 6 * d;
        }
    }
}

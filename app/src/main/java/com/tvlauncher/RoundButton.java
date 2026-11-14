package com.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** 36dp frosted circle with a Wi-Fi or settings glyph. */
final class RoundButton extends View {
    static final int WIFI = 0, GEAR = 1;
    private final int kind;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private LinearGradient grad;
    private int gradInk, gradH;
    private int ink = Color.WHITE; // glyph, outline and fill tint; follows the sky (see MainActivity.applyInk)

    /** White on dark skies, dark on bright ones. */
    void setInk(int rgb) {
        if (ink == rgb) return;
        ink = rgb;
        invalidate();
    }

    RoundButton(Context c, int kind) {
        super(c);
        this.kind = kind;
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(1.8f * d);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = (int) (36 * d);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        float s = gained ? 1.12f : 1f;
        animate().scaleX(s).scaleY(s).setDuration(140).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f, r = w / 2f;
        boolean f = isFocused();
        boolean darkInk = Contrast.luminance(ink) < 0.4;
        int r0 = Color.red(ink), g0 = Color.green(ink), b0 = Color.blue(ink);
        int glyph;
        if (f) { // focused: a solid disc in the opposite tone, so it stands out against the sky either way
            fill.setShader(null);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(darkInk ? Color.rgb(14, 18, 24) : Color.rgb(245, 245, 247));
            c.drawCircle(cx, cy, r, fill);
            glyph = darkInk ? Color.WHITE : Color.rgb(28, 28, 32);
        } else {
            fill.setStyle(Paint.Style.FILL);
            if (grad == null || gradInk != ink || gradH != getHeight()) { // rebuilt only when the colour or size changes
                grad = new LinearGradient(0, 0, 0, getHeight(),
                        Color.argb(46, r0, g0, b0), Color.argb(20, r0, g0, b0), Shader.TileMode.CLAMP);
                gradInk = ink;
                gradH = getHeight();
            }
            fill.setShader(grad);
            c.drawCircle(cx, cy, r, fill);
            fill.setShader(null);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(d);
            fill.setColor(Color.argb(darkInk ? 90 : 56, r0, g0, b0));
            c.drawCircle(cx, cy, r - d / 2f, fill);
            glyph = ink;
        }
        line.setColor(glyph);
        line.setStyle(Paint.Style.STROKE);
        if (kind == WIFI) {
            float py = cy + 6 * d;
            for (int i = 1; i <= 3; i++) {
                float rr = i * 4.4f * d;
                oval.set(cx - rr, py - rr, cx + rr, py + rr);
                c.drawArc(oval, -135, 90, false, line);
            }
            line.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, py, 1.6f * d, line);
        } else {
            float ro = 8.6f * d;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                c.drawLine(cx + (float) Math.cos(a) * (ro - 2.6f * d), cy + (float) Math.sin(a) * (ro - 2.6f * d),
                        cx + (float) Math.cos(a) * ro, cy + (float) Math.sin(a) * ro, line);
            }
            c.drawCircle(cx, cy, 5.6f * d, line);
            c.drawCircle(cx, cy, 2.2f * d, line);
        }
    }
}

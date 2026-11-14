package com.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/** Banner for the PlayStation entry, drawn here: a deep-blue tile with "PS5" and the HDMI port (no logos). */
final class Ps5Banner {
    private Ps5Banner() {}

    static void draw(Context ctx, Canvas c, int w, int h, String name, String detail) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setShader(new LinearGradient(0, 0, w, h, 0xFF1F4FB8, 0xFF081431, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        p.setTypeface(Fonts.get(ctx, 600));
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTextSize(h * 0.40f);
        c.drawText("PS5", w / 2f, h * 0.55f, p);

        p.setTypeface(Fonts.get(ctx, 400));
        p.setColor(Color.argb(200, 255, 255, 255));
        p.setTextSize(h * 0.115f);
        p.setLetterSpacing(0.12f);
        String sub = detail == null || detail.isEmpty() ? name : detail;
        c.drawText(sub.toUpperCase(java.util.Locale.ENGLISH), w / 2f, h * 0.80f, p);
    }
}

package com.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Calendar;
import java.util.Locale;

/** A small sunrise-to-sunset arc with a dot where the sun is now; a dimmed dot on the baseline at night. */
final class DayArc extends android.view.View {
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private int sunrise = -1, sunset = -1;
    private int nowMin = -1; // test hook; -1 = read the clock

    DayArc(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Fonts.get(c, 400));
        text.setTextSize(9 * d);
    }

    void set(int sunriseMin, int sunsetMin) {
        sunrise = sunriseMin;
        sunset = sunsetMin;
        setVisibility(sunriseMin >= 0 && sunsetMin > sunriseMin ? VISIBLE : GONE);
        invalidate();
    }

    private int now() {
        if (nowMin >= 0) return nowMin;
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** Position 0..1 along the arc, clamped, and whether the sun is up. */
    private double progress() {
        return SkyModel.dayProgress(sunrise, sunset, now());
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(86 * d), Math.round(44 * d));
    }

    @Override protected void onDraw(Canvas c) {
        double p = progress();
        if (Double.isNaN(p)) return;
        boolean up = p >= 0 && p <= 1;
        float cx = getWidth() / 2f, base = 28 * d, rx = 32 * d, ry = 22 * d;
        line.setStrokeWidth(1.4f * d);
        line.setColor(Color.argb(70, 255, 255, 255));
        oval.set(cx - rx, base - ry, cx + rx, base + ry);
        c.drawArc(oval, 180, 180, false, line);
        line.setColor(Color.argb(110, 255, 255, 255));
        c.drawLine(cx - rx - 6 * d, base, cx + rx + 6 * d, base, line);
        double q = Math.max(0, Math.min(1, p));
        double ang = Math.PI * (1 - q);
        float x = cx + (float) Math.cos(ang) * rx, y = base - (float) Math.sin(ang) * ry;
        if (up) {
            fill.setColor(Color.argb(60, 255, 214, 64));
            c.drawCircle(x, y, 8 * d, fill);
            fill.setColor(Color.rgb(255, 214, 64));
        } else {
            fill.setColor(Color.argb(150, 220, 226, 245));
        }
        c.drawCircle(x, y, 3.6f * d, fill);
        text.setColor(Color.argb(190, 255, 255, 255));
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(clock(sunrise), cx - rx - 6 * d, base + 12 * d, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(clock(sunset), cx + rx + 6 * d, base + 12 * d, text);
    }

    private static String clock(int m) {
        return String.format(Locale.ENGLISH, "%d:%02d", m / 60, m % 60);
    }

    /** Debug dump: what is drawn, e.g. "arc 6:05-17:44 up 62%" or "arc 6:05-17:44 night". */
    String drawnSummary() {
        double p = progress();
        if (Double.isNaN(p) || getVisibility() != VISIBLE) return "arc none";
        return "arc " + clock(sunrise) + "-" + clock(sunset) + (p >= 0 && p <= 1 ? " up " + Math.round(p * 100) + "%" : " night");
    }
}

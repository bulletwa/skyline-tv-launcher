package com.tvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Seven columns: weekday, a small drawn weather symbol, high and low. Drawn by hand so it needs no icon assets. */
final class WeekStrip extends View {
    private final float d;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, medium;
    private List<WeatherState.Day> days = new ArrayList<>();

    WeekStrip(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        regular = Fonts.get(c, 300);
        medium = Fonts.get(c, 500);
        text.setTextAlign(Paint.Align.CENTER);
        ink.setStrokeCap(Paint.Cap.ROUND);
    }

    private List<WeatherState.Hour> hours = new ArrayList<>();
    private double nowTemp = Double.NaN;
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float HOUR_H = 50, DAY_H = 30, GAP = 6;

    void set(List<WeatherState.Day> days, List<WeatherState.Hour> hours, double nowTemp) {
        this.days = days;
        this.hours = hours;
        this.nowTemp = nowTemp;
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        float hh = (hours.isEmpty() ? 0 : HOUR_H + GAP) + (days.isEmpty() ? 0 : DAY_H);
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(hh * d));
    }

    @Override protected void onDraw(Canvas c) {
        float y = 0;
        tile.setColor(Color.argb(34, 255, 255, 255));
        if (!hours.isEmpty()) {
            rect.set(0, 0, getWidth(), HOUR_H * d);
            c.drawRoundRect(rect, 10 * d, 10 * d, tile);
            int n = hourCols();
            float colW = getWidth() / (float) n;
            for (int i = 0; i < n; i++) {
                WeatherState.Hour hr = hours.get(i);
                float cx = colW * (i + 0.5f);
                text.setTypeface(i == 0 ? medium : regular);
                text.setTextSize(9.5f * d);
                text.setColor(Color.argb(i == 0 ? 255 : 190, 255, 255, 255));
                c.drawText(i == 0 ? "Now" : hourLabel(hr.hour), cx, 13 * d, text);
                icon(c, hr.code, cx, 27 * d, 7 * d, hr.day);
                text.setTypeface(medium);
                text.setTextSize(12 * d);
                text.setColor(Color.WHITE);
                c.drawText(hr.temp + "°", cx, 46 * d, text);
            }
            y = (HOUR_H + GAP) * d;
        }
        if (days.isEmpty()) return;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (WeatherState.Day dy : days) {
            lo = Math.min(lo, dy.min);
            hi = Math.max(hi, dy.max);
        }
        float colW = getWidth() / (float) days.size();
        float span = Math.max(1, hi - lo), bh = 4 * d;
        rect.set(0, y, getWidth(), y + DAY_H * d);
        c.drawRoundRect(rect, 10 * d, 10 * d, tile);
        for (int i = 0; i < days.size(); i++) {
            WeatherState.Day day = days.get(i);
            float cx = colW * (i + 0.5f), x0 = colW * i + 5 * d, x1 = colW * (i + 1) - 5 * d;
            text.setTypeface(i == 0 ? medium : regular);
            text.setTextSize(9.5f * d);
            text.setColor(Color.argb(i == 0 ? 255 : 190, 255, 255, 255));
            c.drawText(label(i, colW), cx, y + 12 * d, text);
            float by = y + 21 * d;
            // vertical-in-week range: the pill sits where this day's lo..hi falls between the week's extremes
            float a = x0 + (x1 - x0) * (day.min - lo) / span, b = x0 + (x1 - x0) * (day.max - lo) / span;
            if (b - a < 6 * d) b = a + 6 * d;
            bar.setShader(null);
            bar.setColor(Color.argb(60, 255, 255, 255));
            rect.set(x0, by, x1, by + bh);
            c.drawRoundRect(rect, bh / 2, bh / 2, bar);
            bar.setColor(Color.WHITE); // paint alpha would scale the shader
            bar.setShader(new android.graphics.LinearGradient(a, 0, b, 0, 0xFF000000 | tempColor(day.min), 0xFF000000 | tempColor(day.max),
                    android.graphics.Shader.TileMode.CLAMP));
            rect.set(a, by, Math.min(b, x1), by + bh);
            c.drawRoundRect(rect, bh / 2, bh / 2, bar);
            if (i == 0 && !Double.isNaN(nowTemp)) {
                float dx = Math.max(x0 + 2 * d, Math.min(x1 - 2 * d, x0 + (x1 - x0) * ((float) nowTemp - lo) / span));
                bar.setShader(null);
                bar.setColor(Color.WHITE);
                c.drawCircle(dx, by + bh / 2, 3 * d, bar);
            }
        }
        bar.setShader(null);
    }

    private int hourCols() {
        return Math.max(1, Math.min(hours.size(), (int) (getWidth() / (38 * d))));
    }

    private static String hourLabel(int h) {
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? "am" : "pm");
    }

    /** Cool blue through yellow to orange, like Apple's temperature bars. */
    private static int tempColor(int t) {
        float f = Math.max(0, Math.min(1, (t - 5) / 40f));
        return f < 0.5f ? SkyModel.mix(Color.rgb(100, 190, 255), Color.rgb(255, 214, 64), f * 2)
                : SkyModel.mix(Color.rgb(255, 214, 64), Color.rgb(255, 130, 50), (f - 0.5f) * 2);
    }

    /** The heading drawn over column i: "Today" when it fits the column, else "Now"; never a chopped word. */
    private String label(int i, float colW) {
        if (i > 0) return days.get(i).name;
        text.setTypeface(medium);
        text.setTextSize(9.5f * d);
        return text.measureText("Today") <= colW - 2 * d ? "Today" : "Now";
    }

    /** Debug dump: what is actually drawn, e.g. "Today 27/19, Sun 28/18". */
    String drawnSummary() {
        if (days.isEmpty() || getWidth() == 0) return days.isEmpty() ? "none" : "not laid out";
        float colW = getWidth() / (float) days.size();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < days.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(label(i, colW)).append(' ').append(days.get(i).max).append('/').append(days.get(i).min);
        }
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < hourCols() && i < hours.size(); i++) {
            WeatherState.Hour h = hours.get(i);
            hb.append(i == 0 ? "" : ", ").append(i == 0 ? "Now" : hourLabel(h.hour)).append(' ').append(h.temp)
                    .append(h.day ? "" : "n");
        }
        return b + " (col " + Math.round(colW / d) + "dp) | hours " + (hb.length() == 0 ? "none" : hb)
                + " | height " + Math.round(getHeight() / d) + "dp";
    }

    /** Symbol for a WMO weather code, centred on (x, y), about 2r wide. */
    private void icon(Canvas c, int code, float x, float y, float r, boolean day) {
        ink.setColor(Color.WHITE);
        ink.setStrokeWidth(1.6f * d);
        boolean storm = code >= 95, snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        if (code == 45 || code == 48) {
            for (int k = -1; k <= 1; k++) {
                float yy = y + k * 5 * d;
                c.drawLine(x - r, yy, x + r, yy, ink);
            }
        } else if (storm || snow || rain) {
            cloud(c, x, y - 2 * d, r * 0.85f, Color.argb(235, 255, 255, 255));
            if (storm) {
                ink.setColor(Color.rgb(255, 214, 64));
                c.drawLine(x + 1 * d, y + 3 * d, x - 2 * d, y + 8 * d, ink);
                c.drawLine(x - 2 * d, y + 8 * d, x + 2 * d, y + 8 * d, ink);
                c.drawLine(x + 2 * d, y + 8 * d, x - 1 * d, y + 13 * d, ink);
            } else if (snow) {
                ink.setStyle(Paint.Style.FILL);
                for (int k = -1; k <= 1; k++) c.drawCircle(x + k * 4.5f * d, y + 9 * d, 1.3f * d, ink);
                ink.setStyle(Paint.Style.STROKE);
            } else {
                ink.setColor(Color.rgb(150, 205, 255));
                for (int k = -1; k <= 1; k++) c.drawLine(x + k * 4.5f * d + 1 * d, y + 6 * d, x + k * 4.5f * d - 1 * d, y + 11 * d, ink);
            }
        } else if (code <= 1 && !day) {
            moon(c, x, y, r * 0.75f);
        } else if (code <= 1) {
            sun(c, x, y, r * 0.6f);
        } else if (code == 2) {
            sun(c, x - 3 * d, y - 3 * d, r * 0.45f);
            cloud(c, x + 2 * d, y + 2 * d, r * 0.8f, Color.argb(240, 255, 255, 255));
        } else {
            cloud(c, x, y, r, Color.argb(240, 255, 255, 255));
        }
        ink.setStyle(Paint.Style.STROKE);
    }

    private final android.graphics.Path crescent = new android.graphics.Path(), cut = new android.graphics.Path();

    private void moon(Canvas c, float x, float y, float r) {
        crescent.rewind();
        cut.rewind();
        crescent.addCircle(x, y, r, android.graphics.Path.Direction.CW);
        cut.addCircle(x + r * 0.55f, y - r * 0.3f, r * 0.85f, android.graphics.Path.Direction.CW);
        crescent.op(cut, android.graphics.Path.Op.DIFFERENCE);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(Color.argb(240, 235, 240, 255));
        c.drawPath(crescent, ink);
        ink.setStyle(Paint.Style.STROKE);
    }

    private void sun(Canvas c, float x, float y, float r) {
        int col = Color.rgb(255, 214, 64);
        ink.setColor(col);
        ink.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, ink);
        ink.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            c.drawLine(x + (float) Math.cos(a) * (r + 2 * d), y + (float) Math.sin(a) * (r + 2 * d),
                    x + (float) Math.cos(a) * (r + 4.5f * d), y + (float) Math.sin(a) * (r + 4.5f * d), ink);
        }
    }

    private void cloud(Canvas c, float x, float y, float r, int col) {
        ink.setColor(col);
        ink.setStyle(Paint.Style.FILL);
        c.drawCircle(x - r * 0.45f, y + r * 0.15f, r * 0.5f, ink);
        c.drawCircle(x + r * 0.1f, y - r * 0.2f, r * 0.62f, ink);
        c.drawCircle(x + r * 0.6f, y + r * 0.2f, r * 0.45f, ink);
        c.drawRect(x - r * 0.45f, y + r * 0.1f, x + r * 0.6f, y + r * 0.65f, ink);
        ink.setStyle(Paint.Style.STROKE);
    }
}

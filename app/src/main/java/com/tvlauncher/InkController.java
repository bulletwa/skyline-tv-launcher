package com.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Colour of the clock, date, weather line and the two round buttons. White or dark ink is picked from what the sky
 * really looks like behind each (see {@link InkSampler}), re-measured every so often. Before the first measurement
 * a model of the sky ({@link Contrast}) stands in.
 */
final class InkController {
    private final MainActivity a;
    int inkNow = Color.WHITE;
    android.animation.ValueAnimator inkAnim;

    InkController(MainActivity a) {
        this.a = a;
    }

    void start() {
        a.ui.removeCallbacks(inkSample);
        a.ui.postDelayed(inkSample, 400);
    }

    void stop() {
        a.ui.removeCallbacks(inkSample);
    }

    void applyInk(boolean animate) {
        SkyRenderer.Input in = a.lastSky;
        if (in == null || a.infoLine == null || inkMeasured) return; // once the real sky is being read, it decides
        float[] za = Contrast.zoneAlphas(in);
        int zone = a.headerGrown ? 1 : 0;
        int bg = SkyModel.scale(Contrast.worstCase(in, Contrast.ZONES[zone]), 1 - za[zone]);
        int target = 0xFF000000 | Contrast.inkFor(bg, a.headerGrown ? 3.0 : 4.5);
        android.util.Log.d("Ink", String.format(java.util.Locale.ENGLISH, "zone %d bg #%06X white %.1f dark %.1f -> %s", zone, bg, Contrast.whiteOn(bg), Contrast.darkOn(bg), (target & 0xFFFFFF) == Contrast.LIGHT_INK ? "white" : "dark"));
        if (target == inkNow) return;
        if (inkAnim != null) inkAnim.cancel();
        if (!animate) {
            paintInk(target);
            return;
        }
        final int from = inkNow;
        inkAnim = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), from, target);
        inkAnim.setDuration(500);
        inkAnim.addUpdateListener(an -> paintInk((int) an.getAnimatedValue()));
        inkAnim.start();
    }

    void paintInk(int argb) {
        inkNow = argb;
        int rgb = argb & 0xFFFFFF;
        a.infoLine.setTextColor(0xBE000000 | rgb);
        a.clockView.setTextColor(0xFF000000 | rgb);
        a.dateView.setTextColor(0xD2000000 | rgb);
        if (a.dividerView != null) a.dividerView.setBackgroundColor(0x50000000 | rgb);
        if (!inkMeasured) paintButtonInk(argb);
    }

    void paintButtonInk(int argb) {
        btnInkNow = argb;
        for (RoundButton b : a.headerButtons) b.setInk(0xFF000000 | (argb & 0xFFFFFF));
    }

    // ---- text and icon colour measured from the real a.sky (clouds, sun glow and all), twice a second or so
    boolean inkMeasured;
    int btnInkNow = Color.WHITE;
    android.animation.ValueAnimator btnInkAnim;
    boolean textWantsDark;
    String inkReport = "-";
    static final long INK_SAMPLE_MS = 700;

    final Runnable inkSample = new Runnable() {
        @Override public void run() {
            if (!a.started) return;
            sampleInk();
            a.ui.postDelayed(this, INK_SAMPLE_MS);
        }
    };

    static android.graphics.Rect textRect(TextView t, float scale) {
        android.graphics.Rect r = new android.graphics.Rect();
        if (!t.getGlobalVisibleRect(r)) return null;
        // the view can be wider than its text; only the words need to be readable
        int w = Math.round(t.getPaint().measureText(t.getText().toString()) * scale + t.getPaddingLeft() * scale);
        r.right = Math.min(r.right, r.left + w);
        return r;
    }

    void sampleInk() {
        android.view.SurfaceView sv = a.sky.surface();
        if (a.infoLine == null) return;
        float sc = a.headerLeft.getScaleX();
        final List<android.graphics.Rect> text = new ArrayList<>(), btn = new ArrayList<>();
        for (TextView t : new TextView[]{a.infoLine, a.clockView, a.dateView}) {
            android.graphics.Rect r = textRect(t, sc);
            if (r != null) text.add(r);
        }
        for (RoundButton b : a.headerButtons) {
            android.graphics.Rect r = new android.graphics.Rect();
            if (b.getGlobalVisibleRect(r)) btn.add(r);
        }
        textRects = rectsText(text);
        btnRects = rectsText(btn);
        if (sv != null && sv.getVisibility() == android.view.View.VISIBLE && sv.getHolder().getSurface().isValid()) {
            InkSampler.sample(sv, text, a.ui, m -> onInkMeasured(m, false));
            InkSampler.sample(sv, btn, a.ui, m -> onInkMeasured(m, true));
        } else { // animation off: the still wallpaper is what is on screen
            int w = a.getResources().getDisplayMetrics().widthPixels;
            onInkMeasured(InkSampler.fromBitmap(a.sky.currentBitmap(), text, w), false);
            onInkMeasured(InkSampler.fromBitmap(a.sky.currentBitmap(), btn, w), true);
        }
    }

    void onInkMeasured(float[] m, boolean buttons) {
        if (m == null) return;
        inkMeasured = true;
        int now = buttons ? btnInkNow : inkNow;
        boolean dark = (now & 0xFFFFFF) == Contrast.DARK_INK || Color.red(now) < 128;
        boolean wantDark = buttons ? Contrast.buttonDark(textWantsDark, m[0], m[1], 0.16, 3.0)
                : Contrast.useDark(m[0], m[1], 4.5, dark, 0);
        if (!buttons) textWantsDark = wantDark;
        inkReport = (buttons ? "btn " : "text ") + String.format(java.util.Locale.ENGLISH, "bright=%.2f dark=%.2f", m[0], m[1]);
        if (buttons) lastBtnReport = inkReport + (wantDark ? " -> dark" : " -> white"); else lastTextReport = inkReport + (wantDark ? " -> dark" : " -> white");
        if (wantDark == dark) return;
        final int target = 0xFF000000 | (wantDark ? Contrast.DARK_INK : Contrast.LIGHT_INK);
        if (buttons) {
            if (btnInkAnim != null) btnInkAnim.cancel();
            btnInkAnim = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), now, target);
            btnInkAnim.setDuration(400);
            btnInkAnim.addUpdateListener(an -> paintButtonInk((int) an.getAnimatedValue()));
            btnInkAnim.start();
        } else {
            if (inkAnim != null) inkAnim.cancel();
            inkAnim = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), now, target);
            inkAnim.setDuration(400);
            inkAnim.addUpdateListener(an -> paintInk((int) an.getAnimatedValue()));
            inkAnim.start();
        }
    }

    String textRects = "", btnRects = "";

    static String rectsText(List<android.graphics.Rect> l) {
        StringBuilder sb = new StringBuilder();
        for (android.graphics.Rect r : l) sb.append(sb.length() > 0 ? ";" : "").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom);
        return sb.toString();
    }

    String lastTextReport = "-", lastBtnReport = "-";
}

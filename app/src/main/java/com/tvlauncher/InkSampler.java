package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads what the sky really looks like behind some on-screen rectangles (clouds, sun glow and all), so the text and
 * icon colour can follow the picture instead of a model of it. The result is {brightest, darkest}: the 85th and 15th
 * percentile WCAG luminance over all the rectangles, which are the pixels white and dark ink respectively struggle with.
 */
final class InkSampler {
    private InkSampler() {}

    private static final float[] LIN = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LIN[i] = (float) (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** Calls {@code done} on {@code h}'s thread with {bright, dark}, or null if the sky could not be read. */
    static void sample(final SurfaceView sv, List<Rect> screenRects, final Handler h, final Consumer<float[]> done) {
        final Rect frame = sv.getHolder().getSurfaceFrame();
        if (!sv.getHolder().getSurface().isValid() || sv.getWidth() == 0 || frame.isEmpty() || screenRects.isEmpty()) {
            done.accept(null);
            return;
        }
        final float k = frame.width() / (float) sv.getWidth();
        final int n = screenRects.size();
        final float[] bright = {0}, dark = {1};
        final int[] left = {n};
        final boolean[] failed = {false};
        for (Rect r : screenRects) {
            Rect src = new Rect(Math.round(r.left * k), Math.round(r.top * k), Math.round(r.right * k), Math.round(r.bottom * k));
            if (!src.intersect(frame) || src.width() < 2 || src.height() < 2) {
                if (--left[0] == 0) finish(failed[0], bright, dark, done);
                continue;
            }
            final Bitmap bmp = Bitmap.createBitmap(Math.max(2, src.width() / 6), Math.max(2, src.height() / 6), Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(sv, src, bmp, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        float[] p = percentiles(bmp);
                        bright[0] = Math.max(bright[0], p[0]);
                        dark[0] = Math.min(dark[0], p[1]);
                    } else {
                        failed[0] = true;
                    }
                    bmp.recycle();
                    if (--left[0] == 0) finish(failed[0], bright, dark, done);
                }, h);
            } catch (RuntimeException e) {
                failed[0] = true;
                bmp.recycle();
                if (--left[0] == 0) finish(true, bright, dark, done);
            }
        }
    }

    /** The same measurement from the still wallpaper picture, for when the live sky is off (the surface is hidden then). */
    static float[] fromBitmap(Bitmap b, List<Rect> screenRects, int screenW) {
        if (b == null || b.isRecycled() || screenRects.isEmpty()) return null;
        float k = b.getWidth() / (float) screenW;
        float bright = 0, dark = 1;
        boolean any = false;
        for (Rect r : screenRects) {
            int l = Math.max(0, Math.round(r.left * k)), tp = Math.max(0, Math.round(r.top * k));
            int rr = Math.min(b.getWidth(), Math.round(r.right * k)), bt = Math.min(b.getHeight(), Math.round(r.bottom * k));
            if (rr - l < 2 || bt - tp < 2) continue;
            int w = rr - l, h = bt - tp;
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, l, tp, w, h);
            float[] lum = new float[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                lum[i] = 0.2126f * LIN[(c >> 16) & 255] + 0.7152f * LIN[(c >> 8) & 255] + 0.0722f * LIN[c & 255];
            }
            Arrays.sort(lum);
            bright = Math.max(bright, lum[Math.min(lum.length - 1, (int) (lum.length * 0.85f))]);
            dark = Math.min(dark, lum[(int) (lum.length * 0.15f)]);
            any = true;
        }
        return any ? new float[]{bright, dark} : null;
    }

    private static void finish(boolean failed, float[] bright, float[] dark, Consumer<float[]> done) {
        done.accept(failed ? null : new float[]{bright[0], dark[0]});
    }

    /** {85th, 15th} percentile luminance of a bitmap. */
    static float[] percentiles(Bitmap b) {
        int w = b.getWidth(), hgt = b.getHeight();
        int[] px = new int[w * hgt];
        b.getPixels(px, 0, w, 0, 0, w, hgt);
        float[] lum = new float[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            lum[i] = 0.2126f * LIN[(c >> 16) & 255] + 0.7152f * LIN[(c >> 8) & 255] + 0.0722f * LIN[c & 255];
        }
        Arrays.sort(lum);
        return new float[]{lum[Math.min(lum.length - 1, (int) (lum.length * 0.85f))], lum[(int) (lum.length * 0.15f)]};
    }
}

package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared frosted-glass source. Android 9-11 TVs have no live blur, so we keep a small (240x135)
 * box-blurred copy of the wallpaper and let each panel draw the slice behind it.
 */
final class Glass {
    static final int BW = 240, BH = 135;

    private static final Glass INSTANCE = new Glass();

    static Glass get() {
        return INSTANCE;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Pools.fixed(1, "glass");
    private final Set<GlassDrawable> users = Collections.newSetFromMap(new WeakHashMap<GlassDrawable, Boolean>());
    private Bitmap blurred;   // wallpaper, blurred
    private Bitmap small;     // wallpaper, 240x135, not blurred (backdrop base for dialogs)
    private int generation;

    private Glass() {}

    Bitmap blurred() {
        return blurred;
    }

    void register(GlassDrawable d) {
        users.add(d);
    }

    /** Call whenever the visible wallpaper changes. Blurring happens off the UI thread. */
    void setWallpaper(final Bitmap wp) {
        final int gen = ++generation;
        final Bitmap sm = Bitmap.createScaledBitmap(wp, BW, BH, true);
        small = sm;
        worker.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = sm.copy(Bitmap.Config.ARGB_8888, true);
                blur(b, 6, 3);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return; // a newer wallpaper is already on its way
                        blurred = b;
                        for (GlassDrawable d : users) d.invalidateSelf();
                    }
                });
            }
        });
    }

    /**
     * What is behind a dialog: the launcher's root view drawn in software at 1/8 scale over the plain
     * wallpaper (a SurfaceView clears its area in a software draw, so the wallpaper underneath shows),
     * then blurred so cards and banners show softly through the glass.
     */
    Bitmap captureBackdrop(View root) {
        Bitmap b = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        if (small != null) c.drawBitmap(small, 0, 0, null);
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            // Draw the launcher into its own transparent layer: a SurfaceView clears its whole area in a
            // software draw, which would wipe anything drawn before it. Then lay the layer over the wallpaper.
            Bitmap layer = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
            Canvas lc = new Canvas(layer);
            lc.scale(BW / (float) root.getWidth(), BH / (float) root.getHeight());
            try {
                root.draw(lc);
            } catch (RuntimeException ignored) {
                // a view that cannot draw in software just leaves the plain wallpaper
            }
            c.drawBitmap(layer, 0, 0, null);
            layer.recycle();
        }
        blur(b, 3, 3);
        return b;
    }

    // ------------------------------------------------------------------ box blur

    /** In-place box blur, {@code passes} times in each direction (3 passes approximate a gaussian). */
    static void blur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] line = new int[Math.max(w, h)];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < h; y++) blurLine(px, y * w, 1, w, radius, line);
            for (int x = 0; x < w; x++) blurLine(px, x, w, h, radius, line);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] px, int off, int stride, int n, int r, int[] line) {
        for (int i = 0; i < n; i++) line[i] = px[off + i * stride];
        int div = 2 * r + 1;
        int sr = 0, sg = 0, sb = 0;
        for (int k = -r; k <= r; k++) {
            int c = line[Math.max(0, Math.min(n - 1, k))];
            sr += (c >> 16) & 255;
            sg += (c >> 8) & 255;
            sb += c & 255;
        }
        for (int i = 0; i < n; i++) {
            px[off + i * stride] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
            int add = line[Math.min(n - 1, i + r + 1)], sub = line[Math.max(0, i - r)];
            sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
            sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
            sb += (add & 255) - (sub & 255);
        }
    }
}

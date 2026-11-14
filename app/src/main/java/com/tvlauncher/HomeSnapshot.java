package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;

/**
 * A small picture of the home screen, taken when the launcher goes to the background. When Home is pressed, the
 * service puts it over Google TV's home screen for the moment it takes our launcher to come back, so you never see
 * the other launcher (see {@link HomeWatcherService}).
 */
final class HomeSnapshot {
    private HomeSnapshot() {}

    static final int W = 640, H = 360;
    /** The latest snapshot, or null before the first one. */
    static volatile Bitmap bmp;
    private static Bitmap layer;

    /**
     * @param root   the launcher's root view
     * @param sky    the current sky picture (drawn first: a SurfaceView clears its own area in a software draw)
     * @param hidden views to leave out, e.g. the clock, which would be out of date by the time it is shown
     */
    static synchronized void capture(View root, Bitmap sky, View... hidden) {
        if (root.getWidth() == 0 || root.getHeight() == 0) return;
        Bitmap b = bmp;
        if (b == null || b.isRecycled()) b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        if (layer == null) layer = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        if (sky != null && !sky.isRecycled()) c.drawBitmap(sky, null, new Rect(0, 0, W, H), null);
        layer.eraseColor(Color.TRANSPARENT);
        Canvas lc = new Canvas(layer);
        lc.scale(W / (float) root.getWidth(), H / (float) root.getHeight());
        int[] vis = new int[hidden.length];
        for (int i = 0; i < hidden.length; i++) {
            vis[i] = hidden[i].getVisibility();
            hidden[i].setVisibility(View.INVISIBLE);
        }
        try {
            root.draw(lc);
        } catch (RuntimeException ignored) {
            // a view that cannot draw in software just leaves the plain sky
        } finally {
            for (int i = 0; i < hidden.length; i++) hidden[i].setVisibility(vis[i]);
        }
        c.drawBitmap(layer, 0, 0, null);
        bmp = b;
    }

    /** Give the memory back (the launcher has been in the background for a while). */
    static synchronized void release() {
        bmp = null;
        layer = null;
    }
}

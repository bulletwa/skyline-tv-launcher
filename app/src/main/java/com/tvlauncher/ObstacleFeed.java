package com.tvlauncher;

import android.view.View;

import java.util.List;

/** Tells the sky where the cards and dock are, so rain and snow treat them as solid objects (debounced). */
final class ObstacleFeed {
    private final MainActivity a;

    ObstacleFeed(MainActivity a) {
        this.a = a;
    }

    // ---------------------------------------------------------------- solid objects for rain and snow

    final Runnable obstacleUpdate = this::updateObstacles;

    /** Debounced: layout, scrolling, focus zoom and clean mode all move the cards and dock. */
    void scheduleObstacles() {
        a.ui.removeCallbacks(obstacleUpdate);
        a.ui.postDelayed(obstacleUpdate, 120);
    }

    /**
     * Tells the sky where the cards and the dock are, so raindrops splash on them and snow settles on them like on
     * real objects. Nothing is solid while they are hidden (clean mode).
     */
    void updateObstacles() {
        if (a.sky == null || a.homeCards == null) return;
        java.util.List<float[]> rects = new java.util.ArrayList<>();
        float k = SkyRenderer.W / (float) a.getResources().getDisplayMetrics().widthPixels;
        if (!a.clean) {
            View row = a.homeCards.rowView();
            if (row.getVisibility() == View.VISIBLE && row.getAlpha() > 0.5f) {
                for (CardView cv : a.homeCards.visibleViews()) addObstacle(rects, cv, 16, k);
            }
        }
        if (a.dockBand.getVisibility() == View.VISIBLE && a.dockBand.getAlpha() > 0.5f) addObstacle(rects, a.dockBand, 28 * a.dockScale, k);
        a.sky.setObstacles(Obstacles.flatten(rects));
    }

    void addObstacle(java.util.List<float[]> out, View v, float radiusDp, float k) {
        if (v.getWidth() == 0) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float w = v.getWidth() * v.getScaleX(), h = v.getHeight() * v.getScaleY();
        float l = loc[0] * k, t = loc[1] * k;
        out.add(new float[]{l, t, l + w * k, t + h * k, radiusDp * a.d * k});
    }

    /**
     * Clock, date, weather line, divider and the two round buttons are white on dark skies and dark on bright ones. The
     * choice is made from the sky behind them (after the contrast veil), with 4.5:1 required for the small text and 3:1
     * once the block has grown into the big clock, and it fades over half a second.
     */
}

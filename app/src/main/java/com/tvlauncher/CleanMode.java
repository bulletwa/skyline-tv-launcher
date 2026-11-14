package com.tvlauncher;

import android.view.View;
import android.widget.LinearLayout;

/**
 * Clean mode (cards hidden, the dock shrunk to a compact bar, the clock and weather centred, with the "ALL APPS" pointer) and the sizing of the
 * clock block: it grows and centres itself whenever there is free space, in clean mode or when no cards are showing.
 */
final class CleanMode {
    private final MainActivity a;

    CleanMode(MainActivity a) {
        this.a = a;
    }

    // ---------------------------------------------------------------- clean mode

    /** The clock block keeps its normal size, just centred (it used to grow to 2x). */
    static final float CLEAN_MAX_SCALE = 1.0f;
    /**
     * In clean mode the dock stays on screen as a compact bar. Its size is worked out so the visible apps (the middle one and
     * up to four each side) fill the bar edge to edge: no empty strip at the ends, whatever the number of apps.
     */
    float cleanDockScale() {
        int n = a.dockTiles.size();
        int k = Math.max(1, Math.min(4, (n - 1) / 2));
        float pitch = a.dp(180 + 16), tile = pitch * DockFocus.TILE_FRACTION;
        float half = DockFocus.evenX(k, CLEAN_HIGHLIGHT, pitch) + tile * DockFocus.scale(k, CLEAN_HIGHLIGHT) / 2f;
        float avail = a.getResources().getDisplayMetrics().widthPixels - 2f * a.dp(a.PAGE_PAD_DP) - 2f * a.dp(10);
        return Math.max(0.35f, Math.min(0.8f, avail / (2f * half)));
    }
    /** How much of the dock's depth-of-field shows in clean mode: the middle app is just slightly bigger and brighter. */
    static final float CLEAN_HIGHLIGHT = 0.75f;
    private android.animation.ValueAnimator dockAnim;

    /** Smoothly resizes the dock bar (also used to keep it small while the screen is clean). */
    void animateDock(float to, boolean animate) {
        if (dockAnim != null) dockAnim.cancel();
        float from = a.dockScale;
        if (!animate || Math.abs(from - to) < 0.001f) {
            a.setDockScale(to);
            return;
        }
        dockAnim = android.animation.ValueAnimator.ofFloat(from, to);
        dockAnim.setDuration(450);
        dockAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        dockAnim.addUpdateListener(an -> a.setDockScale((Float) an.getAnimatedValue()));
        dockAnim.start();
    }

    /**
     * Hides the dock so the screen is just the header and the wallpaper, and grows the clock and weather line
     * (smoothly, to the middle of the screen) to fill the room the cards and dock left. The "ALL APPS" pointer
     * stays, so you know the apps are one step down.
     */
    void enterClean() {
        if (a.clean) return;
        a.clean = true;
        animateDock(cleanDockScale(), true); // the dock stays, as a compact bar
        a.carousel.setFocusAmount(CLEAN_HIGHLIGHT); // the app in the middle is only faintly emphasised
        if (a.homeCards.any()) {
            fadeOut(a.homeCards.rowView()); // hidden cards must not keep cycling stories and pictures in the dark
            a.homeCards.stop();
        }
        layoutHeader(true);
        a.obstacles.scheduleObstacles();
        a.cleanAnchor.setFocusable(true);
        a.cleanAnchor.requestFocus();
        a.hint.bounce();
        a.ui.removeCallbacks(cleanNudge);
        a.ui.postDelayed(cleanNudge, CLEAN_NUDGE_MS);
    }

    void exitClean() {
        if (!a.clean) return;
        a.clean = false;
        a.ui.removeCallbacks(cleanNudge);
        a.cleanAnchor.setFocusable(false);
        layoutHeader(true);
        a.ui.postDelayed(a.obstacles.obstacleUpdate, 320); // once the dock and cards have faded back in
        animateDock(1f, true); // the compact bar grows into the normal dock
        if (a.homeCards.any()) {
            View row = a.homeCards.rowView();
            row.animate().cancel();
            row.setVisibility(View.VISIBLE);
            row.setAlpha(0f);
            row.animate().alpha(1f).setDuration(220).start();
            if (a.started) a.homeCards.start();
        }
        a.focusSelectedDock();
    }

    static final long CLEAN_NUDGE_MS = 12_000;

    /** While the screen is clean the pointer wiggles now and then, in case you have not noticed it. */
    final Runnable cleanNudge = new Runnable() {
        @Override public void run() {
            if (!a.clean || !a.started) return;
            a.hint.bounce();
            a.ui.postDelayed(this, CLEAN_NUDGE_MS);
        }
    };

    /** Distance from the bottom of the screen to the top of the dock band (band + gap + "ALL APPS" pointer). */
    static final int DOCK_TOP_FROM_BOTTOM_DP = 125 + 6 + 34;

    /**
     * Sizes and places the clock + weather block. Normally it sits small in the top-left corner. When there is
     * room, because clean mode hid everything or because no cards are showing, it scales up about its top-left
     * corner and centres itself in the free space: the whole screen in clean mode, the area above the dock
     * otherwise. The scale is the biggest that still fits the page.
     */
    void layoutHeader(boolean animate) {
        final LinearLayout h = a.headerLeft;
        boolean grown = a.clean || !a.homeCards.any();
        a.headerGrown = grown;
        a.ink.applyInk(animate);
        float scale = 1f, tx = 0f, ty = 0f;
        baseTy = 0f;
        if (!grown) h.setAlpha(1f);
        if (grown) {
            if (h.getWidth() == 0) { // not laid out yet: try again once it is
                h.post(() -> layoutHeader(animate));
                return;
            }
            android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
            float w = h.getWidth(), hgt = h.getHeight();
            scale = Math.min(CLEAN_MAX_SCALE, (dm.widthPixels - 2f * a.dp(a.PAGE_PAD_DP)) / w);
            // clean mode: centred above the compact bar; otherwise above the full dock
            float dockTop = a.clean ? 125 * cleanDockScale() + 6 + 34 : DOCK_TOP_FROM_BOTTOM_DP;
            float areaBottom = dm.heightPixels - a.dp(dockTop);
            float bias = 0;
            tx = (dm.widthPixels - w * scale) / 2f - a.dp(a.PAGE_PAD_DP);
            ty = (areaBottom - hgt * scale) / 2f - a.dp(20) - bias; // a.dp(20) = the header's own top margin
            baseTy = ty;
            ty += pinY(a.scrollY());
        }
        h.animate().cancel();
        if (!animate) {
            h.setScaleX(scale);
            h.setScaleY(scale);
            h.setTranslationX(tx);
            h.setTranslationY(ty);
            return;
        }
        h.animate().scaleX(scale).scaleY(scale).translationX(tx).translationY(ty).setDuration(450)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
    }

    private float baseTy;

    /** While the page is scrolled and the clock is centred, it counter-scrolls by half so it stays in the middle of the free space. */
    private float pinY(int scrollY) {
        return a.headerGrown && !a.clean ? scrollY / 2f : 0f;
    }

    /**
     * The page scrolled (Down into All Apps, or back up). With cards off the clock is centred above the dock; once the dock
     * has scrolled up it would leave an empty strip above it, so the clock stays centred in what is left of that space and
     * fades out as the space shrinks (and back in as it grows).
     */
    void onPageScrolled(int y) {
        final LinearLayout h = a.headerLeft;
        if (h == null || h.getWidth() == 0) return;
        if (!a.headerGrown || a.clean) {
            if (h.getAlpha() != 1f) h.setAlpha(1f);
            return;
        }
        float hh = h.getHeight() * h.getScaleY();
        float free = a.getResources().getDisplayMetrics().heightPixels - a.dp(DOCK_TOP_FROM_BOTTOM_DP) - y; // room above the dock
        float alpha = Math.max(0f, Math.min(1f, (free - 1.15f * hh) / (0.75f * hh)));
        h.setAlpha(alpha);
        if (y > 0 || h.getTranslationY() != baseTy) h.setTranslationY(baseTy + pinY(y));
    }

    void fadeOut(final View v) {
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (a.clean) v.setVisibility(View.INVISIBLE); // INVISIBLE keeps the layout and cannot take focus
        }).start();
    }
}

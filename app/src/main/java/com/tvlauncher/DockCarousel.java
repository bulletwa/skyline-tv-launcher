package com.tvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The dock as a looping carousel. Every app is laid out once, in the centre; the carousel then only moves them
 * with translation, scale, alpha and defocus (no layout while it animates), according to where each sits on a
 * ring around the selected app. Pure maths lives in {@link DockFocus}.
 */
final class DockCarousel extends FrameLayout {
    private final List<TileView> tiles = new ArrayList<>();
    private final float pitchPx;
    private float position;      // which app is in the centre, as a continuous index (may exceed 0..n-1)
    private float focusAmount;   // 1 while the dock has focus, 0 when it does not
    private ValueAnimator moveAnim, amountAnim, liftAnim;
    private TileView lifted;
    private final java.util.Map<TileView, Float> slide = new java.util.HashMap<>(); // where each app is coming from, in slots
    private float slideAmount;   // 1 just after a swap, easing to 0
    private ValueAnimator slideAnim;
    private float liftAmount;   // 0..1: how far the held app is enlarged
    private android.view.View shine; // soft glow behind the middle app; only shown in clean mode

    void setShine(android.view.View v) {
        shine = v;
    }

    /** @param pitchPx distance between the centres of neighbouring apps (app width + gap) */
    DockCarousel(Context c, float pitchPx) {
        super(c);
        this.pitchPx = pitchPx;
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** Replaces the apps. The centre stays on the same index where possible. */
    void setTiles(List<TileView> list) {
        if (moveAnim != null) moveAnim.cancel();
        removeAllViews();
        tiles.clear();
        tiles.addAll(list);
        for (TileView t : tiles) addView(t, new LayoutParams(-2, -2, Gravity.CENTER));
        position = tiles.isEmpty() ? 0 : Math.max(0, Math.min(tiles.size() - 1, Math.round(position)));
        apply();
    }

    /** Centres app {@code index}, taking the short way round the ring. */
    void select(int index, boolean animate) {
        int n = tiles.size();
        if (n == 0) return;
        float target = DockFocus.nearestTarget(position, index, n);
        if (moveAnim != null) moveAnim.cancel();
        if (!animate || Math.abs(target - position) < 1e-4f) {
            position = target;
            apply();
            return;
        }
        moveAnim = ValueAnimator.ofFloat(position, target);
        moveAnim.setDuration(Math.abs(target - position) > 1.5f ? 360 : 260);
        moveAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        moveAnim.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            apply();
        });
        moveAnim.start();
    }

    /** The dock gained or lost focus: relax or restore the depth-of-field, so nothing stays highlighted. */
    void setFocused(boolean focused) {
        setFocusAmount(focused ? 1f : 0f);
    }

    /** Depth-of-field strength: 1 = dock focused, 0 = flat, in between = a softer version (clean mode's subtle highlight). */
    void setFocusAmount(float to) {
        if (amountAnim != null) amountAnim.cancel();
        amountAnim = ValueAnimator.ofFloat(focusAmount, to);
        amountAnim.setDuration(220);
        amountAnim.addUpdateListener(a -> {
            focusAmount = (float) a.getAnimatedValue();
            apply();
        });
        amountAnim.start();
    }

    /**
     * The app at {@code i} trades places with its neighbour at {@code j} and stays in the centre; every other app
     * slides to its new slot instead of jumping there.
     */
    void swapAnimated(int i, int j) {
        int n = tiles.size();
        if (i < 0 || j < 0 || i >= n || j >= n || i == j) return;
        java.util.Map<TileView, Float> before = new java.util.HashMap<>();
        for (int k = 0; k < n; k++) before.put(tiles.get(k), DockFocus.offset(k, position, n) + slideOf(tiles.get(k)));
        TileView held = tiles.get(i);
        java.util.Collections.swap(tiles, i, j);
        if (moveAnim != null) moveAnim.cancel();
        position = j;
        slide.clear();
        for (int k = 0; k < n; k++) {
            TileView t = tiles.get(k);
            if (t == held) continue;
            float delta = before.get(t) - DockFocus.offset(k, position, n);
            // the apps are laid out on a ring; take the short way round
            while (delta > n / 2f) delta -= n;
            while (delta < -n / 2f) delta += n;
            slide.put(t, delta);
        }
        held.bringToFront();
        if (slideAnim != null) slideAnim.cancel();
        slideAmount = 1f;
        slideAnim = ValueAnimator.ofFloat(1f, 0f);
        slideAnim.setDuration(240);
        slideAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        slideAnim.addUpdateListener(a -> {
            slideAmount = (float) a.getAnimatedValue();
            apply();
        });
        slideAnim.start();
        apply();
    }

    private float slideOf(TileView t) {
        Float f = slide.get(t);
        return f == null ? 0f : f * slideAmount;
    }

    /** The held app swells a little (null puts it back down). Its jiggle is the tile's own. */
    void setLifted(TileView t) {
        if (liftAnim != null) liftAnim.cancel();
        final TileView old = lifted;
        if (t != null) lifted = t;
        final boolean up = t != null;
        liftAnim = ValueAnimator.ofFloat(liftAmount, up ? 1f : 0f);
        liftAnim.setDuration(160);
        liftAnim.addUpdateListener(a -> {
            liftAmount = (float) a.getAnimatedValue();
            apply();
        });
        liftAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (!up && liftAmount <= 0.001f) lifted = null;
            }
        });
        liftAnim.start();
    }

    /** The launcher is hidden: stop any running animation (it would otherwise keep ticking the choreographer). */
    void cancelAnimations() {
        if (moveAnim != null) moveAnim.cancel();
        if (amountAnim != null) amountAnim.cancel();
        if (liftAnim != null) liftAnim.cancel();
        if (slideAnim != null) slideAnim.cancel();
        slideAmount = 0f;
        liftAmount = 0f;
        lifted = null;
        apply();
    }

    /** For the debug dump: where the ring is and how far each animation has got. */
    String debug() {
        return String.format(java.util.Locale.ENGLISH, "pos=%.2f focus=%.2f lift=%.2f slide=%.2f", position, focusAmount, liftAmount, slideAmount);
    }

    /** Re-applies positions, e.g. after the first layout. */
    void refresh() {
        apply();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        apply();
    }

    private void apply() {
        int n = tiles.size();
        // the gaps between neighbours stay even however much the apps shrink with distance (normal dock and clean mode)
        if (shine != null) shine.setAlpha(DockFocus.evenBlend(focusAmount, CleanMode.CLEAN_HIGHLIGHT)); // the glow: clean mode only
        for (int i = 0; i < n; i++) {
            TileView t = tiles.get(i);
            float off = DockFocus.offset(i, position, n) + slideOf(t);
            float scale = DockFocus.scale(off, focusAmount) * (t == lifted ? 1f + 0.09f * liftAmount : 1f);
            t.setTranslationX(DockFocus.evenX(off, focusAmount, pitchPx));
            t.setScaleX(scale);
            t.setScaleY(scale);
            t.setAlpha(DockFocus.alpha(off, focusAmount) * DockFocus.seamFade(off, n));
            t.setDefocus(DockFocus.defocus(off, focusAmount));
        }
    }
}

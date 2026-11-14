package com.tvlauncher;

/**
 * The dock's looping carousel and depth-of-field. Apps sit on a ring: each one's offset from the centre wraps
 * around, so scrolling past the last app brings the first one back. The app in the centre is sharp and slightly
 * enlarged; the further an app is from it, the smaller, fainter and more out of focus it gets. Pure maths so it
 * can be tested.
 */
final class DockFocus {
    private DockFocus() {}

    /** The selected app is only slightly enlarged; no highlight plate or glow. */
    static final float CENTER_SCALE = 1.08f, FAR_SCALE = 0.76f, FAR_ALPHA = 0.25f;
    /** Distance (in app widths, centre to centre) at which the fade and defocus are complete. */
    static final float FALLOFF = 1.9f;
    /** With fewer apps than this there is nothing to loop around; they just sit in a row. */
    static final int MIN_FOR_LOOP = 3;

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    /** 0 at the centre .. 1 at or beyond the falloff distance. */
    static float blur(float distanceInApps) {
        return smooth(Math.abs(distanceInApps) / FALLOFF);
    }

    /**
     * @param amount 1 while the dock has focus, 0 when focus is elsewhere (every app then looks the same, so
     *               nothing stays highlighted)
     */
    /** An app is this fraction of the pitch wide (the rest is the gap between neighbours): 180 dp of 196. */
    static final float TILE_FRACTION = 180f / 196f;

    /**
     * Where an app at {@code off} slots from the centre sits (in px) so that the gap between the edges of neighbouring apps is the
     * same everywhere, even though the apps shrink with distance: each step outwards is the average of the two apps' scaled widths
     * plus the fixed gap. Between whole slots (while the ring turns) the position is interpolated.
     */
    static float evenX(float off, float amount, float pitchPx) {
        float tile = pitchPx * TILE_FRACTION, gap = pitchPx - tile;
        float a = Math.abs(off);
        int k = (int) Math.floor(a);
        float x = 0;
        for (int j = 0; j < k; j++) x += tile * (scale(j, amount) + scale(j + 1, amount)) / 2f + gap;
        x += (a - k) * (tile * (scale(k, amount) + scale(k + 1, amount)) / 2f + gap);
        return off < 0 ? -x : x;
    }

    /** 0 in the normal dock .. 1 in clean mode's softer highlight: how much of the even-gap layout to use. */
    static float evenBlend(float amount, float cleanAmount) {
        return Math.max(0f, Math.min(1f, (1f - amount) / Math.max(0.001f, 1f - cleanAmount)));
    }

    static float scale(float distanceInApps, float amount) {
        float focused = CENTER_SCALE + (FAR_SCALE - CENTER_SCALE) * blur(distanceInApps);
        return 1f + (focused - 1f) * clamp(amount);
    }

    static float alpha(float distanceInApps, float amount) {
        float focused = 1f + (FAR_ALPHA - 1f) * blur(distanceInApps);
        return 1f + (focused - 1f) * clamp(amount);
    }

    /** How far the blurred copy of the banner is faded in over the sharp one (0 sharp .. 1 fully defocused). */
    static float defocus(float distanceInApps, float amount) {
        return blur(distanceInApps) * clamp(amount);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ------------------------------------------------------------------ the ring

    static boolean loops(int count) {
        return count >= MIN_FOR_LOOP;
    }

    /** Signed distance from the centre to app {@code index}, wrapped into [-n/2, n/2) so the ring is seamless. */
    static float offset(int index, float position, int count) {
        float d = index - position;
        if (!loops(count)) return d;
        return d - count * Math.round(d / count);
    }

    /** The position to animate to so that {@code index} ends up centred, taking the short way round. */
    static float nearestTarget(float position, int index, int count) {
        if (!loops(count)) return index;
        return index + count * Math.round((position - index) / count);
    }

    /** The next (or previous) app around the ring; stops at the ends when there are too few to loop. */
    static int step(int index, int direction, int count) {
        if (count <= 0) return 0;
        if (!loops(count)) return Math.max(0, Math.min(count - 1, index + direction));
        return ((index + direction) % count + count) % count;
    }

    /** Fades an app out as it nears the point where it jumps from one side of the ring to the other. */
    static float seamFade(float offset, int count) {
        if (!loops(count)) return 1f;
        float room = count / 2f - Math.abs(offset); // how far from the jump
        return clamp(room / 0.6f);
    }
}

package com.tvlauncher;

/**
 * A thin film of water on the top of an object: it runs downhill along the object's surface (over the rounded
 * corners), is pushed along by the wind, spreads out a little, and drips off the two ends. Pure Java so the flow
 * rules can be tested. {@code w} is the water depth per 3 px bin and {@code surf} the height of the surface there
 * (larger = lower on screen).
 */
final class WaterFlow {
    private WaterFlow() {}

    static final float BIN = 3f;
    /** The most water a bin holds; more would just run off. */
    static final float MAX_DEPTH = 2.6f;
    /** Depth at an end bin above which water drips off it. */
    static final float DRIP_ABOVE = 0.7f;

    /**
     * Advances the film by {@code dt} seconds.
     *
     * @param wind  sideways push (1/s, positive = to the right): wind-driven rain pushes the film downwind
     * @param evap  depth lost to evaporation per second
     * @param drips out[0] / out[1] receive the water that dripped off the left / right end this step (added to)
     */
    static void step(float[] w, float[] surf, float dt, float wind, float evap, float[] drips) {
        int n = w.length;
        for (int i = 0; i + 1 < n; i++) {
            float sa = Float.isNaN(surf[i]) ? 0 : surf[i], sb = Float.isNaN(surf[i + 1]) ? 0 : surf[i + 1];
            float slope = (sb - sa) / BIN;                 // > 0: the right side is lower, so water runs right
            float source = slope >= 0 ? w[i] : w[i + 1];   // water comes from the higher side
            float flux = dt * (7f * slope * source + 0.35f * (w[i] - w[i + 1]) + wind * w[i]);
            float cap = 0.5f * (flux >= 0 ? w[i] : w[i + 1]);
            if (flux > cap) flux = cap; else if (flux < -cap) flux = -cap;
            w[i] -= flux;
            w[i + 1] += flux;
        }
        if (n > 0) {
            float k = Math.min(1f, 6f * dt);
            if (w[0] > DRIP_ABOVE) {
                float d = (w[0] - DRIP_ABOVE * 0.5f) * k;
                w[0] -= d;
                drips[0] += d;
            }
            if (w[n - 1] > DRIP_ABOVE) {
                float d = (w[n - 1] - DRIP_ABOVE * 0.5f) * k;
                w[n - 1] -= d;
                drips[1] += d;
            }
        }
        float e = evap * dt;
        for (int i = 0; i < n; i++) w[i] = Math.max(0f, Math.min(MAX_DEPTH, w[i]) - e);
    }

    static float total(float[] w) {
        float t = 0;
        for (float v : w) t += v;
        return t;
    }
}

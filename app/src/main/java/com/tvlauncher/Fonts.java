package com.tvlauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit variable font at a given weight (100..900). */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {}

    static synchronized Typeface get(Context c, int weight) {
        Typeface t = CACHE.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getApplicationContext().getAssets(), "Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight)
                        .build();
            } catch (Exception ignored) {
            }
            if (t == null) t = Typeface.DEFAULT;
            CACHE.put(weight, t);
        }
        return t;
    }
}

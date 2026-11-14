package com.tvlauncher;

import android.graphics.Bitmap;

/**
 * How the sky is drawn. The weather, sun and moon, scenes, contrast veil and text-ink logic are shared; a style only
 * decides how the picture and its moving layers look.
 */
interface SkyStyle {
    String name();

    /** The still parts only (the moving layers are built separately). */
    Bitmap renderBase(SkyRenderer.Input in);

    /** One complete still picture, for when the animated sky is off. */
    Bitmap render(SkyRenderer.Input in);

    /** The moving layers: cloud, mist and haze strips and stars. */
    SkyLayers.Set layers(SkyRenderer.Input in);

    /** Whether rain, snow and stars are drawn as brush strokes. */
    boolean painted();

    /** For the debug dump. */
    String debug();

    static SkyStyle byName(String name) {
        return "vangogh".equals(name) ? new VanGoghStyle() : new ClassicStyle();
    }
}

package com.tvlauncher;

import android.graphics.Bitmap;

/** The original smooth, photographic-gradient sky. */
final class ClassicStyle implements SkyStyle {
    private final SkyRenderer renderer = new SkyRenderer();

    @Override public String name() { return "classic"; }

    @Override public Bitmap renderBase(SkyRenderer.Input in) { return renderer.renderBase(in); }

    @Override public Bitmap render(SkyRenderer.Input in) { return renderer.render(in); }

    @Override public SkyLayers.Set layers(SkyRenderer.Input in) { return SkyLayers.build(in); }

    @Override public boolean painted() { return false; }

    @Override public String debug() {
        return "classic vignette+haze+grain rays=" + (renderer.lastRays ? "on" : "off") + " base=" + renderer.lastBaseMs + "ms";
    }
}

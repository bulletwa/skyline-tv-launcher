package com.tvlauncher;

/**
 * Keeps white text readable on every sky. A dark veil of varying strength is baked into the sky picture and its cloud
 * layers (see SkyRenderer.veilGradient): none where the sky is already dark, more where it is bright. Pure Java, so it can be tested.
 * Ratios follow WCAG: 4.5:1 for small text, 3:1 for large text.
 */
final class Contrast {
    private Contrast() {}

    /** Where the veil is measured, as fractions of the screen height: header text, the big clock, the dock area. */
    static final float[] ZONES = {0.08f, 0.35f, 0.62f};
    /** Required white-text contrast in each zone (the dock area carries no text of its own). */
    static final double[] RATIOS = {4.5, 3.0, 0.0};
    /** Never darken more than this: past it the sky stops looking like a sky. */
    static final double CAP = 0.62;

    private static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance 0..1 of 0xRRGGBB. */
    static double luminance(int rgb) {
        return 0.2126 * lin(SkyModel.red(rgb)) + 0.7152 * lin(SkyModel.green(rgb)) + 0.0722 * lin(SkyModel.blue(rgb));
    }

    /** Contrast ratio of white text on this colour (1..21). */
    static double whiteOn(int rgb) {
        return 1.05 / (luminance(rgb) + 0.05);
    }

    /** Smallest black overlay alpha (0..cap) that gives white text at least {@code ratio} on this colour. */
    static double alphaFor(int rgb, double ratio, double cap) {
        if (ratio <= 0 || whiteOn(rgb) >= ratio) return 0;
        double lo = 0, hi = cap;
        if (whiteOn(SkyModel.scale(rgb, 1 - hi)) < ratio) return cap;
        for (int i = 0; i < 18; i++) {
            double mid = (lo + hi) / 2;
            if (whiteOn(SkyModel.scale(rgb, 1 - mid)) >= ratio) hi = mid; else lo = mid;
        }
        return hi;
    }

    /** The colour darkened (towards black) just enough for white text at {@code ratio}. */
    static int darkenFor(int rgb, double ratio) {
        return SkyModel.scale(rgb, 1 - alphaFor(rgb, ratio, 0.85));
    }

    /**
     * The brightest colour the sky is likely to show at screen height fraction {@code frac}: its own colour there,
     * pushed towards the pale tint that clouds and fog add.
     */
    static int worstCase(SkyRenderer.Input in, double frac) {
        WeatherState w = in.weather;
        int[] sky = SkyModel.weatherSky(in.sunElev, in.morning(), w);
        double t = Math.max(0, Math.min(1, frac * SkyRenderer.H / SkyRenderer.HORIZON));
        int base = SkyModel.mix(sky[0], sky[1], t);
        double cloud = (w.rain || w.snow) ? 1 : Math.max(0, Math.min(1, w.cloud / 100.0));
        double veil = Math.min(0.6, 0.30 * cloud + 0.30 * w.fogAmount());
        int pale = SkyModel.mix(0xE6EAF0, 0x2A2E3A, 1 - SkyModel.daylight(in.sunElev)); // clouds are dark at night
        return SkyModel.mix(base, pale, veil);
    }

    /** Veil alpha (0..1) at each of {@link #ZONES} for this sky. */
    static float[] zoneAlphas(SkyRenderer.Input in) {
        float[] a = new float[ZONES.length];
        for (int i = 0; i < a.length; i++) a[i] = (float) alphaFor(worstCase(in, ZONES[i]), RATIOS[i], CAP);
        return a;
    }

    /** Dark ink used when white text cannot reach its contrast on a bright sky. */
    static final int DARK_INK = 0x0E1218, LIGHT_INK = 0xFFFFFF;

    /** Contrast ratio of the dark ink on this colour. */
    static double darkOn(int rgb) {
        return (luminance(rgb) + 0.05) / (luminance(DARK_INK) + 0.05);
    }

    /**
     * White unless it falls clearly short of {@code ratio} (below 90% of it) and dark ink does better. The margin keeps
     * the text from flipping between colours on a sky that is borderline.
     */
    static int inkFor(int background, double ratio) {
        double white = whiteOn(background);
        return (white < ratio * 0.9 && darkOn(background) > white) ? DARK_INK : LIGHT_INK;
    }

    /**
     * From a measured background: should the ink be dark? {@code bright} and {@code dark} are the luminance of the
     * brighter and darker pixels behind it; {@code fill} is the opacity of a tint the ink's own surface adds (a button's
     * glass disc is the ink colour, so it pulls the background towards the ink). The colour in use stays unless it
     * misses {@code ratio} and the other one is clearly (15%) better.
     */
    /**
     * The header icons share the text's tone so the two never disagree on a mid-tone sky; they only break away when
     * that colour misses {@code min} on the icons' own patch and the other one clears it.
     */
    static boolean buttonDark(boolean textDark, double bright, double dark, double fill, double min) {
        double white = 1.05 / (bright + fill * (1 - bright) + 0.05);
        double ink = (dark * (1 - fill) + 0.05) / (luminance(DARK_INK) + 0.05);
        double mine = textDark ? ink : white, other = textDark ? white : ink;
        return mine >= min || other < min ? textDark : !textDark;
    }

    static boolean useDark(double bright, double dark, double ratio, boolean currentlyDark, double fill) {
        double white = 1.05 / (bright + fill * (1 - bright) + 0.05);
        double ink = (dark * (1 - fill) + 0.05) / (luminance(DARK_INK) + 0.05);
        if (currentlyDark) return !(ink < ratio && white > ink * 1.15);
        return white < ratio && ink > white * 1.15;
    }
}

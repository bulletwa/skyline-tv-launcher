package com.tvlauncher;

/**
 * Which moving things the sky has for a given sun height and weather. Pure Java so the rules can be tested; the
 * drawing lives in {@link SkyLayers} and {@link SkyView}.
 */
final class SkyPlan {
    /** Flat cloud decks (overcast, rain, snow), each its own drifting strip. */
    int decks;
    double deckAlpha;
    /** Puffy cumulus strips (partly cloudy) and how many puffs each holds. */
    int puffStrips, puffsPerStrip;
    /** A few faint wisps for an otherwise clear day, so the sky is never frozen. */
    int wispStrips, wispsPerStrip;
    /** Mist banks drifting across (fog / mist), at three speeds. */
    int mistStrips;
    double mistAlpha;
    /** Smog haze (AQI over 100). */
    boolean haze;
    double hazeAlpha;
    /** 0..1 how many stars show through (night x clear sky). Twinkling is applied on top. */
    double starAmount;
    /** An occasional shooting star, only on a really clear night. */
    boolean shooting;

    static SkyPlan of(double sunElev, WeatherState w) {
        SkyPlan p = new SkyPlan();
        double cloud = Math.max(0, Math.min(1, w.cloud / 100.0));
        boolean wet = w.rain || w.snow;
        double fog = w.fogAmount();
        double day = SkyModel.daylight(sunElev);
        double night = 1 - SkyModel.smoothstep(-14, -4, sunElev);

        // Thick cloud and rain hide the stars completely; fog and haze only soften them.
        double clearness = (1 - Math.min(1, cloud * 1.2)) * (1 - 0.3 * fog);
        p.starAmount = night * clearness;
        p.shooting = p.starAmount > 0.5;

        if (wet || cloud > 0.8) {
            p.decks = wet ? 3 : 2;
            p.deckAlpha = wet ? 0.5 : 0.32;
        } else if (cloud > 0.2) {
            p.puffStrips = 2;
            p.puffsPerStrip = Math.max(2, (int) Math.round(cloud * 8));
        } else if (day > 0.12 && fog < 0.6) {
            p.wispStrips = 2;
            p.wispsPerStrip = 2;
        }

        if (fog > 0.05) {
            p.mistStrips = 3;
            p.mistAlpha = Math.min(0.5, 0.2 + 0.3 * fog);
        }
        if (w.aqi > 100) {
            p.haze = true;
            p.hazeAlpha = Math.min(1, (w.aqi - 100) / 200.0) * 0.5;
        }
        return p;
    }

    /**
     * Colour of mist at height {@code y}: the sky's own colour there, mostly desaturated and a little lighter, so
     * fog looks like a veil over this sky rather than a differently coloured band laid on top of it.
     *
     * @param sky     {top, horizon} sky colours (0xRRGGBB)
     * @param horizon y of the horizon in the same units as y
     */
    static int mistColor(int[] sky, double y, double horizon, double day) {
        double t = Math.max(0, Math.min(1, y / horizon));
        int base = SkyModel.mix(sky[0], sky[1], t);
        int lum = SkyModel.luminance(base);
        int grey = SkyModel.rgb(lum, lum, lum);
        int neutral = SkyModel.mix(base, grey, 0.65);
        return SkyModel.mix(neutral, 0xE4E8EE, 0.10 + 0.22 * day);
    }

    boolean hasMotion() {
        return decks > 0 || puffStrips > 0 || wispStrips > 0 || mistStrips > 0 || haze || starAmount > 0.02;
    }

    /** Horizontal shift of a strip of {@code width} px after {@code seconds}, wrapped into [0, width). */
    static float offset(double seconds, float speedPxPerSec, int width) {
        double o = (seconds * speedPxPerSec) % width;
        return (float) (o < 0 ? o + width : o);
    }

    /** Star brightness factor 0.55..1.0 for a point of a sine wave; each group has its own phase and rate. */
    static float twinkle(double seconds, float phase, float rate) {
        return (float) (0.775 + 0.225 * Math.sin(seconds * rate + phase));
    }
}

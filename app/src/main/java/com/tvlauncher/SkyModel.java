package com.tvlauncher;

/**
 * Astronomy + sky palette. Pure Java: low-precision solar/lunar formulas (good to ~0.1-0.5 deg),
 * sun-elevation keyed gradients, and weather tints. Colours are opaque 0xRRGGBB ints.
 */
final class SkyModel {
    private SkyModel() {}

    static final class Pos {
        final double elevation, azimuth;
        Pos(double elevation, double azimuth) {
            this.elevation = elevation;
            this.azimuth = azimuth;
        }
    }

    private static final double RAD = Math.PI / 180;

    private static double julianDay(long utcMillis) {
        return utcMillis / 86400000.0 + 2440587.5;
    }

    private static double norm360(double d) {
        d %= 360;
        return d < 0 ? d + 360 : d;
    }

    /** Ecliptic longitude of the sun in degrees. */
    private static double sunLongitude(double n) {
        double g = norm360(357.528 + 0.9856003 * n) * RAD;
        double L = norm360(280.460 + 0.9856474 * n);
        return norm360(L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
    }

    private static Pos horizontal(double raDeg, double decDeg, double n, double latDeg, double lonDeg) {
        double gmst = norm360(280.46061837 + 360.98564736629 * n);
        double h = norm360(gmst + lonDeg - raDeg) * RAD;
        double lat = latDeg * RAD, dec = decDeg * RAD;
        double sinAlt = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(h);
        double alt = Math.asin(Math.max(-1, Math.min(1, sinAlt)));
        double az = Math.atan2(Math.sin(h), Math.cos(h) * Math.sin(lat) - Math.tan(dec) * Math.cos(lat));
        return new Pos(alt / RAD, norm360(az / RAD + 180));
    }

    private static Pos fromEcliptic(double lonDeg, double latDeg, double n, double obsLat, double obsLon) {
        double eps = (23.439 - 0.0000004 * n) * RAD;
        double l = lonDeg * RAD, b = latDeg * RAD;
        double ra = Math.atan2(Math.sin(l) * Math.cos(eps) - Math.tan(b) * Math.sin(eps), Math.cos(l));
        double dec = Math.asin(Math.sin(b) * Math.cos(eps) + Math.cos(b) * Math.sin(eps) * Math.sin(l));
        return horizontal(norm360(ra / RAD), dec / RAD, n, obsLat, obsLon);
    }

    /** Sun elevation (deg above horizon, no refraction) and azimuth (deg clockwise from north). */
    static Pos sun(long utcMillis, double latDeg, double lonDeg) {
        double n = julianDay(utcMillis) - 2451545.0;
        return fromEcliptic(sunLongitude(n), 0, n, latDeg, lonDeg);
    }

    static Pos moon(long utcMillis, double latDeg, double lonDeg) {
        double n = julianDay(utcMillis) - 2451545.0;
        double lon = norm360(218.316 + 13.176396 * n + 6.289 * Math.sin(norm360(134.963 + 13.064993 * n) * RAD));
        double lat = 5.128 * Math.sin(norm360(93.272 + 13.229350 * n) * RAD);
        return fromEcliptic(lon, lat, n, latDeg, lonDeg);
    }

    /**
     * Where along the screen's width a body at this azimuth sits, as a fraction (can be a little under 0 or over 1, so
     * the moon slides in and out of frame instead of being pinned at the edge).
     */
    static double trackFraction(double az) {
        return ((az - 60) / 240.0) * 1.12 - 0.06;
    }

    /** 0 spring, 1 summer, 2 autumn, 3 winter, by meteorological season; the southern hemisphere is half a year out. */
    static int season(long utcMillis, double latDeg) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);
        int m = cal.get(java.util.Calendar.MONTH); // 0 = January
        int north = (((m + 1) % 12) / 3 + 3) % 4; // Dec-Feb 3 (winter), Mar-May 0, Jun-Aug 1, Sep-Nov 2
        return latDeg < 0 ? (north + 2) % 4 : north;
    }

    static String seasonName(int season) {
        return new String[]{"Spring", "Summer", "Autumn", "Winter"}[Math.max(0, Math.min(3, season))];
    }

    /** 0 below the horizon .. 1 once clear of it: the moon fades in at moonrise and out at moonset. */
    static double riseFade(double elev) {
        return smoothstep(-3, 6, elev);
    }

    /** 0 = nearly on the horizon .. 1 = high: used to redden and swell a low sun or moon. */
    static double lowness(double elev, double highAt) {
        return 1 - smoothstep(2, highAt, elev);
    }

    /** How far through the day the sun is: 0 at sunrise, 1 at sunset, <0 before, >1 after (minutes after midnight). */
    static double dayProgress(int sunriseMin, int sunsetMin, int nowMin) {
        if (sunsetMin <= sunriseMin) return Double.NaN;
        return (nowMin - sunriseMin) / (double) (sunsetMin - sunriseMin);
    }

    /** 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. */
    static double moonPhase(long utcMillis) {
        double n = julianDay(utcMillis) - 2451545.0;
        double mLon = norm360(218.316 + 13.176396 * n + 6.289 * Math.sin(norm360(134.963 + 13.064993 * n) * RAD));
        return norm360(mLon - sunLongitude(n)) / 360.0;
    }

    /** Fraction of the disc that is lit, 0..1. */
    static double moonIllumination(long utcMillis) {
        return (1 - Math.cos(moonPhase(utcMillis) * 2 * Math.PI)) / 2;
    }

    // ------------------------------------------------------------------ colours

    static int rgb(int r, int g, int b) {
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    static int red(int c) { return (c >> 16) & 255; }
    static int green(int c) { return (c >> 8) & 255; }
    static int blue(int c) { return c & 255; }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        return rgb((int) Math.round(red(a) + (red(b) - red(a)) * t),
                (int) Math.round(green(a) + (green(b) - green(a)) * t),
                (int) Math.round(blue(a) + (blue(b) - blue(a)) * t));
    }

    static int luminance(int c) {
        return (int) Math.round(0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c));
    }

    static int scale(int c, double f) {
        return rgb((int) Math.round(red(c) * f), (int) Math.round(green(c) * f), (int) Math.round(blue(c) * f));
    }

    static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** {sun elevation, top rgb, horizon rgb}: night, astronomical/nautical/civil twilight, sunrise, day. */
    private static final int[][] KEYS = {
            {-18, 0x040614, 0x0A0E22},
            {-12, 0x080E2C, 0x1C244E},
            {-8, 0x142256, 0x60466E},
            {-4, 0x284282, 0xD67A6A},
            {-1, 0x466EB9, 0xFF9448},
            {3, 0x4E84D0, 0xFFC482},
            {8, 0x4284DE, 0xC8D6EE},
            {20, 0x2872E1, 0x94C6F5},
            {90, 0x1C64DC, 0x82BEF5},
    };

    /** Clear-sky {top, horizon} for a sun elevation. morning tilts the warm band toward peach vs. orange-red. */
    static int[] clearSky(double sunElev, boolean morning) {
        int[] top = new int[1], hor = new int[1];
        if (sunElev <= KEYS[0][0]) {
            top[0] = KEYS[0][1];
            hor[0] = KEYS[0][2];
        } else if (sunElev >= KEYS[KEYS.length - 1][0]) {
            top[0] = KEYS[KEYS.length - 1][1];
            hor[0] = KEYS[KEYS.length - 1][2];
        } else {
            for (int i = 0; i < KEYS.length - 1; i++) {
                if (sunElev <= KEYS[i + 1][0]) {
                    double t = (sunElev - KEYS[i][0]) / (KEYS[i + 1][0] - KEYS[i][0]);
                    top[0] = mix(KEYS[i][1], KEYS[i + 1][1], t);
                    hor[0] = mix(KEYS[i][2], KEYS[i + 1][2], t);
                    break;
                }
            }
        }
        double warm = 1 - smoothstep(0, 9, Math.abs(sunElev + 1)); // strongest right at the horizon
        hor[0] = mix(hor[0], morning ? 0xFF8C8C : 0xFF5A28, 0.14 * warm);
        return new int[]{top[0], hor[0]};
    }

    /** 0 at night .. 1 in full daylight. */
    static double daylight(double sunElev) {
        return smoothstep(-10, 8, sunElev);
    }

    /** Sky {top, horizon} after clouds, rain, fog and smog have had their say. */
    static int[] weatherSky(double sunElev, boolean morning, WeatherState w) {
        int[] c = clearSky(sunElev, morning);
        int top = c[0], hor = c[1];
        double day = daylight(sunElev);

        double cloud = Math.max(0, Math.min(1, w.cloud / 100.0));
        if (cloud > 0.25) {
            double o = (cloud - 0.25) / 0.75;
            top = mix(top, grey(top, 0.92), 0.75 * o);
            hor = mix(hor, grey(hor, 0.9), 0.65 * o);
        }
        if (w.snow) {
            int s = scale(0xAAB4C3, 0.2 + 0.8 * day);
            top = mix(top, s, 0.7);
            hor = mix(hor, scale(0xC8D0DC, 0.2 + 0.8 * day), 0.7);
        } else if (w.rain) {
            double dark = w.storm ? 0.62 : 1;
            top = mix(top, scale(0x586680, (0.18 + 0.82 * day) * dark), 0.82);
            hor = mix(hor, scale(0x8291A0, (0.18 + 0.82 * day) * dark), 0.6);
        }
        // one mood per weather, kept gentle so contrast is unchanged: clear days a touch richer, rain slightly
        // teal-slate, snow slightly lavender-white. Fog and storm keep their own looks.
        if (!w.storm && w.fogAmount() == 0) {
            if (w.snow) hor = mix(hor, scale(0xD2C8E2, 0.2 + 0.8 * day), 0.18);
            else if (w.rain) hor = mix(hor, scale(0x6F9096, 0.2 + 0.8 * day), 0.15);
            else if (cloud < 0.4) top = mix(top, scale(0x2F6FE0, 0.2 + 0.8 * day), 0.1 * day * (1 - cloud / 0.4));
        }
        double fog = w.fogAmount();
        if (fog > 0) {
            int f = mix(hor, grey(hor, 1.0), 0.6);
            top = mix(top, f, 0.85 * fog);
            hor = mix(hor, f, 0.92 * fog);
        }
        if (w.aqi > 100) {
            double smog = Math.min(1, (w.aqi - 100) / 200.0) * 0.6;
            hor = mix(hor, scale(0x96693C, 0.25 + 0.75 * day), smog);
            top = mix(top, scale(0x7A6A58, 0.25 + 0.75 * day), smog * 0.35);
        }
        return new int[]{top, hor};
    }

    private static int grey(int c, double f) {
        int l = (int) Math.round(luminance(c) * f);
        return rgb(l, l, l);
    }
}

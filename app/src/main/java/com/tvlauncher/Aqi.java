package com.tvlauncher;

/**
 * Air quality index maths. Pure Java.
 *
 * The header shows India's CPCB National AQI, computed from PM2.5 / PM10 concentrations. A reading comes either
 * from the nearest real monitoring station (WAQI, needs a free token) or, without one, from Open-Meteo's
 * modelled estimate, which under-reads badly over north India and is therefore always marked as an estimate.
 */
final class Aqi {
    private Aqi() {}

    static final class Reading {
        /** CPCB AQI 0..500, or -1 when unknown. */
        final int value;
        final String category;
        /** Station name, or "Modelled estimate". */
        final String source;
        final boolean station;
        final double pm25, pm10;

        Reading(int value, String source, boolean station, double pm25, double pm10) {
            this.value = value;
            this.category = value < 0 ? "" : cpcbCategory(value);
            this.source = source;
            this.station = station;
            this.pm25 = pm25;
            this.pm10 = pm10;
        }
    }

    // {conc lo, conc hi, index lo, index hi}
    private static final double[][] CPCB_PM25 = {
            {0, 30, 0, 50}, {30, 60, 50, 100}, {60, 90, 100, 200}, {90, 120, 200, 300}, {120, 250, 300, 400}, {250, 500, 400, 500}};
    private static final double[][] CPCB_PM10 = {
            {0, 50, 0, 50}, {50, 100, 50, 100}, {100, 250, 100, 200}, {250, 350, 200, 300}, {350, 430, 300, 400}, {430, 600, 400, 500}};
    // US EPA breakpoints as used by WAQI (the index it reports per pollutant)
    private static final double[][] US_PM25 = {
            {0, 12, 0, 50}, {12.1, 35.4, 51, 100}, {35.5, 55.4, 101, 150}, {55.5, 150.4, 151, 200},
            {150.5, 250.4, 201, 300}, {250.5, 350.4, 301, 400}, {350.5, 500.4, 401, 500}};
    private static final double[][] US_PM10 = {
            {0, 54, 0, 50}, {55, 154, 51, 100}, {155, 254, 101, 150}, {255, 354, 151, 200},
            {355, 424, 201, 300}, {425, 504, 301, 400}, {505, 604, 401, 500}};

    /** Concentration -> index by piecewise-linear interpolation; values past the last band clamp to 500. */
    private static double toIndex(double c, double[][] table) {
        if (Double.isNaN(c) || c < 0) return Double.NaN;
        for (double[] b : table) {
            if (c <= b[1]) return b[2] + (b[3] - b[2]) * (c - b[0]) / (b[1] - b[0]);
        }
        return 500;
    }

    /** Index -> concentration (the inverse of {@link #toIndex}). */
    private static double toConcentration(double index, double[][] table) {
        if (Double.isNaN(index) || index < 0) return Double.NaN;
        for (double[] b : table) {
            if (index <= b[3]) return b[0] + (b[1] - b[0]) * (index - b[2]) / (b[3] - b[2]);
        }
        return table[table.length - 1][1];
    }

    /** CPCB AQI from PM2.5 and PM10 in µg/m³ (either may be NaN); -1 when neither is known. */
    static int cpcb(double pm25, double pm10) {
        double a = toIndex(pm25, CPCB_PM25), b = toIndex(pm10, CPCB_PM10);
        double m = Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b);
        return Double.isNaN(m) ? -1 : (int) Math.round(m);
    }

    static double usPm25FromIndex(double index) {
        return toConcentration(index, US_PM25);
    }

    static double usPm10FromIndex(double index) {
        return toConcentration(index, US_PM10);
    }

    static String cpcbCategory(int v) {
        if (v <= 50) return "Good";
        if (v <= 100) return "Satisfactory";
        if (v <= 200) return "Moderate";
        if (v <= 300) return "Poor";
        if (v <= 400) return "Very Poor";
        return "Severe";
    }
}

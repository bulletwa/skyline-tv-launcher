package com.tvlauncher;

import java.util.Locale;

/** Current conditions that modify the sky. Pure Java (no Android imports) so it is unit-testable. */
final class WeatherState {
    double tempC = Double.NaN;
    /** WMO weather code (Open-Meteo). */
    int code = 0;
    /** Cloud cover in percent. */
    int cloud = 0;
    /** Precipitation in mm. */
    double precip = 0;
    double visibilityM = 24000;
    /** India CPCB AQI, or -1 when unknown. */
    int aqi = -1;
    String aqiCategory = "";
    /** Monitoring station name, or "Modelled estimate". */
    String aqiSource = "";
    /** True for a real station reading, false for the model's estimate. */
    boolean aqiStation;
    double pm25 = Double.NaN, pm10 = Double.NaN;
    /** ICAO id when an airport observation overrode the model, else null. */
    String metarStation;

    boolean rain, snow, storm, fog;

    /** One day of the week's forecast. */
    static final class Day {
        final String name; // "Mon"
        final int code;
        final int max, min, rainPct;
        Day(String name, int code, int max, int min, int rainPct) {
            this.name = name;
            this.code = code;
            this.max = max;
            this.min = min;
            this.rainPct = rainPct;
        }
    }

    /** Today first, up to 7 days; empty until the forecast has been fetched. */
    java.util.List<Day> days = new java.util.ArrayList<>();

    /** One hour of the next-24h forecast. */
    static final class Hour {
        final int hour; // local hour of day 0..23
        final int code, temp, rainPct;
        final boolean day;
        Hour(int hour, int code, int temp, int rainPct, boolean day) {
            this.hour = hour;
            this.code = code;
            this.temp = temp;
            this.rainPct = rainPct;
            this.day = day;
        }
    }

    /** Starts at the current hour; empty until the forecast has been fetched. */
    java.util.List<Hour> hours = new java.util.ArrayList<>();

    /** Extra current readings; NaN / -1 when unknown. */
    double feelsC = Double.NaN, windKmh = Double.NaN, uv = Double.NaN;
    int humidity = -1, windDir = -1;
    /** Today's sunrise / sunset in local minutes after midnight, or -1. */
    int sunriseMin = -1, sunsetMin = -1;

    WeatherState() {
        derive();
    }

    void applyAqi(Aqi.Reading r) {
        if (r == null) return;
        aqi = r.value;
        aqiCategory = r.category;
        aqiSource = r.source;
        aqiStation = r.station;
        pm25 = r.pm25;
        pm10 = r.pm10;
    }

    /** "AQI 212" for a station reading, "AQI ~88" for the model's estimate; null when unknown. */
    String aqiText() {
        if (aqi < 0) return null;
        return "AQI " + (aqiStation ? "" : "~") + aqi;
    }

    /** Recomputes the flags from code / precipitation / visibility. Call after changing those fields. */
    void derive() {
        storm = code >= 95;
        snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        rain = !snow && ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || storm || precip >= 0.2);
        fog = code == 45 || code == 48 || visibilityM < 1000;
    }

    /** 0 = none, 1 = thick fog. */
    double fogAmount() {
        if (code == 45 || code == 48 || visibilityM < 1000) return 1;
        if (visibilityM < 3000) return 0.5;
        if (visibilityM < 5000) return 0.2;
        return 0;
    }

    /** Rain/snow intensity 0..1 for the particle layer. */
    double intensity() {
        if (!rain && !snow) return 0;
        double byCode;
        switch (code) {
            case 51: case 56: case 61: case 80: case 71: case 85: byCode = 0.35; break;
            case 53: case 63: case 66: case 81: case 73: byCode = 0.6; break;
            case 55: case 57: case 65: case 67: case 82: case 75: case 77: case 86: byCode = 0.85; break;
            default: byCode = storm ? 1 : 0.5;
        }
        return Math.min(1, Math.max(byCode, Math.min(1, precip / 4.0)));
    }

    boolean animated() {
        return rain || snow;
    }

    String description() {
        if (storm) return "THUNDERSTORM";
        if (snow) return code == 85 || code == 86 ? "SNOW SHOWERS" : "SNOW";
        switch (code) {
            case 45: case 48: return "FOG";
            case 51: case 53: case 55: case 56: case 57: return "DRIZZLE";
            case 61: case 63: case 65: case 66: case 67: return "RAIN";
            case 80: case 81: case 82: return "SHOWERS";
        }
        if (fog) return "MIST";
        if (rain) return "RAIN";
        if (cloud < 15) return "CLEAR SKY";
        if (cloud < 40) return "MOSTLY CLEAR";
        if (cloud < 75) return "PARTLY CLOUDY";
        return "OVERCAST";
    }

    String temperatureText() {
        return Double.isNaN(tempC) ? null : String.format(Locale.ENGLISH, "%d°C", Math.round(tempC));
    }

    /** Changes only when the sky would visibly change. */
    String signature() {
        return code + "|" + (cloud / 10) + "|" + (int) (fogAmount() * 10) + "|" + (aqi < 0 ? -1 : aqi / 50)
                + "|" + rain + snow + storm + "|" + (int) (intensity() * 4);
    }
}

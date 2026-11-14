package com.tvlauncher;

/** Named fixed skies for the Appearance setting (and for previewing the renderer on the TV). */
final class Scenes {
    private Scenes() {}

    static final String[] NAMES = {"sunrise", "day", "golden", "dusk", "night", "overcast", "rain", "storm", "fog", "nightfog", "smog", "snow", "partly", "drizzle", "hot", "moonrise", "sunsetlow"};

    static String label(String name) {
        switch (name) {
            case "sunrise": return "Sunrise";
            case "day": return "Clear day";
            case "golden": return "Golden hour";
            case "dusk": return "Dusk";
            case "night": return "Starry night";
            case "overcast": return "Overcast";
            case "rain": return "Rain";
            case "storm": return "Thunderstorm";
            case "fog": return "Fog";
            case "nightfog": return "Foggy night";
            case "smog": return "Smog";
            case "snow": return "Snow";
            case "partly": return "Partly cloudy";
            case "drizzle": return "Drizzle";
            case "hot": return "Hazy heat";
            case "moonrise": return "Moonrise";
            case "sunsetlow": return "Low sun";
        }
        return name;
    }

    /** Returns null for "auto" or an unknown name. */
    static SkyRenderer.Input build(String name) {
        if (name == null) return null;
        SkyRenderer.Input in = new SkyRenderer.Input();
        WeatherState w = new WeatherState();
        in.weather = w;
        in.moonElev = 38;
        in.moonAz = 150;
        in.moonPhase = 0.36;
        switch (name) {
            case "sunrise": sun(in, 1.5, 98); break;
            case "day": sun(in, 48, 180); break;
            case "golden": sun(in, 6, 255); w.cloud = 35; break;
            case "dusk": sun(in, -5, 268); break;
            case "night": sun(in, -45, 0); break;
            case "overcast": sun(in, 40, 180); w.cloud = 100; w.code = 3; break;
            case "rain": sun(in, 30, 200); w.cloud = 100; w.code = 63; w.precip = 1.5; break;
            case "storm": sun(in, 20, 220); w.cloud = 100; w.code = 95; w.precip = 4; break;
            case "fog": sun(in, 25, 120); w.code = 45; w.visibilityM = 300; break;
            case "nightfog": sun(in, -45, 0); w.code = 45; w.visibilityM = 300; break;
            case "smog": sun(in, 35, 200); w.aqi = 320; w.cloud = 10; break;
            case "snow": sun(in, 20, 190); w.cloud = 100; w.code = 73; w.precip = 1; break;
            case "partly": sun(in, 40, 170); w.cloud = 55; w.code = 2; break;
            case "drizzle": sun(in, 25, 200); w.cloud = 90; w.code = 53; w.precip = 0.4; break;
            case "moonrise": sun(in, -30, 0); in.moonElev = 4; in.moonAz = 100; in.moonPhase = 0.46; break;
            case "sunsetlow": sun(in, 1.2, 262); w.cloud = 20; break;
            case "hot": sun(in, 62, 180); w.cloud = 0; w.code = 0; w.visibilityM = 9000; break;
            default: return null;
        }
        w.derive();
        return in;
    }

    private static void sun(SkyRenderer.Input in, double elev, double az) {
        in.sunElev = elev;
        in.sunAz = az;
    }
}

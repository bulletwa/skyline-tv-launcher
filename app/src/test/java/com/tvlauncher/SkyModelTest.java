package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public class SkyModelTest {
    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static int chroma(int c) {
        int max = Math.max(SkyModel.red(c), Math.max(SkyModel.green(c), SkyModel.blue(c)));
        int min = Math.min(SkyModel.red(c), Math.min(SkyModel.green(c), SkyModel.blue(c)));
        return max - min;
    }

    // ------------------------------------------------------------ sun

    @Test
    public void sunIsOverheadOnEquatorAtEquinoxNoon() {
        SkyModel.Pos p = SkyModel.sun(t("2024-03-20T12:07:00Z"), 0, 0);
        assertTrue("elevation " + p.elevation, p.elevation > 88);
    }

    @Test
    public void londonSolsticeNoonElevation() {
        SkyModel.Pos p = SkyModel.sun(t("2024-06-21T12:00:00Z"), 51.5072, -0.1276);
        assertEquals(61.9, p.elevation, 1.0);
        assertEquals(180, p.azimuth, 5);
    }

    @Test
    public void sunIsBelowHorizonAtLocalMidnight() {
        SkyModel.Pos p = SkyModel.sun(t("2024-06-21T00:00:00Z"), 51.5072, -0.1276);
        assertTrue(p.elevation < -5);
    }

    @Test
    public void equinoxSunriseIsDueEast() {
        SkyModel.Pos p = SkyModel.sun(t("2024-03-20T06:00:00Z"), 0, 0);
        assertEquals(0, p.elevation, 3);
        assertEquals(90, p.azimuth, 4);
    }

    @Test
    public void sunriseOnEastSunsetOnWest() {
        long noon = t("2024-10-30T06:30:00Z"); // ~noon in Delhi
        SkyModel.Pos before = SkyModel.sun(noon - 4 * 3600_000L, 28.65, 77.23);
        SkyModel.Pos after = SkyModel.sun(noon + 4 * 3600_000L, 28.65, 77.23);
        assertTrue(before.azimuth < 180);
        assertTrue(after.azimuth > 180);
    }

    // ------------------------------------------------------------ moon

    @Test
    public void newMoonPhaseIsNearZero() {
        double p = SkyModel.moonPhase(t("2024-04-08T18:21:00Z"));
        assertTrue("phase " + p, p < 0.03 || p > 0.97);
        assertTrue(SkyModel.moonIllumination(t("2024-04-08T18:21:00Z")) < 0.02);
    }

    @Test
    public void fullMoonPhaseIsHalf() {
        long full = t("2024-04-24T23:49:00Z");
        assertEquals(0.5, SkyModel.moonPhase(full), 0.03);
        assertTrue(SkyModel.moonIllumination(full) > 0.98);
    }

    @Test
    public void firstQuarterPhase() {
        assertEquals(0.25, SkyModel.moonPhase(t("2024-04-15T19:13:00Z")), 0.04);
    }

    @Test
    public void fullMoonRisesOppositeTheSun() {
        long full = t("2024-04-24T23:49:00Z");
        SkyModel.Pos sun = SkyModel.sun(full, 28.65, 77.23);
        SkyModel.Pos moon = SkyModel.moon(full, 28.65, 77.23);
        assertEquals(-sun.elevation, moon.elevation, 12); // roughly antipodal
    }

    // ------------------------------------------------------------ palette

    @Test
    public void nightIsDarkAndDayIsBlue() {
        int[] night = SkyModel.clearSky(-30, false);
        assertTrue(SkyModel.luminance(night[0]) < 20);
        int[] day = SkyModel.clearSky(45, false);
        assertTrue(SkyModel.blue(day[0]) > SkyModel.red(day[0]) + 80);
    }

    @Test
    public void sunsetHorizonIsOrange() {
        int hor = SkyModel.clearSky(-1, false)[1];
        assertTrue(SkyModel.red(hor) > 220);
        assertTrue(SkyModel.red(hor) > SkyModel.blue(hor) + 100);
    }

    @Test
    public void topBrightnessRisesFromNightToSunrise() {
        // Above ~3 deg the zenith intentionally deepens to a richer blue, so only check up to sunrise.
        int prev = -1;
        for (int e = -20; e <= 3; e++) {
            int l = SkyModel.luminance(SkyModel.clearSky(e, true)[0]);
            assertTrue("elev " + e, l >= prev);
            prev = l;
        }
    }

    // ------------------------------------------------------------ weather tints

    @Test
    public void overcastGreysTheSky() {
        WeatherState w = new WeatherState();
        w.cloud = 100;
        w.code = 3;
        w.derive();
        int clear = SkyModel.clearSky(40, false)[0];
        int cloudy = SkyModel.weatherSky(40, false, w)[0];
        assertTrue(chroma(cloudy) < chroma(clear) / 2);
    }

    @Test
    public void rainTurnsSlateAndDarkerThanClear() {
        WeatherState w = new WeatherState();
        w.code = 63;
        w.cloud = 100;
        w.derive();
        assertTrue(w.rain);
        int clear = SkyModel.clearSky(40, false)[0];
        int rain = SkyModel.weatherSky(40, false, w)[0];
        assertTrue(SkyModel.luminance(rain) < SkyModel.luminance(clear));
        assertTrue(chroma(rain) < chroma(clear));
    }

    @Test
    public void fogFlattensTopAndHorizon() {
        WeatherState w = new WeatherState();
        w.code = 45;
        w.derive();
        int[] clear = SkyModel.clearSky(30, false);
        int[] fog = SkyModel.weatherSky(30, false, w);
        int gapClear = Math.abs(SkyModel.luminance(clear[0]) - SkyModel.luminance(clear[1]));
        int gapFog = Math.abs(SkyModel.luminance(fog[0]) - SkyModel.luminance(fog[1]));
        assertTrue(gapFog < gapClear / 2 + 1);
    }

    @Test
    public void smogAddsBrownHazeAtHorizon() {
        WeatherState w = new WeatherState();
        w.aqi = 300;
        int clear = SkyModel.clearSky(30, false)[1];
        int smog = SkyModel.weatherSky(30, false, w)[1];
        assertTrue(SkyModel.red(smog) - SkyModel.blue(smog) > SkyModel.red(clear) - SkyModel.blue(clear));
    }

    @Test
    public void goodAirLeavesSkyAlone() {
        WeatherState w = new WeatherState();
        w.aqi = 40;
        assertEquals(SkyModel.clearSky(30, false)[1], SkyModel.weatherSky(30, false, w)[1]);
    }

    // ------------------------------------------------------------ weather state

    @Test
    public void weatherFlagsFromCodes() {
        WeatherState w = new WeatherState();
        w.code = 95;
        w.derive();
        assertTrue(w.storm && w.rain);
        w.code = 73;
        w.derive();
        assertTrue(w.snow && !w.rain);
        w.code = 0;
        w.precip = 1.0;
        w.derive();
        assertTrue(w.rain);
    }

    @Test
    public void descriptionFollowsCloudCoverWhenDry() {
        WeatherState w = new WeatherState();
        w.cloud = 5;
        assertEquals("CLEAR SKY", w.description());
        w.cloud = 55;
        assertEquals("PARTLY CLOUDY", w.description());
        w.cloud = 95;
        assertEquals("OVERCAST", w.description());
    }

    @Test
    public void signatureIgnoresSmallChanges() {
        WeatherState a = new WeatherState();
        a.cloud = 41;
        WeatherState b = new WeatherState();
        b.cloud = 44;
        assertEquals(a.signature(), b.signature());
        b.cloud = 60;
        assertTrue(!a.signature().equals(b.signature()));
    }

    @Test
    public void moonFadesInAtRiseAndSlidesAcrossTheFrame() {
        assertEquals(0, SkyModel.riseFade(-6), 1e-9);
        assertTrue(SkyModel.riseFade(1) > 0.1 && SkyModel.riseFade(1) < 0.9);
        assertEquals(1, SkyModel.riseFade(20), 1e-9);
        double prev = Double.NEGATIVE_INFINITY;
        for (double az = 40; az <= 320; az += 10) { // strictly increasing: never pinned at an edge
            double f = SkyModel.trackFraction(az);
            assertTrue(f > prev);
            prev = f;
        }
        assertTrue(SkyModel.trackFraction(60) < 0.1 && SkyModel.trackFraction(300) > 0.9);
    }

    @Test
    public void lowBodiesAreFlaggedAndDayProgressRunsSunriseToSunset() {
        assertTrue(SkyModel.lowness(1, 30) > 0.99);
        assertEquals(0, SkyModel.lowness(45, 30), 1e-9);
        assertEquals(0.5, SkyModel.dayProgress(360, 1080, 720), 1e-9);
        assertTrue(SkyModel.dayProgress(360, 1080, 300) < 0);
        assertTrue(SkyModel.dayProgress(360, 1080, 1200) > 1);
        assertTrue(Double.isNaN(SkyModel.dayProgress(-1, -1, 700)));
    }

    @Test
    public void seasonsFollowTheCalendarAndFlipInTheSouth() {
        assertEquals(2, SkyModel.season(t("2026-10-31T12:00:00Z"), 28.5)); // autumn in the north
        assertEquals(3, SkyModel.season(t("2026-01-15T12:00:00Z"), 28.5));
        assertEquals(3, SkyModel.season(t("2026-12-01T12:00:00Z"), 51));
        assertEquals(0, SkyModel.season(t("2026-04-10T12:00:00Z"), 40));
        assertEquals(1, SkyModel.season(t("2026-07-04T12:00:00Z"), 40));
        assertEquals(0, SkyModel.season(t("2026-10-31T12:00:00Z"), -33)); // spring in the south
        assertEquals("Winter", SkyModel.seasonName(3));
    }
}

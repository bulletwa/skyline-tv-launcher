package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SkyPlanTest {
    private static WeatherState wx(int code, int cloud, double visM, int aqi) {
        WeatherState w = new WeatherState();
        w.code = code;
        w.cloud = cloud;
        w.visibilityM = visM;
        w.aqi = aqi;
        w.derive();
        return w;
    }

    private static final double DAY = 40, NIGHT = -45, DUSK = -3;

    @Test
    public void clearDayHasOnlyFaintWispsDrifting() {
        SkyPlan p = SkyPlan.of(DAY, wx(0, 5, 24000, 40));
        assertEquals(2, p.wispStrips);
        assertEquals(0, p.decks + p.puffStrips + p.mistStrips);
        assertFalse(p.haze);
        assertEquals(0, p.starAmount, 1e-6);
        assertTrue(p.hasMotion());
    }

    @Test
    public void clearNightTwinklesAndShoots() {
        SkyPlan p = SkyPlan.of(NIGHT, wx(0, 5, 24000, 40));
        assertTrue(p.starAmount > 0.9);
        assertTrue(p.shooting);
        assertEquals(0, p.wispStrips); // no wisps in the dark
        assertTrue(p.hasMotion());
    }

    @Test
    public void partlyCloudyGetsPuffsInProportionToCover() {
        SkyPlan few = SkyPlan.of(DAY, wx(2, 30, 24000, 40));
        SkyPlan many = SkyPlan.of(DAY, wx(2, 70, 24000, 40));
        assertEquals(2, few.puffStrips);
        assertTrue(many.puffsPerStrip > few.puffsPerStrip);
        assertEquals(0, few.decks);
    }

    @Test
    public void overcastAndRainGetLayeredDecksAndNoStars() {
        SkyPlan over = SkyPlan.of(NIGHT, wx(3, 100, 24000, 40));
        assertEquals(2, over.decks);
        assertEquals(0, over.starAmount, 1e-6);
        assertFalse(over.shooting);
        SkyPlan rain = SkyPlan.of(NIGHT, wx(63, 100, 8000, 40));
        assertEquals(3, rain.decks);
        assertTrue(rain.deckAlpha > over.deckAlpha); // darker, heavier decks in rain
        assertEquals(0, rain.starAmount, 1e-6);
        assertEquals(3, SkyPlan.of(DAY, wx(73, 100, 8000, 40)).decks); // snow too
    }

    @Test
    public void fogGetsThreeMistBanksOverAnyTimeOfDay() {
        for (double sun : new double[]{DAY, DUSK, NIGHT}) {
            SkyPlan p = SkyPlan.of(sun, wx(45, 10, 300, 40));
            assertEquals("sun " + sun, 3, p.mistStrips);
            assertTrue(p.mistAlpha > 0.3);
        }
    }

    @Test
    public void foggyNightStillShowsSomeStars() {
        SkyPlan p = SkyPlan.of(NIGHT, wx(45, 10, 300, 40));
        assertTrue(p.starAmount > 0.4 && p.starAmount < 1.0);
    }

    @Test
    public void lightMistGetsFainterBanksThanThickFog() {
        SkyPlan mist = SkyPlan.of(DAY, wx(0, 10, 4000, 40));   // 4 km visibility
        SkyPlan fog = SkyPlan.of(DAY, wx(45, 10, 300, 40));
        assertEquals(3, mist.mistStrips);
        assertTrue(mist.mistAlpha < fog.mistAlpha);
        assertEquals(0, SkyPlan.of(DAY, wx(0, 10, 24000, 40)).mistStrips); // clear air: none
    }

    @Test
    public void smogAddsHazeScaledByAqi() {
        assertFalse(SkyPlan.of(DAY, wx(0, 5, 24000, 90)).haze);
        SkyPlan bad = SkyPlan.of(DAY, wx(0, 5, 24000, 200));
        SkyPlan worse = SkyPlan.of(DAY, wx(0, 5, 24000, 400));
        assertTrue(bad.haze && worse.haze);
        assertTrue(worse.hazeAlpha > bad.hazeAlpha);
        assertTrue(worse.hazeAlpha <= 0.5 + 1e-9);
    }

    @Test
    public void everyKindOfSkyHasSomeMotion() {
        int[][] cases = {{0, 5}, {2, 40}, {3, 100}, {45, 10}, {63, 100}, {95, 100}, {73, 100}};
        for (double sun : new double[]{DAY, DUSK, NIGHT, 1.5}) {
            for (int[] c : cases) {
                SkyPlan p = SkyPlan.of(sun, wx(c[0], c[1], c[0] == 45 ? 300 : 24000, 40));
                assertTrue("sun " + sun + " code " + c[0] + " cloud " + c[1], p.hasMotion());
            }
        }
    }

    @Test
    public void stripOffsetWrapsSeamlesslyForBothDirections() {
        assertEquals(0f, SkyPlan.offset(0, 10, 1920), 1e-3);
        assertEquals(100f, SkyPlan.offset(10, 10, 1920), 1e-3);
        assertEquals(1f, SkyPlan.offset(1921, 1, 1920), 1e-2);       // wrapped once
        assertEquals(1910f, SkyPlan.offset(10, -1, 1920), 1e-2);    // negative speeds wrap into range
        for (double t = 0; t < 5000; t += 137) {
            float o = SkyPlan.offset(t, 19, 1920);
            assertTrue(o >= 0 && o < 1920);
        }
    }

    @Test
    public void twinkleStaysWithinItsBandAndVaries() {
        float min = 9, max = -9;
        for (double t = 0; t < 20; t += 0.05) {
            float f = SkyPlan.twinkle(t, 1.3f, 2.1f);
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        assertTrue(min >= 0.55f - 1e-3 && max <= 1.0f + 1e-3);
        assertTrue("it actually twinkles", max - min > 0.3f);
    }

    @Test
    public void everyNamedSceneBuildsAndTheNewFoggyNightIsFoggyAndDark() {
        for (String n : Scenes.NAMES) assertTrue(n, Scenes.build(n) != null);
        SkyRenderer.Input in = Scenes.build("nightfog");
        assertTrue(in.sunElev < -20);
        assertTrue(in.weather.fogAmount() > 0.9);
    }
}

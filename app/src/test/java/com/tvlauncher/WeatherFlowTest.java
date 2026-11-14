package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WeatherFlowTest {
    // ------------------------------------------------------------ open columns (where weather can reach the dock)

    // two cards with a 26 px gap and 48 px side margins, and the dock below them
    private static float[] withCards() {
        List<float[]> l = new ArrayList<>();
        l.add(new float[]{48, 100, 467, 330, 16});
        l.add(new float[]{493, 100, 912, 330, 16});
        l.add(new float[]{48, 375, 912, 500, 28});
        return Obstacles.flatten(l);
    }

    private static float[] dockOnly() {
        List<float[]> l = new ArrayList<>();
        l.add(new float[]{48, 375, 912, 500, 28});
        return Obstacles.flatten(l);
    }

    @Test
    public void theDockIsTheLowestObject() {
        assertEquals(2, Obstacles.dockIndex(withCards()));
        assertEquals(0, Obstacles.dockIndex(dockOnly()));
        assertEquals(-1, Obstacles.dockIndex(new float[0]));
    }

    @Test
    public void withCardsOnlyTheGapAndTheSideMarginsAreOpen() {
        float[] o = withCards();
        for (float u = 0; u < 1; u += 0.01f) {
            float x = Obstacles.openSample(o, 0f, u);
            assertFalse(Float.isNaN(x));
            boolean inGap = x > 467 && x < 493;
            boolean outsideCards = x < 48 || x > 912;
            assertTrue("x=" + x + " would hit a card", inGap || outsideCards || x == 467 || x == 493);
        }
    }

    @Test
    public void withoutCardsTheWholeDockIsOpen() {
        float[] o = dockOnly();
        float min = 9999, max = -9999;
        for (float u = 0; u < 1; u += 0.01f) {
            float x = Obstacles.openSample(o, 0f, u);
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        assertEquals(48f, min, 2f);
        assertEquals(912f, max, 12f);
    }

    @Test
    public void windShiftsTheOpenColumnsUpwind() {
        float[] o = withCards();
        // slanting right by 0.16 px per px, a particle starting at the top lands 0.16 * 100 = 16 px to the right of
        // where it started when it reaches the cards, so the gap is entered from further left
        float x = Obstacles.openSample(o, 0.16f, 0.5f);
        assertFalse(Float.isNaN(x));
        float gapMidTop = 480 - 0.16f * 100;  // start x whose path crosses the cards' top at the gap middle
        boolean viaGap = Math.abs(x - gapMidTop) < 40;
        boolean viaSide = x + 0.16f * 375 > 912 || x + 0.16f * 375 < 48; // lands beside the dock: not counted
        assertTrue("x=" + x, viaGap || !viaSide);
    }

    @Test
    public void nothingIsOpenWhenCardsCoverTheWholeDock() {
        List<float[]> l = new ArrayList<>();
        l.add(new float[]{0, 100, 960, 330, 16});
        l.add(new float[]{48, 375, 912, 500, 28});
        assertTrue(Float.isNaN(Obstacles.openSample(Obstacles.flatten(l), 0f, 0.5f)));
    }

    @Test
    public void gapSamplingIsUniformOverTheOpenStretches() {
        float[] o = withCards();
        int inGap = 0, total = 400;
        for (int i = 0; i < total; i++) {
            float x = Obstacles.openSample(o, 0f, (i + 0.5f) / total);
            if (x > 467 && x < 493) inGap++;
        }
        // the gap is 26 px of the open stretches, the side margins (0..48 and 912..912) add up to 48 within the dock
        assertTrue("gap share " + inGap, inGap > 0);
    }

    // ------------------------------------------------------------ water film

    private static float[] flat(int n) {
        float[] s = new float[n];
        java.util.Arrays.fill(s, 100f);
        return s;
    }

    @Test
    public void waterIsNeverCreatedOrLostExceptByDrippingAndEvaporation() {
        float[] surf = flat(60), w = new float[60];
        for (int i = 20; i < 40; i++) w[i] = 1.2f;
        float before = WaterFlow.total(w);
        float[] drips = new float[2];
        for (int step = 0; step < 100; step++) WaterFlow.step(w, surf, 0.04f, 0f, 0f, drips);
        assertEquals(before, WaterFlow.total(w) + drips[0] + drips[1], 0.01f);
    }

    @Test
    public void waterRunsDownhillOverARoundedCorner() {
        // surface drops towards the right end (a rounded corner), flat before it
        float[] surf = flat(40);
        for (int i = 30; i < 40; i++) surf[i] = 100f + (i - 29) * 1.5f;
        float[] w = new float[40];
        for (int i = 25; i <= 33; i++) w[i] = 2.0f;
        float[] drips = new float[2];
        for (int step = 0; step < 400; step++) WaterFlow.step(w, surf, 0.04f, 0f, 0f, drips);
        // where the water ended up on average (water that dripped off an end counts at that end)
        float com = centreOfMass(w, drips, 40);
        // control: the same water on a flat surface
        float[] flatSurf = flat(40), w2 = new float[40];
        for (int i = 25; i <= 33; i++) w2[i] = 2.0f;
        float[] drips2 = new float[2];
        for (int step = 0; step < 400; step++) WaterFlow.step(w2, flatSurf, 0.04f, 0f, 0f, drips2);
        float com2 = centreOfMass(w2, drips2, 40);
        assertTrue("slope " + com + " vs flat " + com2, com > com2 + 1.5f);
        assertTrue("it drips off the lower end: " + drips[1] + " vs " + drips[0], drips[1] > drips[0] + 0.05f);
    }

    private static float centreOfMass(float[] w, float[] drips, int n) {
        float sum = drips[0] + drips[1], moment = drips[1] * (n - 1);
        for (int i = 0; i < n; i++) { sum += w[i]; moment += w[i] * i; }
        return moment / sum;
    }

    @Test
    public void windPushesAFlatFilmDownwind() {
        float[] surf = flat(80), w = new float[80];
        for (int i = 50; i < 70; i++) w[i] = 1.0f;
        float[] drips = new float[2];
        for (int step = 0; step < 250; step++) WaterFlow.step(w, surf, 0.04f, 6f, 0f, drips);
        assertTrue("downwind end drips more: " + drips[1] + " vs " + drips[0], drips[1] > drips[0] * 3 + 0.001f);
    }

    @Test
    public void aCalmFlatFilmSpreadsEvenlyAndDripsEqualAtBothEnds() {
        float[] surf = flat(40), w = new float[40];
        for (int i = 0; i < 40; i++) w[i] = 1.4f; // thick enough that both ends drip
        float[] drips = new float[2];
        for (int step = 0; step < 100; step++) WaterFlow.step(w, surf, 0.04f, 0f, 0f, drips);
        assertEquals(drips[0], drips[1], 0.05f);
        assertTrue(drips[0] > 0);
    }

    @Test
    public void aThinFilmDoesNotDripAndEvaporates() {
        float[] surf = flat(40), w = new float[40];
        for (int i = 0; i < 40; i++) w[i] = 0.2f;
        float[] drips = new float[2];
        for (int step = 0; step < 200; step++) WaterFlow.step(w, surf, 0.04f, 0f, 0.15f, drips);
        assertEquals(0f, drips[0] + drips[1], 1e-6);
        assertEquals(0f, WaterFlow.total(w), 1e-4);
    }

    @Test
    public void depthIsCapped() {
        float[] surf = flat(10), w = new float[10];
        java.util.Arrays.fill(w, 50f);
        WaterFlow.step(w, surf, 0.04f, 0f, 0f, new float[2]);
        for (float v : w) assertTrue(v <= WaterFlow.MAX_DEPTH + 1e-4);
    }

    @Test
    public void unknownSurfaceHeightsAreTreatedAsFlat() {
        float[] surf = flat(10);
        surf[4] = Float.NaN;
        float[] w = new float[10];
        w[4] = 1f;
        float before = WaterFlow.total(w);
        float[] drips = new float[2];
        for (int i = 0; i < 20; i++) WaterFlow.step(w, surf, 0.04f, 0f, 0f, drips);
        assertEquals(before, WaterFlow.total(w) + drips[0] + drips[1], 0.01f);
    }

    // ------------------------------------------------------------ ink colour

    @Test
    public void whiteTextStaysWhiteWhereItIsReadable() {
        assertEquals(Contrast.LIGHT_INK, Contrast.inkFor(0x0A1230, 4.5));
        assertEquals(Contrast.LIGHT_INK, Contrast.inkFor(0x1C64DC, 4.5));
    }

    @Test
    public void textTurnsDarkOnABrightBackgroundThatWhiteCannotReach() {
        int pale = 0xC8D4E0;
        assertTrue(Contrast.whiteOn(pale) < 4.5 * 0.9);
        assertEquals(Contrast.DARK_INK, Contrast.inkFor(pale, 4.5));
        assertTrue(Contrast.darkOn(pale) > 4.5);
    }

    @Test
    public void aBorderlineBackgroundKeepsWhiteSoTheTextDoesNotFlip() {
        // contrast just under the ratio but within 10%: stays white
        int c = 0x5F7F9F;
        double white = Contrast.whiteOn(c);
        assertTrue("white " + white, white < 4.5 && white >= 4.5 * 0.9);
        assertEquals(Contrast.LIGHT_INK, Contrast.inkFor(c, 4.5));
    }

    @Test
    public void largeTextAcceptsLowerContrastThanSmallText() {
        int c = 0x7F9AC0;
        double white = Contrast.whiteOn(c);
        assertTrue("white " + white, white > 2.7 && white < 4.0);
        assertEquals(Contrast.LIGHT_INK, Contrast.inkFor(c, 3.0)); // big clock: white is fine
    }

    @Test
    public void darkInkIsCompletelyReadableOnWhite() {
        assertTrue(Contrast.darkOn(0xFFFFFF) > 15);
    }
}

package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ObstaclesTest {
    // a card: x 100..500, y 100..200, corner radius 10; a dock: x 50..900, y 300..400, radius 14
    private static float[] scene() {
        List<float[]> l = new ArrayList<>();
        l.add(new float[]{100, 100, 500, 200, 10});
        l.add(new float[]{50, 300, 900, 400, 14});
        return Obstacles.flatten(l);
    }

    @Test
    public void flatTopsAreAtTheTopEdge() {
        float[] o = scene();
        assertEquals(100f, Obstacles.surfaceY(o, 0, 300), 1e-4);
        assertEquals(300f, Obstacles.surfaceY(o, 1, 475), 1e-4);
        assertEquals(2, Obstacles.count(o));
    }

    @Test
    public void outsideTheObjectThereIsNoSurface() {
        float[] o = scene();
        assertTrue(Float.isNaN(Obstacles.surfaceY(o, 0, 99)));
        assertTrue(Float.isNaN(Obstacles.surfaceY(o, 0, 501)));
    }

    @Test
    public void roundedCornersCurveDownwardsTowardsTheSides() {
        float[] o = scene();
        float edge = Obstacles.surfaceY(o, 0, 100);     // the very left edge: a quarter turn down
        float mid = Obstacles.surfaceY(o, 0, 105);      // half way round the corner
        float flat = Obstacles.surfaceY(o, 0, 110);     // where the corner ends
        assertEquals(110f, edge, 1e-3);                 // top + radius
        assertEquals(100f, flat, 1e-3);
        assertTrue(mid > flat && mid < edge);
        // symmetric on the right
        assertEquals(Obstacles.surfaceY(o, 0, 100 + 3), Obstacles.surfaceY(o, 0, 500 - 3), 1e-3);
        // surface height never increases moving in from the edge
        float prev = Float.MAX_VALUE;
        for (float x = 100; x <= 110; x += 0.5f) {
            float y = Obstacles.surfaceY(o, 0, x);
            assertTrue(y <= prev + 1e-4);
            prev = y;
        }
    }

    @Test
    public void aRadiusLargerThanTheObjectIsClamped() {
        float[] o = Obstacles.flatten(java.util.Collections.singletonList(new float[]{0, 0, 20, 10, 999}));
        float y = Obstacles.surfaceY(o, 0, 10);
        assertTrue(y >= 0 && y <= 10);
    }

    @Test
    public void somethingFallingThroughTheTopLandsOnIt() {
        float[] o = scene();
        float[] out = new float[1];
        assertEquals(0, Obstacles.landing(o, 300, 90, 105, out));
        assertEquals(100f, out[0], 1e-4);
        assertEquals(1, Obstacles.landing(o, 400, 290, 310, out));
        assertEquals(300f, out[0], 1e-4);
    }

    @Test
    public void missesWhenNotYetThereOrAlreadyBelowOrBesideIt() {
        float[] o = scene();
        float[] out = new float[1];
        assertEquals(-1, Obstacles.landing(o, 300, 80, 95, out));    // still above
        assertEquals(-1, Obstacles.landing(o, 300, 110, 125, out));  // already inside: passes behind
        assertEquals(-1, Obstacles.landing(o, 20, 90, 120, out));    // beside it (left of x range)
        assertEquals(-1, Obstacles.landing(o, 700, 90, 120, out));   // between objects, over nothing
    }

    @Test
    public void aFastStepStillCatchesTheSurface() {
        float[] o = scene();
        float[] out = new float[1];
        assertEquals(0, Obstacles.landing(o, 200, 20, 140, out)); // a step far bigger than the object's top
        assertEquals(100f, out[0], 1e-4);
    }

    @Test
    public void theHighestObjectUnderneathIsHitFirst() {
        List<float[]> l = new ArrayList<>();
        l.add(new float[]{0, 200, 100, 300, 0});
        l.add(new float[]{0, 120, 100, 180, 0});
        float[] o = Obstacles.flatten(l);
        float[] out = new float[1];
        assertEquals(1, Obstacles.landing(o, 50, 100, 250, out)); // the upper one, though it comes second in the list
        assertEquals(120f, out[0], 1e-4);
    }

    @Test
    public void nothingToHitMeansNoLanding() {
        assertEquals(-1, Obstacles.landing(new float[0], 5, 0, 500, new float[1]));
    }

    @Test
    public void shapeComparisonToleratesSmallMovesOnly() {
        float[] a = scene();
        float[] b = a.clone();
        b[1] += 3;
        assertTrue(Obstacles.sameShape(a, b, 6));
        b[1] += 20;
        assertFalse(Obstacles.sameShape(a, b, 6));
        assertFalse(Obstacles.sameShape(a, new float[0], 6));
    }

    // ------------------------------------------------------------ mist colour

    private static int chroma(int c) {
        int mx = Math.max(SkyModel.red(c), Math.max(SkyModel.green(c), SkyModel.blue(c)));
        int mn = Math.min(SkyModel.red(c), Math.min(SkyModel.green(c), SkyModel.blue(c)));
        return mx - mn;
    }

    @Test
    public void mistIsAVeilOfTheSkyNotADifferentlyColouredBand() {
        int[] night = {0x0A1230, 0x2A2038};   // deep blue at the top, purplish at the horizon
        for (double y : new double[]{60, 180, 300}) {
            int sky = SkyModel.mix(night[0], night[1], y / 356.0);
            int mist = SkyPlan.mistColor(night, y, 356, 0);
            assertTrue("less colourful than the sky at y=" + y, chroma(mist) < chroma(sky));
            // a night mist is only a touch lighter than the sky behind it, not a bright band
            assertTrue("y=" + y, SkyModel.luminance(mist) - SkyModel.luminance(sky) < 40);
            assertTrue("y=" + y, SkyModel.luminance(mist) >= SkyModel.luminance(sky) - 2);
        }
    }

    @Test
    public void dayMistIsPaleAndNearlyNeutral() {
        int[] day = {0x2872E1, 0x94C6F5};
        int mist = SkyPlan.mistColor(day, 300, 356, 1);
        assertTrue(SkyModel.luminance(mist) > 150);
        assertTrue(chroma(mist) < chroma(day[1]));
    }

    @Test
    public void mistColourChangesSmoothlyWithHeight() {
        int[] sky = {0x142256, 0x60466E};
        int prev = SkyPlan.mistColor(sky, 0, 356, 0.2);
        for (double y = 10; y <= 356; y += 10) {
            int c = SkyPlan.mistColor(sky, y, 356, 0.2);
            assertTrue(Math.abs(SkyModel.red(c) - SkyModel.red(prev)) <= 6);
            assertTrue(Math.abs(SkyModel.green(c) - SkyModel.green(prev)) <= 6);
            assertTrue(Math.abs(SkyModel.blue(c) - SkyModel.blue(prev)) <= 6);
            prev = c;
        }
    }
}

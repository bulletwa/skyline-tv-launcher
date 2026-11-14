package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Random;

public class PrecipTest {
    @Test
    public void countsGrowWithIntensityAndStayBounded() {
        assertTrue(Precip.rainCount(0) < Precip.rainCount(0.5));
        assertTrue(Precip.rainCount(0.5) < Precip.rainCount(1));
        assertTrue(Precip.rainCount(5) <= Precip.MAX_PARTICLES);
        assertTrue(Precip.snowCount(-3) > 0);
        assertTrue(Precip.snowCount(1) <= Precip.MAX_PARTICLES);
    }

    @Test
    public void strikesComeEveryFiveToFifteenSeconds() {
        Random r = new Random(1);
        for (int i = 0; i < 500; i++) {
            long d = Precip.nextStrikeDelayMs(r);
            assertTrue(d >= 5000 && d <= 15000);
        }
    }

    @Test
    public void flashIsADoubleFlickerThenDecays() {
        double first = Precip.flashAlpha(30), gap = Precip.flashAlpha(100), second = Precip.flashAlpha(180);
        assertTrue("first flicker is bright", first > 0.4);
        assertTrue("dark beat between", gap < 0.15);
        assertTrue("second flash is stronger", second > first);
        assertTrue("decaying", Precip.flashAlpha(400) < second && Precip.flashAlpha(400) > 0);
        assertEquals(0, Precip.flashAlpha(700), 0);
        assertEquals(0, Precip.flashAlpha(-5), 0);
        for (long t = 0; t < 800; t += 10) {
            double a = Precip.flashAlpha(t);
            assertTrue(a >= 0 && a <= 1);
        }
    }

    @Test
    public void boltOutlivesFlashButEnds() {
        assertEquals(1, Precip.boltAlpha(100), 0);
        assertTrue(Precip.boltAlpha(400) > 0 && Precip.boltAlpha(400) < 1);
        assertEquals(0, Precip.boltAlpha(500), 0);
    }

    @Test
    public void boltRunsTopToEndAndForks() {
        for (int seed = 0; seed < 50; seed++) {
            List<Precip.Segment> b = Precip.bolt(new Random(seed), 960, 356);
            assertTrue(b.size() > 8);
            Precip.Segment first = b.get(0);
            assertEquals(0, first.y1, 0);
            assertEquals(0, first.kind);
            float maxY = 0;
            int forks = 0;
            float prevX = first.x1, prevY = first.y1;
            for (Precip.Segment s : b) {
                assertTrue(s.x1 >= 0 && s.x1 <= 960 && s.x2 >= 0 && s.x2 <= 960);
                assertTrue(s.y2 >= s.y1); // always heads downwards
                maxY = Math.max(maxY, s.y2);
                if (s.kind == 1) forks++;
                else { // trunk pieces are contiguous
                    assertEquals(prevX, s.x1, 0.001);
                    assertEquals(prevY, s.y1, 0.001);
                    prevX = s.x2;
                    prevY = s.y2;
                }
            }
            assertEquals(356, maxY, 0.001);
            assertTrue("seed " + seed + " has forks", forks >= 2);
        }
    }
}

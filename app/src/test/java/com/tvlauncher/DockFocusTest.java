package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DockFocusTest {
    // ------------------------------------------------------------ depth of field

    @Test
    public void centreIsSharpAndOnlySlightlyEnlarged() {
        assertEquals(DockFocus.CENTER_SCALE, DockFocus.scale(0, 1), 1e-6);
        assertEquals(1f, DockFocus.alpha(0, 1), 1e-6);
        assertEquals(0f, DockFocus.defocus(0, 1), 1e-6);
        assertTrue("only slightly enlarged", DockFocus.CENTER_SCALE > 1f && DockFocus.CENTER_SCALE <= 1.12f);
    }

    @Test
    public void fadesShrinksAndDefocusesGraduallyWithDistance() {
        float prevS = 10, prevA = 10, prevB = -1;
        for (float d = 0; d <= 3.5f; d += 0.25f) {
            float s = DockFocus.scale(d, 1), a = DockFocus.alpha(d, 1), b = DockFocus.defocus(d, 1);
            assertTrue("scale never grows at " + d, s <= prevS + 1e-6);
            assertTrue("alpha never grows at " + d, a <= prevA + 1e-6);
            assertTrue("defocus never shrinks at " + d, b >= prevB - 1e-6);
            prevS = s;
            prevA = a;
            prevB = b;
        }
        assertEquals(DockFocus.FAR_SCALE, DockFocus.scale(5, 1), 1e-6);
        assertEquals(DockFocus.FAR_ALPHA, DockFocus.alpha(5, 1), 1e-6);
        assertEquals(1f, DockFocus.defocus(5, 1), 1e-6);
    }

    @Test
    public void theEffectIsClearlyVisibleOnTheNextApp() {
        // the previous version's falloff was too gentle to notice: neighbours must visibly differ from the centre
        assertTrue(DockFocus.defocus(1, 1) > 0.4f);
        assertTrue(DockFocus.alpha(1, 1) < 0.7f);
        assertTrue(DockFocus.alpha(1, 1) > 0.45f); // but still readable
        assertTrue(DockFocus.scale(0, 1) - DockFocus.scale(1, 1) > 0.08f);
    }

    @Test
    public void isSymmetricLeftAndRight() {
        assertEquals(DockFocus.scale(1.3f, 1), DockFocus.scale(-1.3f, 1), 1e-6);
        assertEquals(DockFocus.defocus(2f, 1), DockFocus.defocus(-2f, 1), 1e-6);
    }

    @Test
    public void whenFocusIsElsewhereEveryAppLooksTheSame() {
        for (float d = 0; d <= 4; d += 0.5f) {
            assertEquals(1f, DockFocus.scale(d, 0), 1e-6);
            assertEquals(1f, DockFocus.alpha(d, 0), 1e-6);
            assertEquals(0f, DockFocus.defocus(d, 0), 1e-6);
        }
        assertEquals(1f, DockFocus.scale(0, -3f), 1e-6); // amount is clamped
    }

    // ------------------------------------------------------------ the ring

    @Test
    public void offsetsWrapSoTheRowIsSeamless() {
        // 8 apps, centred on app 0: the apps just before it are the last ones
        assertEquals(0f, DockFocus.offset(0, 0, 8), 1e-6);
        assertEquals(1f, DockFocus.offset(1, 0, 8), 1e-6);
        assertEquals(-1f, DockFocus.offset(7, 0, 8), 1e-6);
        assertEquals(-2f, DockFocus.offset(6, 0, 8), 1e-6);
        // centred on the last app, the first is next to it
        assertEquals(1f, DockFocus.offset(0, 7, 8), 1e-6);
        assertEquals(-1f, DockFocus.offset(6, 7, 8), 1e-6);
        // in between positions stay continuous
        assertEquals(0.5f, DockFocus.offset(1, 0.5f, 8), 1e-6);
        assertEquals(-0.5f, DockFocus.offset(0, 0.5f, 8), 1e-6);
    }

    @Test
    public void everyOffsetStaysWithinHalfTheRing() {
        for (int n = 3; n <= 12; n++) {
            for (float pos = -20; pos <= 20; pos += 0.37f) {
                for (int i = 0; i < n; i++) {
                    float o = DockFocus.offset(i, pos, n);
                    assertTrue("n=" + n + " pos=" + pos + " i=" + i + " o=" + o, o >= -n / 2f - 1e-4 && o <= n / 2f + 1e-4);
                }
            }
        }
    }

    @Test
    public void exactlyOneAppIsAtTheCentre() {
        for (int n = 3; n <= 10; n++) {
            for (int centre = 0; centre < n; centre++) {
                int atCentre = 0;
                for (int i = 0; i < n; i++) if (Math.abs(DockFocus.offset(i, centre, n)) < 1e-6) atCentre++;
                assertEquals(1, atCentre);
            }
        }
    }

    @Test
    public void scrollingTakesTheShortWayRound() {
        // from the last app (7 of 8) to the first: one step forward, not seven back
        assertEquals(8f, DockFocus.nearestTarget(7, 0, 8), 1e-6);
        // from the first to the last: one step back
        assertEquals(-1f, DockFocus.nearestTarget(0, 7, 8), 1e-6);
        // ordinary moves
        assertEquals(3f, DockFocus.nearestTarget(2, 3, 8), 1e-6);
        assertEquals(1f, DockFocus.nearestTarget(2, 1, 8), 1e-6);
        // after wrapping once, keeps counting upwards
        assertEquals(9f, DockFocus.nearestTarget(8, 1, 8), 1e-6);
        // the app ends up centred whatever the starting position
        for (float start = -30; start <= 30; start += 1.3f) {
            for (int i = 0; i < 8; i++) {
                float t = DockFocus.nearestTarget(start, i, 8);
                assertEquals(0f, DockFocus.offset(i, t, 8), 1e-4);
                assertTrue(Math.abs(t - start) <= 4.0001f); // never more than half way round
            }
        }
    }

    @Test
    public void stepWrapsInBothDirections() {
        assertEquals(0, DockFocus.step(7, 1, 8));
        assertEquals(7, DockFocus.step(0, -1, 8));
        assertEquals(4, DockFocus.step(3, 1, 8));
        assertEquals(2, DockFocus.step(3, -1, 8));
        // visiting every app by pressing Right 8 times comes back to the start
        int i = 5;
        for (int k = 0; k < 8; k++) i = DockFocus.step(i, 1, 8);
        assertEquals(5, i);
    }

    @Test
    public void fewAppsDoNotLoopAndStopAtTheEnds() {
        assertFalse(DockFocus.loops(2));
        assertTrue(DockFocus.loops(3));
        assertEquals(1, DockFocus.step(1, 1, 2));
        assertEquals(0, DockFocus.step(0, -1, 2));
        assertEquals(0, DockFocus.step(0, 1, 1));
        assertEquals(1f, DockFocus.offset(1, 0, 2), 1e-6);   // no wrapping
        assertEquals(1f, DockFocus.nearestTarget(0, 1, 2), 1e-6);
        assertEquals(0, DockFocus.step(0, 1, 0));
    }

    @Test
    public void appsFadeOutBeforeTheySkipAcrossTheSeam() {
        assertEquals(1f, DockFocus.seamFade(0, 8), 1e-6);
        assertEquals(1f, DockFocus.seamFade(2, 8), 1e-6);
        assertEquals(0f, DockFocus.seamFade(4, 8), 1e-6);
        assertEquals(0f, DockFocus.seamFade(-4, 8), 1e-6);
        assertTrue(DockFocus.seamFade(3.7f, 8) > 0f && DockFocus.seamFade(3.7f, 8) < 1f);
        assertEquals(1f, DockFocus.seamFade(5, 2), 1e-6); // not a ring: no seam
    }

    @Test
    public void pointerChevronNeverTouchesItsLabel() {
        assertTrue("chevron bottom " + HintView.chevronBottomDp() + " vs label top " + HintView.labelTopDp(),
                HintView.chevronBottomDp() + 3 <= HintView.labelTopDp());
    }

    @Test public void evenGapsStayEvenWhenAppsShrink() {
        float pitch = 392f, amount = 0.75f, tile = pitch * DockFocus.TILE_FRACTION;
        float prevGap = -1;
        for (int k = 0; k < 4; k++) {
            float xa = DockFocus.evenX(k, amount, pitch), xb = DockFocus.evenX(k + 1, amount, pitch);
            float edgeGap = (xb - xa) - tile * (DockFocus.scale(k, amount) + DockFocus.scale(k + 1, amount)) / 2f;
            if (prevGap >= 0) org.junit.Assert.assertEquals(prevGap, edgeGap, 0.01f);
            prevGap = edgeGap;
        }
        org.junit.Assert.assertEquals(0f, DockFocus.evenX(0, amount, pitch), 1e-4f);
        org.junit.Assert.assertEquals(-DockFocus.evenX(2, amount, pitch), DockFocus.evenX(-2, amount, pitch), 1e-3f);
        org.junit.Assert.assertEquals(0f, DockFocus.evenBlend(1f, 0.75f), 1e-6f);   // normal dock: untouched
        org.junit.Assert.assertEquals(1f, DockFocus.evenBlend(0.75f, 0.75f), 1e-6f); // clean mode: fully even
    }
}


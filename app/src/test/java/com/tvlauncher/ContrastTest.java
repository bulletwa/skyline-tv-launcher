package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContrastTest {
    @Test
    public void knownLuminanceAndContrastValues() {
        assertEquals(0.0, Contrast.luminance(0x000000), 1e-9);
        assertEquals(1.0, Contrast.luminance(0xFFFFFF), 1e-6);
        assertEquals(21.0, Contrast.whiteOn(0x000000), 1e-6);
        assertEquals(1.0, Contrast.whiteOn(0xFFFFFF), 1e-6);
        assertEquals(4.54, Contrast.whiteOn(0x767676), 0.02); // the classic AA grey
    }

    @Test
    public void darkSkiesNeedNoVeil() {
        assertEquals(0, Contrast.alphaFor(0x0A1230, 4.5, 0.6), 1e-9);
        assertEquals(0, Contrast.alphaFor(0xFFFFFF, 0.0, 0.6), 1e-9); // no requirement, no veil
    }

    @Test
    public void brightSkiesGetJustEnoughVeil() {
        int sky = 0xA8C4E8;
        double a = Contrast.alphaFor(sky, 4.5, 0.62);
        assertTrue(a > 0.2 && a < 0.62);
        assertTrue(Contrast.whiteOn(SkyModel.scale(sky, 1 - a)) >= 4.5 - 0.05);
        // and not much more than needed
        assertTrue(Contrast.whiteOn(SkyModel.scale(sky, 1 - (a - 0.04))) < 4.5);
    }

    @Test
    public void veilIsCappedAndMoreRatioNeedsMoreVeil() {
        assertEquals(0.30, Contrast.alphaFor(0xFFFFFF, 4.5, 0.30), 1e-9); // needs 0.535, so the cap wins
        assertEquals(0.535, Contrast.alphaFor(0xFFFFFF, 4.5, 0.62), 0.01);
        int sky = 0x9CB8DC;
        assertTrue(Contrast.alphaFor(sky, 4.5, 0.9) > Contrast.alphaFor(sky, 3.0, 0.9));
    }

    @Test
    public void darkenForReachesTheRatio() {
        for (int c : new int[]{0xFFFFFF, 0xC8D4E0, 0x94C6F5, 0x7AA0D0}) {
            assertTrue(Contrast.whiteOn(Contrast.darkenFor(c, 4.5)) >= 4.5 - 0.06);
        }
        assertEquals(0x102030, Contrast.darkenFor(0x102030, 4.5));
    }

    @Test
    public void whiteTextIsReadableInEveryNamedScene() {
        for (String name : Scenes.NAMES) {
            SkyRenderer.Input in = Scenes.build(name);
            float[] a = Contrast.zoneAlphas(in);
            for (int z = 0; z < Contrast.ZONES.length; z++) {
                int c = Contrast.worstCase(in, Contrast.ZONES[z]);
                int veiled = SkyModel.scale(c, 1 - a[z]);
                double got = Contrast.whiteOn(veiled);
                if (a[z] < Contrast.CAP - 1e-6) {
                    assertTrue(name + " zone " + z + " contrast " + got, got >= Contrast.RATIOS[z] - 0.06);
                } else {
                    assertTrue(name + " zone " + z + " (capped) contrast " + got, got >= 2.5);
                }
            }
        }
    }

    @Test
    public void headerTextIsTheStrictestZone() {
        // small text near the top must be at least as protected as the big clock below it
        for (String name : Scenes.NAMES) {
            float[] a = Contrast.zoneAlphas(Scenes.build(name));
            assertTrue(name, a[0] + 0.25 >= a[1] || a[1] < 0.1);
            assertTrue(name, a[2] == 0f); // the dock area has no text and the bottom fade handles it
        }
    }

    @Test
    public void aClearDeepNightNeedsNoVeilAtAll() {
        float[] a = Contrast.zoneAlphas(Scenes.build("night"));
        assertEquals(0f, a[0], 1e-6);
        assertEquals(0f, a[1], 1e-6);
    }

    @Test
    public void foggyDaylightIsDarkenedMoreThanClearDaylight() {
        float clear = Contrast.zoneAlphas(Scenes.build("day"))[0];
        float fog = Contrast.zoneAlphas(Scenes.build("fog"))[0];
        assertTrue("fog " + fog + " clear " + clear, fog > clear);
    }

    @org.junit.Test
    public void measuredInkFollowsTheBackground() {
        assertFalse(Contrast.useDark(0.10, 0.05, 4.5, false, 0));   // dark sky: stay white
        assertTrue(Contrast.useDark(0.55, 0.25, 4.5, false, 0));    // bright patch behind the text: go dark
        assertTrue(Contrast.useDark(0.50, 0.30, 4.5, true, 0));     // dark ink on a mid sky works: keep it
        assertFalse(Contrast.useDark(0.40, 0.04, 4.5, true, 0));    // dark ink on a dark patch fails and white is clearly better: switch
        assertTrue(Contrast.useDark(0.55, 0.04, 4.5, true, 0));     // both poor and close: no flip-flopping
    }

    @org.junit.Test
    public void buttonTintCountsAgainstWhiteIcons() {
        // 0.19 is fine for white on the bare sky (4.4:1) but not once the white glass disc lightens it
        assertTrue(Contrast.buttonDark(true, 0.17, 0.15, 0.16, 3.0));     // clear sky: icons match the dark text
        assertFalse(Contrast.buttonDark(false, 0.10, 0.05, 0.16, 3.0));  // night: stay white
        assertFalse(Contrast.buttonDark(true, 0.10, 0.02, 0.16, 3.0));   // dark ink fails on the icons' patch, white clears it: break away
        assertFalse(Contrast.useDark(0.19, 0.17, 3.0, false, 0));
        assertTrue(Contrast.useDark(0.30, 0.17, 3.0, false, 0.16));
    }
}

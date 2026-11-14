package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class PerfStatsTest {
    @Test public void parsesMeminfo() {
        PerfStats.Mem m = PerfStats.parseMeminfo("MemTotal:        2097152 kB\nMemFree:  94248 kB\nMemAvailable:     425180 kB\nSwapTotal: 1048572 kB\nSwapFree: 616028 kB\n");
        assertEquals(2097152, m.totalKb);
        assertEquals(425180, m.availKb);
        assertEquals("RAM  1.59 / 2.00 GB", PerfStats.ramLine(m));
        assertEquals("Free 415 MB  Swap 422 MB", PerfStats.freeLine(m));
    }

    @Test public void unreadableFilesGiveDashes() {
        assertEquals("RAM  --", PerfStats.ramLine(PerfStats.parseMeminfo("")));
    }

    @Test public void fpsNeedsAReasonableWindow() {
        assertEquals(60, PerfStats.fps(60, 1000));
        assertEquals(-1, PerfStats.fps(3, 50));
        assertEquals("1920x1080  60 Hz", PerfStats.screenLine(1920, 1080, 60.000004f));
    }

    @Test public void muteLongPressFiresOncePerPress() {
        KeyHold h = new KeyHold();
        int M = KeyEvent.KEYCODE_VOLUME_MUTE;
        assertTrue(h.onKey(M, true, 0));
        assertFalse(h.due(300));            // still a tap
        assertFalse(h.onKey(M, true, 400)); // auto-repeat is not a new press
        assertTrue(h.due(900));
        assertFalse(h.due(1200));           // once per press
        assertTrue(h.swallow(M, true));     // the rest of that press must not reach the TV
        assertTrue(h.swallow(M, false));    // including the release, which is what mutes
        assertFalse(h.swallow(M, false));
        h.onKey(M, false, 1300);
        assertTrue(h.onKey(M, true, 2000));
        h.onKey(M, false, 2100);            // released early: no toggle
        assertFalse(h.due(3000));
        assertFalse(h.swallow(M, false));   // a short tap still mutes as normal
        assertFalse(h.onKey(KeyEvent.KEYCODE_DPAD_LEFT, true, 3100));
    }

    @Test public void networkRatesAndLink() {
        assertEquals(500_000, PerfStats.bytesPerSec(1_000_000, 1_500_000, 1000));
        assertEquals(-1, PerfStats.bytesPerSec(-1, 5, 1000));   // counter unsupported
        assertEquals(-1, PerfStats.bytesPerSec(10, 5, 1000));   // went backwards
        assertEquals(-1, PerfStats.bytesPerSec(0, 5, 50));      // window too short
        assertEquals("DL 4.00  UL 0.08 Mb/s", PerfStats.rateLine(500_000, 10_000));
        assertEquals("DL --  UL -- Mb/s", PerfStats.rateLine(-1, -1));
        assertEquals("Wi-Fi  -52 dBm  433 Mbps", PerfStats.linkLine("wifi", -52, 433));
        assertEquals("Wi-Fi", PerfStats.linkLine("wifi", 0, -1));
        assertEquals("Ethernet", PerfStats.linkLine("ethernet", 0, 0));
        assertEquals("Offline", PerfStats.linkLine("none", 0, 0));
    }
}

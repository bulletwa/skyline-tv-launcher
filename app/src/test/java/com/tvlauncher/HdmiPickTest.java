package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HdmiPickTest {
    // Shapes taken from this TCL TV's `dumpsys tv_input`.
    private static final String CEC_PS5 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HDMI200004";
    private static final String PORT2 = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384";

    private static HdmiPick.Row port(String id, String label, int state) {
        return new HdmiPick.Row(id, label, null, state, -1);
    }

    @Test
    public void portComesFromTheCecPhysicalAddress() {
        assertEquals(2, HdmiPick.port(CEC_PS5));
        assertEquals(1, HdmiPick.port("x/y/HDMI100002"));
        assertEquals(3, HdmiPick.port("x/y/HDMI310004")); // 3.1.0.0: behind a switch on port 3
        assertEquals(4, HdmiPick.port("x/y/HDMI4000FF"));
        assertEquals(-1, HdmiPick.port(PORT2));            // hardware ports carry no address
        assertEquals(-1, HdmiPick.port(null));
        assertEquals("HDMI 2", HdmiPick.portText(CEC_PS5));
        assertEquals("", HdmiPick.portText(PORT2));
    }

    @Test
    public void recognisesPlayStationNames() {
        assertTrue(HdmiPick.looksLikePlayStation("PlayStation 5"));
        assertTrue(HdmiPick.looksLikePlayStation("PLAYSTATION"));
        assertTrue(HdmiPick.looksLikePlayStation("my PS5"));
        assertFalse(HdmiPick.looksLikePlayStation("HDMI 2"));
        assertFalse(HdmiPick.looksLikePlayStation("Xbox"));
        assertFalse(HdmiPick.looksLikePlayStation(""));
    }

    @Test
    public void picksTheCecDeviceOverTheBarePort() {
        List<HdmiPick.Row> rows = new ArrayList<>(Arrays.asList(
                port("p1", "HDMI 1", HdmiPick.DISCONNECTED),
                port("p2", "PlayStation 5", HdmiPick.STANDBY),            // a port that was renamed
                new HdmiPick.Row(CEC_PS5, "PlayStation 5", PORT2, HdmiPick.CONNECTED, HdmiPick.STANDBY)));
        assertEquals(CEC_PS5, HdmiPick.choose(rows, null).id);
        assertEquals(CEC_PS5, HdmiPick.choose(rows, "").id);
    }

    @Test
    public void yourChoiceInSettingsWinsEvenWithoutAPlayStationName() {
        List<HdmiPick.Row> rows = Arrays.asList(
                port("p1", "HDMI 1", HdmiPick.CONNECTED),
                new HdmiPick.Row(CEC_PS5, "PlayStation 5", PORT2, HdmiPick.CONNECTED, HdmiPick.CONNECTED));
        assertEquals("p1", HdmiPick.choose(rows, "p1").id);
    }

    @Test
    public void aSavedChoiceThatNoLongerExistsFallsBackToDetection() {
        List<HdmiPick.Row> rows = Arrays.asList(
                new HdmiPick.Row(CEC_PS5, "PlayStation 5", PORT2, HdmiPick.CONNECTED, HdmiPick.CONNECTED));
        assertEquals(CEC_PS5, HdmiPick.choose(rows, "gone").id);
    }

    @Test
    public void noConsoleMeansNull() {
        assertNull(HdmiPick.choose(Arrays.asList(port("p1", "HDMI 1", 0), port("p2", "HDMI 2", 1)), null));
        assertNull(HdmiPick.choose(new ArrayList<HdmiPick.Row>(), null));
    }

    @Test
    public void thePortStateBeatsTheConsolesOwnCecInput() {
        // this TV: the PS5's CEC input says CONNECTED while HDMI port 2 says STANDBY (console asleep)
        HdmiPick.Row asleep = new HdmiPick.Row(CEC_PS5, "PlayStation 5", PORT2, HdmiPick.CONNECTED, HdmiPick.STANDBY);
        assertEquals(HdmiPick.STANDBY, asleep.effectiveState());
        HdmiPick.Row on = new HdmiPick.Row(CEC_PS5, "PlayStation 5", PORT2, HdmiPick.CONNECTED, HdmiPick.CONNECTED);
        assertEquals(HdmiPick.CONNECTED, on.effectiveState());
        // no parent: its own state
        assertEquals(HdmiPick.DISCONNECTED, port("p", "x", HdmiPick.DISCONNECTED).effectiveState());
    }

    @Test
    public void statusWords() {
        assertEquals("On", HdmiPick.statusWord(HdmiPick.CONNECTED));
        assertEquals("Standby", HdmiPick.statusWord(HdmiPick.STANDBY));
        assertEquals("Not connected", HdmiPick.statusWord(HdmiPick.DISCONNECTED));
        assertEquals("Unknown", HdmiPick.statusWord(99));
    }
}

package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SleepTimerTest {
    @Test
    public void optionLabels() {
        assertEquals("Off", SleepTimer.optionLabel(0));
        assertEquals("15 minutes", SleepTimer.optionLabel(15));
        assertEquals("1 hour", SleepTimer.optionLabel(60));
        assertEquals("1 hour 30 min", SleepTimer.optionLabel(90));
        assertEquals("2 hours", SleepTimer.optionLabel(120));
    }

    @Test
    public void describeOffWhenNotSetOrExpired() {
        assertEquals("Off", SleepTimer.describe(0, 1_000_000));
        assertEquals("Off", SleepTimer.describe(1_000, 1_000_000));
    }

    @Test
    public void describeRoundsMinutesUp() {
        long now = 1_700_000_000_000L;
        String s = SleepTimer.describe(now + 44 * 60_000L + 10_000L, now);
        assertTrue(s, s.startsWith("in 45 min ("));
        assertTrue(s, s.endsWith("AM)") || s.endsWith("PM)"));
        assertTrue(SleepTimer.describe(now + 90 * 60_000L, now).startsWith("in 1 h 30 min ("));
    }
}

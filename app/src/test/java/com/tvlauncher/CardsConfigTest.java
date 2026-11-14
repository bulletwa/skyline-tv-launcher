package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class CardsConfigTest {
    @Test
    public void defaultsAreSpotlightAndNews() {
        assertEquals(Arrays.asList("spotlight", "news"), CardsConfig.defaults());
        assertEquals(CardsConfig.defaults(), CardsConfig.parse(null));
    }

    @Test
    public void parseKeepsDisplayOrderDropsUnknownAndDuplicates() {
        assertEquals(Arrays.asList("spotlight", "nowplaying"), CardsConfig.parse("nowplaying, bogus ,spotlight,spotlight"));
        assertEquals(Collections.emptyList(), CardsConfig.parse(""));
        assertEquals(Collections.emptyList(), CardsConfig.parse("nothing,here"));
    }

    @Test
    public void parseCapsAtThree() {
        assertEquals(CardsConfig.MAX, CardsConfig.parse("spotlight,news,nowplaying,weather").size());
        assertEquals(Arrays.asList("spotlight", "news", "nowplaying"), CardsConfig.parse("weather,nowplaying,news,spotlight"));
    }

    @Test
    public void joinRoundTrips() {
        for (String csv : new String[]{"", "news", "spotlight,weather", "spotlight,news,nowplaying"}) {
            assertEquals(csv, CardsConfig.join(CardsConfig.parse(csv)));
        }
    }

    @Test
    public void setSwitchesCardsOnAndOffInDisplayOrder() {
        assertEquals(Arrays.asList("spotlight", "news", "weather"), CardsConfig.set(CardsConfig.defaults(), "weather", true));
        assertEquals(Arrays.asList("news"), CardsConfig.set(CardsConfig.defaults(), "spotlight", false));
        assertEquals(Collections.emptyList(), CardsConfig.set(Arrays.asList("news"), "news", false));
        assertEquals(Arrays.asList("spotlight", "nowplaying"), CardsConfig.set(Arrays.asList("nowplaying"), "spotlight", true));
    }

    @Test
    public void fourthCardIsRefused() {
        java.util.List<String> three = Arrays.asList("spotlight", "news", "nowplaying");
        assertTrue(CardsConfig.full(three, "weather"));
        assertFalse(CardsConfig.full(three, "news")); // already on: switching off is fine
        assertEquals(three, CardsConfig.set(three, "weather", true));
    }

    @Test
    public void everyCardHasALabelAndDescription() {
        for (String id : CardsConfig.ALL) {
            assertTrue(id, !CardsConfig.label(id).equals(id));
            assertTrue(id, !CardsConfig.description(id).isEmpty());
        }
    }
}

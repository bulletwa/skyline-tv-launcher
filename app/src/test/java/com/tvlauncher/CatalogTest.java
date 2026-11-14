package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CatalogTest {
    private static Set<String> ids(List<NewsModels.Channel> l) {
        Set<String> s = new HashSet<>();
        for (NewsModels.Channel c : l) s.add(c.id);
        return s;
    }

    @Test
    public void everyChannelIsWellFormedAndUnique() {
        Set<String> seen = new HashSet<>();
        for (NewsModels.Channel c : Catalog.ALL) {
            assertTrue(c.id, seen.add(c.id));
            assertTrue(c.url, c.url.startsWith("https://"));
            assertTrue(c.name, !c.name.isEmpty());
            assertTrue(c.id, Arrays.asList(Catalog.CATEGORIES).contains(c.category));
            assertEquals(0, c.color & 0xFF000000);
        }
    }

    @Test
    public void localChannelsOnlyForTheirCountry() {
        assertTrue(ids(Catalog.forCountry("IN")).contains("ndtv"));
        assertFalse(ids(Catalog.forCountry("US")).contains("ndtv"));
        assertFalse(ids(Catalog.forCountry("")).contains("hindu"));
        assertTrue(ids(Catalog.forCountry("US")).contains("bbc-world"));
        assertTrue(ids(Catalog.forCountry("in")).contains("toi")); // case-insensitive
    }

    @Test
    public void defaultsExistAndAreAvailableToThatCountry() {
        for (String cc : new String[]{"IN", "US", ""}) {
            Set<String> avail = ids(Catalog.forCountry(cc));
            assertEquals(5, Catalog.defaults(cc).size());
            for (String id : Catalog.defaults(cc)) {
                assertNotNull(id, Catalog.find(id));
                assertTrue(cc + " " + id, avail.contains(id));
            }
        }
    }

    @Test
    public void filterByTabCategoryAndQuery() {
        List<NewsModels.Channel> all = Catalog.forCountry("IN");
        Set<String> following = new HashSet<>(Arrays.asList("bbc-world", "verge"));

        assertEquals(2, Catalog.filter(all, "All", "", following, true).size());
        assertEquals(all.size(), Catalog.filter(all, null, null, following, false).size());

        List<NewsModels.Channel> tech = Catalog.filter(all, "Technology", "", following, false);
        assertTrue(!tech.isEmpty());
        for (NewsModels.Channel c : tech) assertEquals("Technology", c.category);

        List<NewsModels.Channel> bbc = Catalog.filter(all, "All", "  BBC ", following, false);
        assertTrue(bbc.size() >= 3);
        for (NewsModels.Channel c : bbc) assertTrue(c.name.contains("BBC"));

        // the query also matches the category name
        assertTrue(ids(Catalog.filter(all, "All", "science", following, false)).contains("nasa"));
        // following + category + query together
        assertEquals(1, Catalog.filter(all, "Technology", "verge", following, true).size());
        assertTrue(Catalog.filter(all, "World", "verge", following, true).isEmpty());
    }

    @Test
    public void customUrlHelpers() {
        assertEquals("https://example.com/feed", Catalog.normalizeUrl(" example.com/feed "));
        assertEquals("https://example.com/feed", Catalog.normalizeUrl("http://example.com/feed"));
        assertEquals("", Catalog.normalizeUrl("   "));
        assertEquals("example.com", Catalog.nameFromUrl("https://www.example.com/rss.xml"));
        assertEquals("nasa.gov", Catalog.nameFromUrl("https://feeds.nasa.gov/x"));
    }
}

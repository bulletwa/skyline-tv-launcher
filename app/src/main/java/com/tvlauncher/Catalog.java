package com.tvlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Feeds that were tested live on 2026-10-30. Dead ones (TechCrunch 403, Economic Times 404, Lokmat 403, Loksatta) are left out. */
final class Catalog {
    private Catalog() {}

    static final String[] CATEGORIES = {"World", "India", "Technology", "Science", "Business"};

    static final List<NewsModels.Channel> ALL = Arrays.asList(
            c("bbc-world", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "World", 0xBB1919, null),
            c("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "World", 0xC77D00, null),
            c("bbc-tech", "BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml", "Technology", 0x8A1F9E, null),
            c("verge", "The Verge", "https://www.theverge.com/rss/index.xml", "Technology", 0xE0420F, null),
            c("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Technology", 0xC2410C, null),
            c("bbc-science", "BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "Science", 0x0E7490, null),
            c("nasa", "NASA", "https://www.nasa.gov/feed/", "Science", 0x0B3D91, null),
            c("hindu", "The Hindu", "https://www.thehindu.com/news/national/feeder/default.rss", "India", 0x1F4E79, "IN"),
            c("hindu-sci", "The Hindu Sci-Tech", "https://www.thehindu.com/sci-tech/feeder/default.rss", "Science", 0x2E6B8F, "IN"),
            c("toi", "Times of India", "https://timesofindia.indiatimes.com/rssfeedstopstories.cms", "India", 0xB71C1C, "IN"),
            c("ndtv", "NDTV", "https://feeds.feedburner.com/ndtvnews-top-stories", "India", 0xD32F2F, "IN"),
            c("indian-express", "Indian Express", "https://indianexpress.com/feed/", "India", 0x9C1C2C, "IN"),
            c("ht", "Hindustan Times", "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml", "India", 0x1565C0, "IN"),
            c("india-today", "India Today", "https://www.indiatoday.in/rss/1206578", "India", 0xC62828, "IN"),
            c("scroll", "Scroll", "https://feeds.feedburner.com/ScrollinArticles.rss", "India", 0x37474F, "IN"),
            c("mint", "LiveMint", "https://www.livemint.com/rss/news", "Business", 0x2E7D32, "IN"),
            c("firstpost", "Firstpost", "https://www.firstpost.com/commonfeeds/v1/mfp/rss/india.xml", "India", 0xD84315, "IN")
    );

    private static NewsModels.Channel c(String id, String name, String url, String cat, int color, String country) {
        return new NewsModels.Channel(id, name, url, cat, color, country);
    }

    static NewsModels.Channel find(String id) {
        for (NewsModels.Channel ch : ALL) if (ch.id.equals(id)) return ch;
        return null;
    }

    /** Channels offered to a user in the given country (global ones plus that country's local ones). */
    static List<NewsModels.Channel> forCountry(String cc) {
        List<NewsModels.Channel> out = new ArrayList<>();
        for (NewsModels.Channel ch : ALL) if (ch.country == null || ch.country.equalsIgnoreCase(cc)) out.add(ch);
        return out;
    }

    /**
     * What the News Channels list shows. {@code category} null/"All" = every category; {@code query} matches
     * the name or category, case-insensitively; {@code followingOnly} limits to followed ids.
     */
    static List<NewsModels.Channel> filter(List<NewsModels.Channel> all, String category, String query,
                                           java.util.Set<String> followed, boolean followingOnly) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<NewsModels.Channel> out = new ArrayList<>();
        for (NewsModels.Channel ch : all) {
            if (followingOnly && !followed.contains(ch.id)) continue;
            if (category != null && !category.equals("All") && !category.equalsIgnoreCase(ch.category)) continue;
            if (!q.isEmpty() && !ch.name.toLowerCase().contains(q) && !ch.category.toLowerCase().contains(q)) continue;
            out.add(ch);
        }
        return out;
    }

    /** Hostname-based display name for a feed the user typed in. */
    static String nameFromUrl(String url) {
        try {
            String host = new java.net.URI(url.trim()).getHost();
            if (host == null) return url.trim();
            host = host.replaceFirst("^(www|feeds|rss)\\.", "");
            return host;
        } catch (Exception e) {
            return url.trim();
        }
    }

    /** Adds https:// when the user typed a bare address. */
    static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return u;
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        if (!u.startsWith("https://")) return "https://" + u;
        return u;
    }

    static List<String> defaults(String cc) {
        if ("IN".equalsIgnoreCase(cc)) return Arrays.asList("bbc-world", "ndtv", "hindu", "toi", "verge");
        return Arrays.asList("bbc-world", "aljazeera", "verge", "bbc-tech", "nasa");
    }

    /** A user-supplied feed URL becomes a channel with a colour derived from its address. */
    static NewsModels.Channel custom(String name, String url) {
        int h = Math.abs(url.hashCode());
        int color = android.graphics.Color.HSVToColor(new float[]{h % 360, 0.55f, 0.55f}) & 0xFFFFFF;
        return new NewsModels.Channel("custom-" + Integer.toHexString(url.hashCode()), name, url, "Custom", color, null);
    }
}

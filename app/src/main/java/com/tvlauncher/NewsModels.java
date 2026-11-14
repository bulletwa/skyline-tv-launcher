package com.tvlauncher;

/** Plain data for the news feature (no Android imports so it can be unit-tested). */
final class NewsModels {
    private NewsModels() {}

    static final class Channel {
        final String id, name, url, category;
        /** Opaque 0xRRGGBB used for the fallback tile and the channel dot. */
        final int color;
        /** Non-null for channels only shown to one country, e.g. "IN". */
        final String country;

        Channel(String id, String name, String url, String category, int color, String country) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.category = category;
            this.color = color;
            this.country = country;
        }

        String initials() {
            String[] parts = name.trim().split("\\s+");
            if (parts.length >= 2) {
                return ("" + Character.toUpperCase(parts[0].charAt(0)) + Character.toUpperCase(parts[1].charAt(0)));
            }
            String n = parts[0];
            return n.length() <= 2 ? n.toUpperCase() : n.substring(0, 2).toUpperCase();
        }
    }

    static final class Story {
        final Channel channel;
        final String title, summary, link;
        /** May be filled in later from the page's og:image. */
        String imageUrl;
        boolean pageImageTried;
        final long publishedMs; // 0 = unknown

        Story(Channel channel, String title, String summary, String link, String imageUrl, long publishedMs) {
            this.channel = channel;
            this.title = title;
            this.summary = summary;
            this.link = link;
            this.imageUrl = imageUrl;
            this.publishedMs = publishedMs;
        }
    }
}

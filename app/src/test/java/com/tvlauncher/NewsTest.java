package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NewsTest {
    private static final NewsModels.Channel CH = new NewsModels.Channel("t", "The Test", "http://x", "World", 0xAA0000, null);
    private static final long NOW = Instant.parse("2026-10-30T15:30:00Z").toEpochMilli();

    // ------------------------------------------------------------ dates

    @Test
    public void parsesRfc822WithZoneNameAndOffset() {
        long gmt = TextUtil.parseDate("Fri, 30 Oct 2026 14:19:43 GMT");
        assertEquals(Instant.parse("2026-10-30T14:19:43Z").toEpochMilli(), gmt);
        long ist = TextUtil.parseDate("Fri, 30 Oct 2026 20:37:07 +0530");
        assertEquals(Instant.parse("2026-10-30T15:07:07Z").toEpochMilli(), ist);
        assertEquals(Instant.parse("2026-10-30T09:05:00Z").toEpochMilli(),
                TextUtil.parseDate("Fri, 30 Oct 2026 09:05 +0000"));
    }

    @Test
    public void parsesIso8601() {
        assertEquals(Instant.parse("2026-10-30T15:29:02Z").toEpochMilli(),
                TextUtil.parseDate("2026-10-30T15:29:02+00:00"));
        assertEquals(Instant.parse("2026-10-30T10:00:00Z").toEpochMilli(),
                TextUtil.parseDate("2026-10-30T15:30:00+05:30"));
        assertEquals(Instant.parse("2026-10-30T15:29:02Z").toEpochMilli(), TextUtil.parseDate("2026-10-30T15:29:02Z"));
        assertEquals(Instant.parse("2026-10-30T15:29:02Z").toEpochMilli(), TextUtil.parseDate("2026-10-30T15:29:02"));
    }

    @Test
    public void unreadableDatesAreZero() {
        assertEquals(0, TextUtil.parseDate(null));
        assertEquals(0, TextUtil.parseDate(""));
        assertEquals(0, TextUtil.parseDate("yesterday-ish"));
    }

    @Test
    public void agoText() {
        assertEquals("", TextUtil.ago(0, NOW));
        assertEquals("just now", TextUtil.ago(NOW - 20_000, NOW));
        assertEquals("just now", TextUtil.ago(NOW + 60_000, NOW)); // slight clock skew
        assertEquals("5m ago", TextUtil.ago(NOW - 5 * 60_000L, NOW));
        assertEquals("2h ago", TextUtil.ago(NOW - 2 * 3600_000L - 5 * 60_000L, NOW));
        assertEquals("3d ago", TextUtil.ago(NOW - 3 * 86400_000L, NOW));
        assertEquals("2w ago", TextUtil.ago(NOW - 15 * 86400_000L, NOW));
    }

    // ------------------------------------------------------------ text cleaning

    @Test
    public void cleanStripsTagsAndDecodesEntities() {
        assertEquals("Tom & Jerry's \"big\" day – today",
                TextUtil.clean("<p>Tom &amp; Jerry&#39;s &quot;big&quot;&nbsp;day &ndash; <b>today</b></p>"));
        assertEquals("a b", TextUtil.clean("a\n\t  <br/>  b"));
        assertEquals("Hello", TextUtil.clean("&lt;p&gt;Hello&lt;/p&gt;"));
        assertEquals("", TextUtil.clean(null));
    }

    @Test
    public void truncateBreaksOnWords() {
        String t = TextUtil.truncate("one two three four five six", 12);
        assertTrue(t.endsWith("…"));
        assertTrue(t.length() <= 13);
        assertEquals("short", TextUtil.truncate("short", 12));
    }

    // ------------------------------------------------------------ RSS / Atom

    private static final String RSS = "<?xml version=\"1.0\"?><rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\">"
            + "<channel><title>Feed</title>"
            + "<item><title><![CDATA[Big &amp; bold headline]]></title>"
            + "<link>https://example.com/a/1</link>"
            + "<description><![CDATA[<p>First <b>summary</b>&nbsp;text.</p>]]></description>"
            + "<pubDate><![CDATA[Fri, 30 Oct 2026 14:19:43 GMT]]></pubDate>"
            + "<media:content medium=\"image\" url=\"https://img.example.com/small.jpg\" width=\"300\"/>"
            + "<media:content medium=\"image\" url=\"https://img.example.com/big.jpg\" width=\"1200\"/></item>"
            + "<item><title>No image but enclosure</title><link>https://example.com/a/2</link>"
            + "<pubDate>Fri, 30 Oct 2026 12:00:00 +0000</pubDate>"
            + "<enclosure url=\"/pics/2.jpg\" type=\"image/jpeg\" length=\"1\"/></item>"
            + "<item><title>Inline image</title><link>https://example.com/a/3</link>"
            + "<description>&lt;img src=\"https://img.example.com/inline.png\" /&gt; Words &amp; more</description></item>"
            + "<item><title>Nothing at all</title><link>https://example.com/a/4</link>"
            + "<description>Just text AT&T and &nbsp; stuff</description></item>"
            + "<item><title>Tracking pixel only</title><link>https://example.com/a/5</link>"
            + "<description>&lt;img src=\"http://feeds.feedburner.com/~r/x/~4/abc\"/&gt;</description></item>"
            + "</channel></rss>";

    @Test
    public void parsesRssItemsAndPicksTheLargestMedia() throws Exception {
        List<NewsModels.Story> s = FeedParser.parse(RSS, CH);
        assertEquals(5, s.size());
        assertEquals("Big & bold headline", s.get(0).title);
        assertEquals("First summary text.", s.get(0).summary);
        assertEquals("https://img.example.com/big.jpg", s.get(0).imageUrl);
        assertEquals(Instant.parse("2026-10-30T14:19:43Z").toEpochMilli(), s.get(0).publishedMs);
        assertEquals("https://example.com/a/1", s.get(0).link);
    }

    @Test
    public void enclosureIsResolvedAgainstTheStoryLink() throws Exception {
        List<NewsModels.Story> s = FeedParser.parse(RSS, CH);
        assertEquals("https://example.com/pics/2.jpg", s.get(1).imageUrl);
    }

    @Test
    public void imageFallsBackToInlineImgThenNull() throws Exception {
        List<NewsModels.Story> s = FeedParser.parse(RSS, CH);
        assertEquals("https://img.example.com/inline.png", s.get(2).imageUrl);
        assertEquals("Words & more", s.get(2).summary);
        assertNull(s.get(3).imageUrl);
        assertTrue(s.get(3).summary.startsWith("Just text AT&T"));
        assertNull(s.get(4).imageUrl); // feedburner beacon is not a picture
    }

    @Test
    public void parsesAtom() throws Exception {
        String atom = "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Verge</title>"
                + "<entry><title type=\"html\">Atom story</title>"
                + "<link rel=\"alternate\" type=\"text/html\" href=\"https://www.theverge.com/x/1\"/>"
                + "<published>2026-10-30T15:29:02+00:00</published><updated>2026-10-30T16:00:00+00:00</updated>"
                + "<content type=\"html\">&lt;figure&gt;&lt;img alt=\"a\" src=\"https://cdn.vox/pic.jpg?quality=90&amp;w=1200\" /&gt;&lt;/figure&gt;&lt;p&gt;Body text here.&lt;/p&gt;</content>"
                + "</entry></feed>";
        List<NewsModels.Story> s = FeedParser.parse(atom, CH);
        assertEquals(1, s.size());
        assertEquals("https://www.theverge.com/x/1", s.get(0).link);
        assertEquals("https://cdn.vox/pic.jpg?quality=90&w=1200", s.get(0).imageUrl);
        assertEquals("Body text here.", s.get(0).summary);
        assertEquals(Instant.parse("2026-10-30T15:29:02Z").toEpochMilli(), s.get(0).publishedMs);
    }

    @Test(expected = Exception.class)
    public void rejectsNonXml() throws Exception {
        FeedParser.parse("<html><body>403 Forbidden</body", CH);
    }

    @Test
    public void sanitizeKeepsRealEntitiesAndFixesBareAmpersands() {
        String s = FeedParser.sanitize("a & b &amp; c &#169; &nbsp; d");
        assertEquals("a &amp; b &amp; c &#169; &amp;nbsp; d", s);
        // inside CDATA nothing is rewritten
        assertEquals("x &amp; <![CDATA[a &nbsp; b & c]]> &amp;y", FeedParser.sanitize("x & <![CDATA[a &nbsp; b & c]]> &y"));
    }

    // ------------------------------------------------------------ og:image

    @Test
    public void extractsOgImageInEitherAttributeOrder() {
        String a = "<html><head><meta property=\"og:image\" content=\"https://cdn.x/a.jpg\"></head>";
        String b = "<meta content='https://cdn.x/b.jpg?x=1&amp;y=2' property='og:image' />";
        assertEquals("https://cdn.x/a.jpg", FeedParser.extractOgImage(a, "https://x/p"));
        assertEquals("https://cdn.x/b.jpg?x=1&y=2", FeedParser.extractOgImage(b, "https://x/p"));
    }

    @Test
    public void ogImageResolvesRelativeAndFallsBackToTwitter() {
        assertEquals("https://x.com/img/a.jpg",
                FeedParser.extractOgImage("<meta name=\"twitter:image\" content=\"/img/a.jpg\">", "https://x.com/news/1"));
        assertEquals("https://x.com/og.jpg", FeedParser.extractOgImage(
                "<meta name=\"twitter:image\" content=\"/tw.jpg\"><meta property=\"og:image\" content=\"/og.jpg\">", "https://x.com/n"));
        assertNull(FeedParser.extractOgImage("<html><meta name=\"description\" content=\"hi\"></html>", "https://x.com"));
        assertNull(FeedParser.extractOgImage(null, "https://x.com"));
    }

    // ------------------------------------------------------------ merge rules

    private static NewsModels.Story story(NewsModels.Channel c, String t, long ageMin, String img) {
        return new NewsModels.Story(c, t, "", "https://x/" + t, img, NOW - ageMin * 60_000L);
    }

    @Test
    public void mergeIsNewestFirstAndCapsPerChannelAndTotal() {
        NewsModels.Channel a = new NewsModels.Channel("a", "Alpha", "u", "World", 0, null);
        NewsModels.Channel b = new NewsModels.Channel("b", "Beta", "u", "World", 0, null);
        List<NewsModels.Story> la = new ArrayList<>(), lb = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            la.add(story(a, "a" + i, i * 10, null));
            lb.add(story(b, "b" + i, i * 10 + 5, null));
        }
        List<NewsModels.Story> m = FeedParser.merge(Arrays.asList(la, lb));
        assertEquals(16, m.size()); // 8 per channel
        for (int i = 1; i < m.size(); i++) assertTrue(m.get(i - 1).publishedMs >= m.get(i).publishedMs);
        assertEquals("a0", m.get(0).title);
        assertEquals("b0", m.get(1).title);

        List<List<NewsModels.Story>> many = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            NewsModels.Channel ch = new NewsModels.Channel("c" + c, "C" + c, "u", "World", 0, null);
            List<NewsModels.Story> l = new ArrayList<>();
            for (int i = 0; i < 8; i++) l.add(story(ch, "c" + c + "_" + i, i, null));
            many.add(l);
        }
        assertEquals(FeedParser.TOTAL, FeedParser.merge(many).size());
    }

    @Test
    public void mergeNeverRepeatsAPicture() {
        NewsModels.Channel a = new NewsModels.Channel("a", "Alpha", "u", "World", 0, null);
        List<NewsModels.Story> l = new ArrayList<>();
        l.add(story(a, "first", 1, "https://cdn/logo.jpg?w=100"));
        l.add(story(a, "second", 2, "https://cdn/logo.jpg?w=800"));
        l.add(story(a, "third", 3, "https://cdn/other.jpg"));
        List<NewsModels.Story> m = FeedParser.merge(java.util.Collections.singletonList(l));
        assertNotNull(m.get(0).imageUrl);
        assertNull(m.get(1).imageUrl); // same picture as the first
        assertNotNull(m.get(2).imageUrl);
    }

    @Test
    public void unknownDatesSortLast() {
        NewsModels.Channel a = new NewsModels.Channel("a", "Alpha", "u", "World", 0, null);
        NewsModels.Story undated = new NewsModels.Story(a, "undated", "", "l", null, 0);
        List<NewsModels.Story> l = new ArrayList<>(Arrays.asList(undated, story(a, "dated", 100, null)));
        assertEquals("dated", FeedParser.merge(java.util.Collections.singletonList(l)).get(0).title);
    }

    // ------------------------------------------------------------ channel

    @Test
    public void initialsFromName() {
        assertEquals("BW", new NewsModels.Channel("x", "BBC World", "u", "c", 0, null).initials());
        assertEquals("TV", new NewsModels.Channel("x", "The Verge", "u", "c", 0, null).initials());
        assertEquals("NA", new NewsModels.Channel("x", "NASA", "u", "c", 0, null).initials());
        assertEquals("X", new NewsModels.Channel("x", "X", "u", "c", 0, null).initials());
    }
}

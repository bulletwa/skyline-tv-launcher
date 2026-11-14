package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class WikiParseTest {
    private static final String JSON = "{"
            + "\"tfa\":{\"title\":\"Some_Article\",\"titles\":{\"normalized\":\"Some Article\"},\"description\":\"A thing\","
            + "\"thumbnail\":{\"source\":\"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Some.jpg/330px-Some.jpg\",\"width\":330,\"height\":500}},"
            + "\"image\":{\"title\":\"File:Lake view.jpg\",\"thumbnail\":{\"source\":\"https://thumb.wikimedia.org/wikipedia/commons/thumb/2/25/Lake.jpg/960px-Lake.jpg\",\"width\":960,\"height\":640},"
            + "\"description\":{\"text\":\"Sunrays <b>shining</b> through trees\"}},"
            + "\"mostread\":{\"articles\":["
            + "{\"title\":\"Main_Page\",\"thumbnail\":{\"source\":\"https://x/thumb/1/1/a.jpg/330px-a.jpg\",\"width\":400,\"height\":200}},"
            + "{\"title\":\"Portrait_Person\",\"titles\":{\"normalized\":\"Portrait Person\"},\"thumbnail\":{\"source\":\"https://x/thumb/2/2/b.jpg/330px-b.jpg\",\"width\":330,\"height\":420}},"
            + "{\"title\":\"Big_Bridge\",\"titles\":{\"normalized\":\"Big Bridge\"},\"description\":\"Bridge in Place\",\"thumbnail\":{\"source\":\"https://x/thumb/3/3/c.jpg/330px-c.jpg\",\"width\":330,\"height\":200}},"
            + "{\"title\":\"No_Picture\"}"
            + "]}}";

    @Test
    public void widenAsksForALargerThumbnail() {
        assertEquals("https://x/thumb/3/3/c.jpg/960px-c.jpg?utm=1", WikiParse.widen("https://x/thumb/3/3/c.jpg/330px-c.jpg?utm=1", 960));
        assertEquals("https://upload.wikimedia.org/a/b.jpg", WikiParse.widen("https://upload.wikimedia.org/a/b.jpg", 960));
    }

    @Test
    public void buildsItemsInDisplayOrder() throws Exception {
        List<WikiParse.Item> items = WikiParse.parse(JSON);
        assertEquals(3, items.size());
        assertEquals("PICTURE OF THE DAY", items.get(0).kind);
        assertEquals("Sunrays shining through trees", items.get(0).title);
        assertEquals("FEATURED ARTICLE", items.get(1).kind);
        assertTrue(items.get(1).imageUrl.contains("/960px-Some.jpg")); // featured article may be a portrait
        assertEquals("TRENDING ON WIKIPEDIA", items.get(2).kind);
        assertEquals("Big Bridge", items.get(2).title);
        assertEquals("Bridge in Place", items.get(2).subtitle);
    }

    @Test
    public void trendingSkipsPortraitsMainPageAndPictureless() throws Exception {
        for (WikiParse.Item i : WikiParse.parse(JSON)) {
            assertTrue(!i.title.contains("Portrait") && !i.title.contains("Main"));
        }
    }
}

package com.tvlauncher;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** RSS 2.0 / Atom reader, og:image extraction and cross-channel merging. Pure Java. */
final class FeedParser {
    private FeedParser() {}

    static final int PER_CHANNEL = 8, TOTAL = 30;

    private static final Pattern BAD_AMP =
            Pattern.compile("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");
    private static final Pattern IMG_SRC =
            Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[.*?\\]\\]>", Pattern.DOTALL);

    /**
     * HTML entities like &nbsp; are not valid XML; escape their ampersand so the document parses and
     * decode them later. CDATA sections are left alone: entities are not interpreted there.
     */
    static String sanitize(String xml) {
        StringBuilder out = new StringBuilder(xml.length() + 64);
        Matcher m = CDATA.matcher(xml);
        int last = 0;
        while (m.find()) {
            out.append(BAD_AMP.matcher(xml.substring(last, m.start())).replaceAll("&amp;"));
            out.append(m.group());
            last = m.end();
        }
        out.append(BAD_AMP.matcher(xml.substring(last)).replaceAll("&amp;"));
        return out.toString();
    }

    static List<NewsModels.Story> parse(String xml, NewsModels.Channel ch) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // not every parser (Android's included) supports these toggles; it does not fetch DTDs anyway
        }
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        String text = sanitize(xml.trim().replace("\uFEFF", ""));
        org.w3c.dom.Document doc = b.parse(new InputSource(new StringReader(text)));
        NodeList items = doc.getElementsByTagName("item");
        if (items.getLength() == 0) items = doc.getElementsByTagName("entry");
        List<NewsModels.Story> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element it = (Element) items.item(i);
            String title = TextUtil.clean(text(it, "title"));
            if (title.isEmpty()) continue;
            String rawDesc = firstNonEmpty(text(it, "content:encoded"), text(it, "description"),
                    text(it, "summary"), text(it, "content"));
            String summary = TextUtil.truncate(TextUtil.clean(firstNonEmpty(text(it, "description"),
                    text(it, "summary"), text(it, "content:encoded"), text(it, "content"))), 600);
            if (summary.equalsIgnoreCase(title)) summary = "";
            String link = link(it);
            long pub = TextUtil.parseDate(firstNonEmpty(text(it, "pubDate"), text(it, "published"),
                    text(it, "updated"), text(it, "dc:date")));
            String image = image(it, rawDesc);
            if (image != null && link != null) image = resolve(link, image);
            out.add(new NewsModels.Story(ch, title, summary, link, image, pub));
        }
        return out;
    }

    // ------------------------------------------------------------------ element helpers

    private static String text(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        for (int i = 0; i < l.getLength(); i++) {
            String t = l.item(i).getTextContent();
            if (t != null && !t.trim().isEmpty()) return t.trim();
        }
        return "";
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s;
        return "";
    }

    private static String link(Element it) {
        NodeList l = it.getElementsByTagName("link");
        String fallback = null;
        for (int i = 0; i < l.getLength(); i++) {
            Element e = (Element) l.item(i);
            String t = e.getTextContent() == null ? "" : e.getTextContent().trim();
            if (!t.isEmpty()) return t;
            String href = e.getAttribute("href");
            if (!href.isEmpty()) {
                String rel = e.getAttribute("rel");
                if (rel.isEmpty() || rel.equals("alternate")) return href;
                if (fallback == null) fallback = href;
            }
        }
        return fallback;
    }

    private static String image(Element it, String rawHtml) {
        String best = null;
        int bestW = -1;
        NodeList mc = it.getElementsByTagName("media:content");
        for (int i = 0; i < mc.getLength(); i++) {
            Element e = (Element) mc.item(i);
            String url = e.getAttribute("url");
            String medium = e.getAttribute("medium"), type = e.getAttribute("type");
            boolean isImage = medium.equals("image") || type.startsWith("image") || (medium.isEmpty() && type.isEmpty() && looksLikeImage(url));
            if (!isImage || !usable(url)) continue;
            int w = intAttr(e, "width");
            if (best == null || w > bestW) {
                best = url;
                bestW = w;
            }
        }
        if (best != null) return best;
        for (String tag : new String[]{"media:thumbnail", "itunes:image"}) {
            NodeList l = it.getElementsByTagName(tag);
            for (int i = 0; i < l.getLength(); i++) {
                String url = ((Element) l.item(i)).getAttribute(tag.startsWith("itunes") ? "href" : "url");
                if (usable(url)) return url;
            }
        }
        NodeList enc = it.getElementsByTagName("enclosure");
        for (int i = 0; i < enc.getLength(); i++) {
            Element e = (Element) enc.item(i);
            String url = e.getAttribute("url");
            if ((e.getAttribute("type").startsWith("image") || looksLikeImage(url)) && usable(url)) return url;
        }
        Matcher m = IMG_SRC.matcher(TextUtil.decodeEntities(rawHtml));
        while (m.find()) {
            if (usable(m.group(1))) return m.group(1);
        }
        return null;
    }

    private static int intAttr(Element e, String name) {
        try {
            return Integer.parseInt(e.getAttribute(name));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean looksLikeImage(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") || u.endsWith(".webp") || u.endsWith(".gif");
    }

    /** Skips tracking pixels, feedburner beacons and obvious non-photos. */
    private static boolean usable(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.toLowerCase();
        return !(u.contains("/~r/") || u.contains("/~ff/") || u.contains("pixel") || u.contains("1x1")
                || u.endsWith(".mp4") || u.endsWith(".gif") || u.startsWith("data:"));
    }

    static String resolve(String base, String url) {
        try {
            return new URI(base).resolve(url.trim()).toString();
        } catch (Exception e) {
            return url.trim();
        }
    }

    // ------------------------------------------------------------------ og:image

    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z:_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /** og:image / twitter:image from a page's HTML, in either attribute order; null if none. */
    static String extractOgImage(String html, String pageUrl) {
        if (html == null) return null;
        String twitter = null;
        Matcher m = META.matcher(html);
        while (m.find()) {
            String prop = null, content = null;
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                String name = a.group(1).toLowerCase();
                String val = a.group(2) != null ? a.group(2) : a.group(3);
                if (name.equals("property") || name.equals("name")) prop = val.toLowerCase();
                else if (name.equals("content")) content = val;
            }
            if (prop == null || content == null || content.trim().isEmpty()) continue;
            String url = resolve(pageUrl, TextUtil.decodeEntities(content));
            if (prop.equals("og:image") || prop.equals("og:image:url") || prop.equals("og:image:secure_url")) return url;
            if (prop.equals("twitter:image") || prop.equals("twitter:image:src")) twitter = url;
        }
        return twitter;
    }

    // ------------------------------------------------------------------ merge

    /**
     * Newest first, at most PER_CHANNEL per channel and TOTAL overall. A picture URL already used by an
     * earlier story is dropped from later ones so the same photo never repeats.
     */
    static List<NewsModels.Story> merge(List<List<NewsModels.Story>> perChannel) {
        Comparator<NewsModels.Story> newest = (a, b) -> Long.compare(b.publishedMs, a.publishedMs);
        List<NewsModels.Story> all = new ArrayList<>();
        for (List<NewsModels.Story> list : perChannel) {
            List<NewsModels.Story> copy = new ArrayList<>(list);
            Collections.sort(copy, newest);
            all.addAll(copy.subList(0, Math.min(PER_CHANNEL, copy.size())));
        }
        Collections.sort(all, newest);
        if (all.size() > TOTAL) all = new ArrayList<>(all.subList(0, TOTAL));
        Set<String> used = new HashSet<>();
        for (NewsModels.Story s : all) {
            if (s.imageUrl == null) continue;
            String key = s.imageUrl.replaceAll("\\?.*$", "");
            if (!used.add(key)) s.imageUrl = null;
        }
        return all;
    }
}

package com.tvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Wikipedia "featured feed" -> spotlight items (picture of the day, featured article, trending). Pure Java. */
final class WikiParse {
    private WikiParse() {}

    static final class Item {
        final String kind, title, subtitle, imageUrl, pageUrl;

        Item(String kind, String title, String subtitle, String imageUrl, String pageUrl) {
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
            this.imageUrl = imageUrl;
            this.pageUrl = pageUrl;
        }
    }

    /** Asks the thumbnail service for a wider rendition (…/330px-X.jpg -> …/960px-X.jpg). */
    static String widen(String url, int px) {
        if (url == null) return null;
        if (!url.contains("/thumb/")) return url;
        return url.replaceFirst("/\\d+px-", "/" + px + "px-");
    }

    static List<Item> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        List<Item> out = new ArrayList<>();

        JSONObject pic = root.optJSONObject("image");
        if (pic != null) {
            JSONObject th = pic.optJSONObject("thumbnail");
            if (th != null && th.optString("source").length() > 0 && landscape(th)) {
                String desc = pic.optJSONObject("description") != null ? pic.getJSONObject("description").optString("text") : "";
                out.add(new Item("PICTURE OF THE DAY", TextUtil.truncate(TextUtil.clean(desc), 90), "Wikimedia Commons",
                        widen(th.getString("source"), 960), "https://commons.wikimedia.org/wiki/" + pic.optString("title").replace(' ', '_')));
            }
        }
        JSONObject tfa = root.optJSONObject("tfa");
        if (tfa != null) addArticle(out, "FEATURED ARTICLE", tfa, true);

        JSONObject most = root.optJSONObject("mostread");
        JSONArray arts = most == null ? null : most.optJSONArray("articles");
        int trending = 0;
        for (int i = 0; arts != null && i < arts.length() && trending < 4; i++) {
            JSONObject a = arts.getJSONObject(i);
            String t = a.optString("title");
            if (t.startsWith("Main_Page") || t.startsWith("Special:") || t.startsWith("Wikipedia:")) continue;
            if (addArticle(out, "TRENDING ON WIKIPEDIA", a, false)) trending++;
        }
        return out;
    }

    private static boolean landscape(JSONObject thumb) {
        int w = thumb.optInt("width"), h = thumb.optInt("height");
        return h <= 0 || w >= h * 1.15;
    }

    /** Card art is wide, so portraits are skipped unless {@code allowAny}. */
    private static boolean addArticle(List<Item> out, String kind, JSONObject a, boolean allowAny) throws JSONException {
        JSONObject th = a.optJSONObject("thumbnail");
        if (th == null || th.optString("source").isEmpty()) return false;
        if (!allowAny && !landscape(th)) return false;
        JSONObject titles = a.optJSONObject("titles");
        String title = titles != null ? titles.optString("normalized") : a.optString("title").replace('_', ' ');
        String desc = TextUtil.clean(a.optString("description"));
        if (desc.isEmpty()) desc = TextUtil.truncate(TextUtil.clean(a.optString("extract")), 110);
        String page = "https://en.wikipedia.org/wiki/" + a.optString("title");
        out.add(new Item(kind, title, desc, widen(th.getString("source"), 960), page));
        return true;
    }
}

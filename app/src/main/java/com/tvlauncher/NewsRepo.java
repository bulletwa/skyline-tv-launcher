package com.tvlauncher;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Followed channels, feed fetching (newest first, 8 per channel, 30 total) and og:image lookups. */
final class NewsRepo {
    interface Listener {
        void onStories(List<NewsModels.Story> stories);
    }

    private static final long STALE_MS = 20 * 60 * 1000L;

    private final Prefs prefs;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Pools.fixed(3, "news");
    private final ExecutorService coordinator = Pools.fixed(1, "news-main");
    private List<NewsModels.Story> stories = new ArrayList<>();
    private long lastFetch;
    private boolean fetching, refreshAgain;

    NewsRepo(Prefs prefs, Listener l) {
        this.prefs = prefs;
        this.listener = l;
    }

    List<NewsModels.Story> stories() {
        return stories;
    }

    // ------------------------------------------------------------------ channels

    private String country() {
        return prefs.str("cc", "");
    }

    List<NewsModels.Channel> custom() {
        List<NewsModels.Channel> out = new ArrayList<>();
        String s = prefs.str("news_custom", "");
        for (String line : s.split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 2 && !p[1].isEmpty()) out.add(Catalog.custom(p[0], p[1]));
        }
        return out;
    }

    void addCustom(String name, String url) {
        StringBuilder sb = new StringBuilder(prefs.str("news_custom", ""));
        if (sb.length() > 0) sb.append('\n');
        sb.append(name.replace('\t', ' ').replace('\n', ' ')).append('\t').append(url.trim());
        prefs.putStr("news_custom", sb.toString());
        List<String> ids = followedIds();
        ids.add(Catalog.custom(name, url.trim()).id);
        setFollowed(ids);
    }

    void removeCustom(String id) {
        StringBuilder sb = new StringBuilder();
        for (NewsModels.Channel c : custom()) {
            if (c.id.equals(id)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.name).append('\t').append(c.url);
        }
        prefs.putStr("news_custom", sb.toString());
        List<String> ids = followedIds();
        ids.remove(id);
        setFollowed(ids);
    }

    /** Everything the user can pick from: this country's catalogue plus their own feeds. */
    List<NewsModels.Channel> available() {
        List<NewsModels.Channel> all = new ArrayList<>(Catalog.forCountry(country()));
        all.addAll(custom());
        return all;
    }

    List<String> followedIds() {
        String s = prefs.str("news_follow", null);
        List<String> ids = new ArrayList<>();
        if (s == null) {
            ids.addAll(Catalog.defaults(country()));
        } else if (!s.isEmpty()) {
            for (String id : s.split(",")) ids.add(id);
        }
        return ids;
    }

    List<NewsModels.Channel> followed() {
        List<NewsModels.Channel> out = new ArrayList<>();
        List<NewsModels.Channel> avail = available();
        for (String id : followedIds()) {
            for (NewsModels.Channel c : avail) {
                if (c.id.equals(id)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    void setFollowed(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs.putStr("news_follow", sb.toString());
    }

    // ------------------------------------------------------------------ fetching

    void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (fetching) {
            if (force) refreshAgain = true; // the channel list changed mid-fetch: run once more when it ends
            return;
        }
        if (!force && !stories.isEmpty() && now - lastFetch < STALE_MS) return;
        fetching = true;
        refreshAgain = false;
        final List<NewsModels.Channel> channels = followed();
        coordinator.execute(new Runnable() {
            @Override public void run() {
                List<Future<List<NewsModels.Story>>> futures = new ArrayList<>();
                for (final NewsModels.Channel ch : channels) {
                    futures.add(pool.submit(new Callable<List<NewsModels.Story>>() {
                        @Override public List<NewsModels.Story> call() throws Exception {
                            return FeedParser.parse(Http.get(ch.url), ch);
                        }
                    }));
                }
                final List<List<NewsModels.Story>> lists = new ArrayList<>();
                for (Future<List<NewsModels.Story>> f : futures) {
                    try {
                        lists.add(f.get(30, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        // a dead or slow feed just contributes nothing
                    }
                }
                final List<NewsModels.Story> merged = FeedParser.merge(lists);
                ui.post(new Runnable() {
                    @Override public void run() {
                        fetching = false;
                        if (refreshAgain) { // the result is for an outdated channel list
                            refreshAgain = false;
                            refresh(true);
                            return;
                        }
                        if (merged.isEmpty() && !stories.isEmpty()) return; // keep what we had when offline
                        stories = merged;
                        lastFetch = System.currentTimeMillis();
                        listener.onStories(merged);
                    }
                });
            }
        });
    }

    /** Fills s.imageUrl from the story page's og:image when the feed had no picture. */
    void ensurePageImage(final NewsModels.Story s, final Runnable done) {
        if (s.imageUrl != null || s.pageImageTried || s.link == null) {
            done.run();
            return;
        }
        s.pageImageTried = true;
        pool.execute(new Runnable() {
            @Override public void run() {
                String found = null;
                try {
                    found = FeedParser.extractOgImage(Http.getPagePrefix(s.link, 400 * 1024), s.link);
                } catch (Exception ignored) {
                }
                final String url = found;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (url != null && !usedElsewhere(s, url)) s.imageUrl = url;
                        done.run();
                    }
                });
            }
        });
    }

    private boolean usedElsewhere(NewsModels.Story self, String url) {
        String key = url.replaceAll("\\?.*$", "");
        for (NewsModels.Story o : stories) {
            if (o != self && o.imageUrl != null && o.imageUrl.replaceAll("\\?.*$", "").equals(key)) return true;
        }
        return false;
    }
}

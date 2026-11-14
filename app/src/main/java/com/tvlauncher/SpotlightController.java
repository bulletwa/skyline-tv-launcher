package com.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The left card. Rotates through your most-launched app and Wikipedia's picture of the day, featured
 * article and trending articles (keyless feed). Pictures are loaded at card size through the shared cache.
 */
final class SpotlightController implements CardController {
    static final class Spot {
        String accent = "", title = "", subtitle = "", imageUrl;
        int accentColor = Color.WHITE;
        Drawable art;
        Runnable click;
    }

    private static final long CYCLE_MS = 11_000, STALE_MS = 6 * 3600_000L;

    private final Activity act;
    private final CardView card;
    private int imgW, imgH;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Pools.fixed(1, "spotlight");
    private final ImageLoader loader = ImageLoader.get();

    private Spot favourite;
    private List<Spot> wiki = new ArrayList<>();
    private int index = -1;
    private boolean started, fetching;
    private long lastFetch;
    private Spot current;

    private final Runnable cycle = new Runnable() {
        @Override public void run() {
            List<Spot> l = spots();
            if (l.size() > 1) {
                index = (index + 1) % l.size();
                show(true);
            }
            schedule();
        }
    };

    SpotlightController(Activity act, CardView card, int imgW, int imgH) {
        this.act = act;
        this.card = card;
        this.imgW = imgW;
        this.imgH = imgH;
        card.setOnClickListener(v -> {
            if (current != null && current.click != null) current.click.run();
        });
        showPlaceholder();
    }

    @Override public CardView view() { return card; }

    @Override
    public void setSize(int widthPx, int heightPx) {
        imgW = widthPx;
        imgH = heightPx;
    }

    private List<Spot> spots() {
        List<Spot> l = new ArrayList<>();
        if (favourite != null) l.add(favourite);
        l.addAll(wiki);
        return l;
    }

    @Override public String debug() {
        List<Spot> l = spots();
        return "spot " + (index < 0 || l.isEmpty() ? "none" : (index + 1) + "/" + l.size()) + " favourite=" + (favourite != null)
                + " showingFavourite=" + (current != null && current == favourite) + " wiki=" + wiki.size() + " fetching=" + fetching;
    }

    void setFavourite(Spot s) {
        boolean wasShowing = current != null && current == favourite;
        favourite = s;
        if (wasShowing || current == null) {
            index = -1;
            if (!spots().isEmpty()) {
                index = 0;
                show(current != null);
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        started = true;
        long now = System.currentTimeMillis();
        if (wiki.isEmpty() || now - lastFetch > STALE_MS) fetch();
        if (index < 0 && !spots().isEmpty()) {
            index = 0;
            show(false);
        }
        schedule();
    }

    @Override
    public void stop() {
        started = false;
        ui.removeCallbacks(cycle);
    }

    private void schedule() {
        ui.removeCallbacks(cycle);
        if (started && spots().size() > 1) ui.postDelayed(cycle, CYCLE_MS);
    }

    // ------------------------------------------------------------------ data

    private void fetch() {
        if (fetching) return;
        fetching = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                List<Spot> out = new ArrayList<>();
                for (int back = 0; back <= 1 && out.isEmpty(); back++) {
                    try {
                        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH);
                        f.setTimeZone(TimeZone.getTimeZone("UTC"));
                        String day = f.format(new Date(System.currentTimeMillis() - back * 86400_000L));
                        String json = Http.get("https://en.wikipedia.org/api/rest_v1/feed/featured/" + day);
                        for (WikiParse.Item it : WikiParse.parse(json)) out.add(spotFor(it));
                    } catch (Exception ignored) {
                    }
                }
                final List<Spot> result = out;
                ui.post(new Runnable() {
                    @Override public void run() {
                        fetching = false;
                        if (result.isEmpty()) return;
                        wiki = result;
                        lastFetch = System.currentTimeMillis();
                        if (index < 0 || current == null || !spots().contains(current)) {
                            index = 0;
                            show(current != null);
                        }
                        schedule();
                    }
                });
            }
        });
    }

    private Spot spotFor(final WikiParse.Item it) {
        Spot s = new Spot();
        s.accent = it.kind;
        s.accentColor = it.kind.startsWith("PICTURE") ? Color.rgb(255, 206, 110)
                : it.kind.startsWith("FEATURED") ? Color.rgb(160, 200, 255) : Color.rgb(255, 160, 160);
        s.title = it.title;
        s.subtitle = it.subtitle;
        s.imageUrl = it.imageUrl;
        s.click = new Runnable() {
            @Override public void run() {
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.pageUrl)));
                } catch (Exception e) {
                    Toast.makeText(act, "No browser to open that", Toast.LENGTH_SHORT).show();
                }
            }
        };
        return s;
    }

    // ------------------------------------------------------------------ showing

    private void showPlaceholder() {
        card.setAccent("SPOTLIGHT", 0, Color.argb(200, 255, 255, 255));
        card.setTitle("Discovering…");
        card.setSubtitle("");
        card.setImage(new ColorDrawable(Color.rgb(24, 30, 44)), false);
    }

    private void show(final boolean animate) {
        List<Spot> l = spots();
        if (index < 0 || index >= l.size()) return;
        final Spot s = l.get(index);
        current = s;
        final int n = l.size(), sel = index;
        card.swapText(new Runnable() {
            @Override public void run() {
                card.setAccent(s.accent, 0, s.accentColor);
                card.setTitle(s.title);
                card.setSubtitle(s.subtitle);
                card.setFooter(Math.min(n, 8), sel % 8, "Press Center to open");
            }
        }, animate);
        if (s.art != null) {
            card.setImage(s.art, animate);
        } else if (s.imageUrl != null) {
            Bitmap hit = loader.peek(s.imageUrl, imgW);
            if (hit != null) {
                card.setImage(new BitmapDrawable(act.getResources(), hit), animate);
            } else {
                if (!animate) card.setImage(new ColorDrawable(Color.rgb(24, 30, 44)), false);
                loader.load(s.imageUrl, null, imgW, imgH, new ImageLoader.Callback() {
                    @Override public void onImage(Bitmap b) {
                        if (b != null && current == s) card.setImage(new BitmapDrawable(act.getResources(), b), true);
                    }
                });
            }
        }
        // warm the next picture so the crossfade is instant
        if (l.size() > 1) {
            Spot next = l.get((index + 1) % l.size());
            if (next.imageUrl != null) loader.preload(next.imageUrl, null, imgW, imgH);
        }
    }
}

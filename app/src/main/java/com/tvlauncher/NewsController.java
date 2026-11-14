package com.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** The right card: cycles stories every ~8 s with a crossfade; Center opens the glass reader. */
final class NewsController implements NewsRepo.Listener, CardController {
    private static final long CYCLE_MS = 8_000;
    private static final int RED = Color.rgb(230, 40, 40);

    private final Activity act;
    private final CardView card;
    private final NewsRepo repo;
    private int imgW, imgH;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageLoader loader = ImageLoader.get();
    private final float d;

    private List<NewsModels.Story> list = new java.util.ArrayList<>();
    private int index;
    private boolean started;
    private NewsModels.Story current;

    private final Runnable cycle = new Runnable() {
        @Override public void run() {
            if (list.size() > 1) {
                index = (index + 1) % list.size();
                show(true);
            }
            schedule();
        }
    };

    NewsController(Activity act, CardView card, Prefs prefs, int imgW, int imgH) {
        this.act = act;
        this.card = card;
        this.imgW = imgW;
        this.imgH = imgH;
        d = act.getResources().getDisplayMetrics().density;
        repo = new NewsRepo(prefs, this);
        card.setOnClickListener(v -> openReader());
        card.setAccent("NEWS", RED, Color.argb(220, 255, 255, 255));
        card.setTitle("Loading headlines…");
        card.setSubtitle("");
    }

    NewsRepo repo() {
        return repo;
    }

    @Override public CardView view() { return card; }

    @Override
    public void setSize(int widthPx, int heightPx) {
        imgW = widthPx;
        imgH = heightPx;
    }

    @Override
    public void start() {
        started = true;
        repo.refresh(false);
        schedule();
    }

    @Override
    public void stop() {
        started = false;
        ui.removeCallbacks(cycle);
    }

    /** Followed channels changed in the News Channels dialog. */
    void channelsChanged() {
        repo.refresh(true);
    }

    @Override
    public void onStories(List<NewsModels.Story> stories) {
        list = stories;
        index = 0;
        show(current != null);
        schedule();
    }

    @Override public String debug() {
        return "story " + (list.isEmpty() ? "0/0 (none loaded)" : (index + 1) + "/" + list.size())
                + (current == null ? "" : " channel=" + current.channel.name) + " cycling=" + (started && list.size() > 1);
    }

    private void schedule() {
        ui.removeCallbacks(cycle);
        if (started && list.size() > 1) ui.postDelayed(cycle, CYCLE_MS);
    }

    // ------------------------------------------------------------------ card

    private Drawable tile(NewsModels.Channel ch) {
        return new TileDrawable(act, ch.color, ch.initials());
    }

    private void show(boolean animate) {
        if (list.isEmpty()) return;
        final NewsModels.Story s = list.get(index);
        current = s;
        final int n = list.size(), sel = index;
        card.swapText(new Runnable() {
            @Override public void run() {
                String ago = TextUtil.ago(s.publishedMs, System.currentTimeMillis());
                card.setAccent(s.channel.name.toUpperCase(Locale.ENGLISH) + (ago.isEmpty() ? "" : "  •  " + ago.toUpperCase(Locale.ENGLISH)),
                        RED, Color.argb(225, 255, 255, 255));
                card.setTitle(s.title);
                card.setSubtitle("");
                card.setFooter(Math.min(n, 8), sel % 8, "Press Center to read");
            }
        }, animate);
        showPicture(s, animate);
        // preload the next story's picture so the crossfade has it ready
        if (n > 1) prepare(list.get((index + 1) % n));
    }

    /** Shows the cached picture, or the channel tile now and the real picture as soon as it arrives. */
    private void showPicture(final NewsModels.Story s, boolean animate) {
        Bitmap hit = s.imageUrl == null ? null : loader.peek(s.imageUrl, imgW);
        if (hit != null) {
            card.setImage(new BitmapDrawable(act.getResources(), hit), animate);
            return;
        }
        card.setImage(tile(s.channel), animate);
        fetchPicture(s, new Runnable() {
            @Override public void run() {
                if (current != s || s.imageUrl == null) return;
                Bitmap b = loader.peek(s.imageUrl, imgW);
                if (b != null) card.setImage(new BitmapDrawable(act.getResources(), b), true);
            }
        });
    }

    private void fetchPicture(final NewsModels.Story s, final Runnable whenReady) {
        repo.ensurePageImage(s, new Runnable() {
            @Override public void run() {
                if (s.imageUrl == null) return;
                loader.load(s.imageUrl, s.link, imgW, imgH, new ImageLoader.Callback() {
                    @Override public void onImage(Bitmap b) {
                        if (b != null) whenReady.run();
                    }
                });
            }
        });
    }

    private void prepare(NewsModels.Story s) {
        if (s.imageUrl != null) loader.preload(s.imageUrl, s.link, imgW, imgH);
        else fetchPicture(s, new Runnable() {
            @Override public void run() {}
        });
    }

    // ------------------------------------------------------------------ reader

    private void openReader() {
        if (list.isEmpty()) {
            repo.refresh(true);
            Toast.makeText(act, "Loading headlines…", Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] i = {index};
        final GlassDialog dlg = new GlassDialog(act, 720);
        final ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * d);
            }
        });
        img.setClipToOutline(true);
        final TextView meta = GlassDialog.text(act, 11, 500, Color.argb(150, 255, 255, 255));
        meta.setLetterSpacing(0.2f);
        final TextView title = GlassDialog.text(act, 24, 400, Color.WHITE);
        final TextView summary = GlassDialog.text(act, 15, 300, Color.argb(190, 255, 255, 255));
        final TextView count = GlassDialog.text(act, 12, 300, Color.argb(130, 255, 255, 255));
        LinearLayout body = dlg.body();
        int m = Math.round(8 * d);
        body.addView(img, new LinearLayout.LayoutParams(-1, Math.round(190 * d)));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.setMargins(m, Math.round(14 * d), m, 0);
        body.addView(meta, mlp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.setMargins(m, Math.round(6 * d), m, 0);
        body.addView(title, tlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(m, Math.round(10 * d), m, 0);
        body.addView(summary, slp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(m, Math.round(12 * d), m, 0);
        body.addView(count, clp);

        final Runnable render = new Runnable() {
            @Override public void run() {
                final NewsModels.Story s = list.get(i[0]);
                String ago = TextUtil.ago(s.publishedMs, System.currentTimeMillis());
                meta.setText((s.channel.name + (ago.isEmpty() ? "" : "  •  " + ago)).toUpperCase(Locale.ENGLISH));
                title.setText(s.title);
                summary.setText(s.summary.isEmpty() ? "No summary in this feed. Open the story for the full text." : s.summary);
                count.setText((i[0] + 1) + " of " + list.size());
                Bitmap hit = s.imageUrl == null ? null : loader.peek(s.imageUrl, imgW);
                if (hit != null) {
                    img.setImageDrawable(new BitmapDrawable(act.getResources(), hit));
                } else {
                    img.setImageDrawable(tile(s.channel));
                    fetchPicture(s, new Runnable() {
                        @Override public void run() {
                            Bitmap b = s.imageUrl == null ? null : loader.peek(s.imageUrl, imgW);
                            if (b != null && list.get(i[0]) == s && dlg.isShowing()) {
                                img.setImageDrawable(new BitmapDrawable(act.getResources(), b));
                            }
                        }
                    });
                }
            }
        };
        dlg.button("Previous", new Runnable() {
            @Override public void run() {
                i[0] = (i[0] - 1 + list.size()) % list.size();
                render.run();
            }
        }, true);
        dlg.button("Next", new Runnable() {
            @Override public void run() {
                i[0] = (i[0] + 1) % list.size();
                render.run();
            }
        }, true);
        dlg.button("Open story", new Runnable() {
            @Override public void run() {
                String link = list.get(i[0]).link;
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                } catch (Exception e) {
                    Toast.makeText(act, "No browser to open that", Toast.LENGTH_SHORT).show();
                }
            }
        }, false);
        dlg.button("Close", null, false);
        render.run();
        dlg.show();
    }
}

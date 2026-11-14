package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** News Channels: Following / Discover tabs, search, category chips, and your own feed URL. */
final class NewsChannelsDialog {
    private final Activity act;
    private final NewsRepo repo;
    private final Runnable onChanged;
    private final float d;
    private final GlassDialog dlg;
    private final Set<String> following = new HashSet<>();
    private final Set<String> initial = new HashSet<>();

    private boolean discover = false;
    private String category = "All";
    private String query = "";

    private LinearLayout tabs, cats, list;
    private TextView followingTab, discoverTab;

    NewsChannelsDialog(Activity act, NewsRepo repo, Runnable onChanged) {
        this.act = act;
        this.repo = repo;
        this.onChanged = onChanged;
        d = act.getResources().getDisplayMetrics().density;
        following.addAll(repo.followedIds());
        initial.addAll(following);
        dlg = new GlassDialog(act, 700).title("News Channels");
        build();
    }

    GlassDialog dialog() {
        return dlg;
    }

    void show() {
        dlg.onClosed(() -> {
            if (!following.equals(initial)) {
                java.util.List<String> ordered = new java.util.ArrayList<>();
                // keep the user's existing order, append new ones
                for (String id : repo.followedIds()) if (following.contains(id)) ordered.add(id);
                for (String id : following) if (!ordered.contains(id)) ordered.add(id);
                repo.setFollowed(ordered);
                onChanged.run();
            }
        });
        dlg.show();
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private void build() {
        LinearLayout body = dlg.body();

        tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = dp(10);
        body.addView(tabs, tlp);

        EditText search = GlassDialog.makeInput(act, "Search channels");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                renderList();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = dp(10);
        body.addView(search, slp);

        HorizontalScrollView catScroll = new HorizontalScrollView(act);
        catScroll.setHorizontalScrollBarEnabled(false);
        cats = new LinearLayout(act);
        cats.setOrientation(LinearLayout.HORIZONTAL);
        catScroll.addView(cats);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(8);
        body.addView(catScroll, clp);

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, new LinearLayout.LayoutParams(-1, -2));

        dlg.button("Done", null, false);
        renderTabs();
        renderCategories();
        renderList();
    }

    private void renderTabs() {
        tabs.removeAllViews();
        followingTab = chip("Following", !discover, () -> { discover = false; renderTabs(); renderList(); });
        discoverTab = chip("Discover", discover, () -> { discover = true; renderTabs(); renderList(); });
        for (TextView t : new TextView[]{followingTab, discoverTab}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.rightMargin = dp(8);
            tabs.addView(t, lp);
        }
        followingTab.requestFocus();
    }

    private void renderCategories() {
        cats.removeAllViews();
        List<String> names = new java.util.ArrayList<>();
        names.add("All");
        names.addAll(java.util.Arrays.asList(Catalog.CATEGORIES));
        for (final String name : names) {
            TextView c = chip(name, name.equals(category), () -> { category = name; renderCategories(); renderList(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.rightMargin = dp(8);
            cats.addView(c, lp);
        }
    }

    private TextView chip(String label, boolean selected, final Runnable action) {
        TextView c = GlassDialog.makeChip(act, label, selected);
        c.setOnClickListener(v -> action.run());
        return c;
    }

    private void renderList() {
        list.removeAllViews();
        List<NewsModels.Channel> shown = Catalog.filter(repo.available(), category, query, following, !discover);
        if (shown.isEmpty()) {
            TextView t = GlassDialog.text(act, 14, 300, Color.argb(140, 255, 255, 255));
            t.setText(discover ? "No channels match." : "You are not following any channel here yet. Open Discover to add some.");
            t.setPadding(dp(8), dp(10), dp(8), dp(10));
            list.addView(t);
        }
        for (final NewsModels.Channel ch : shown) {
            final View row = GlassDialog.makeRow(act, ch.name, ch.category + (ch.country != null ? " · " + ch.country : ""),
                    following.contains(ch.id) ? "Following" : "Follow");
            row.setOnClickListener(v -> {
                if (!following.remove(ch.id)) following.add(ch.id);
                if (!discover && !following.contains(ch.id)) {
                    renderList(); // unfollowed on the Following tab: it leaves the list
                } else {
                    GlassDialog.setRowValue(row, following.contains(ch.id) ? "Following" : "Follow");
                }
            });
            if (ch.category.equals("Custom")) {
                row.setOnLongClickListener(v -> {
                    repo.removeCustom(ch.id);
                    following.remove(ch.id);
                    initial.remove(ch.id);
                    onChanged.run();
                    renderList();
                    Toast.makeText(act, "Removed " + ch.name, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (discover) {
            View add = GlassDialog.makeRow(act, "Add a feed by address", "Paste an RSS or Atom URL", null);
            add.setOnClickListener(v -> askForFeed());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            list.addView(add, lp);
        }
    }

    // ------------------------------------------------------------------ custom feed

    private void askForFeed() {
        final GlassDialog ask = new GlassDialog(act, 620).title("Add a feed");
        final EditText url = GlassDialog.makeInput(act, "https://example.com/feed.xml");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        ask.message("Type or paste the address of an RSS or Atom feed. It is checked before it is added.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(8), dp(6), dp(8), 0);
        ask.body().addView(url, lp);
        ask.button("Cancel", null, false);
        ask.button("Add", () -> addFeed(Catalog.normalizeUrl(url.getText().toString())), false);
        ask.show();
        url.requestFocus();
    }

    private void addFeed(final String url) {
        if (url.isEmpty()) return;
        Toast.makeText(act, "Checking feed…", Toast.LENGTH_SHORT).show();
        final NewsModels.Channel probe = Catalog.custom(Catalog.nameFromUrl(url), url);
        new Thread(() -> {
            boolean ok;
            try {
                ok = !FeedParser.parse(Http.get(url), probe).isEmpty();
            } catch (Exception e) {
                ok = false;
            }
            final boolean good = ok;
            act.runOnUiThread(() -> {
                if (!good) {
                    Toast.makeText(act, "Couldn't read a feed at that address", Toast.LENGTH_LONG).show();
                    return;
                }
                repo.addCustom(probe.name, url);
                following.add(probe.id);
                initial.remove(probe.id); // addCustom already followed it; make dismiss detect the change
                renderList();
                onChanged.run();
                Toast.makeText(act, "Added " + probe.name, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
}

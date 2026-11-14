package com.tvlauncher;

import android.app.Activity;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the card row: lays out the cards that are switched on (up to three, or none for a clean home screen),
 * and starts and stops each card's work along with its visibility.
 */
final class HomeCards {
    private static final int HEIGHT_DP = 220, GAP_DP = 26;

    private final Activity act;
    private final Prefs prefs;
    private final LinearLayout row;
    private final int pagePadPx;
    private final float d;
    private final Map<String, CardController> byId = new LinkedHashMap<>();
    private final List<CardView> visible = new ArrayList<>();
    private boolean started;

    HomeCards(Activity act, Prefs prefs, LinearLayout row, int pagePadPx) {
        this.act = act;
        this.prefs = prefs;
        this.row = row;
        this.pagePadPx = pagePadPx;
        d = act.getResources().getDisplayMetrics().density;
    }

    /** Register a card; the id must be one of {@link CardsConfig#ALL}. */
    void register(String id, CardController c) {
        byId.put(id, c);
    }

    /** The ids on screen right now (empty when the cards are switched off). */
    List<String> active() {
        return prefs.cardsShown() ? prefs.cardsEnabled() : new ArrayList<String>();
    }

    /** The card views on screen, left to right, for D-pad navigation. */
    List<CardView> visibleViews() {
        return visible;
    }

    boolean any() {
        return !visible.isEmpty();
    }

    /** The row that holds the cards, so clean mode can fade it out as a whole. */
    android.view.View rowView() {
        return row;
    }

    /** (Re)builds the row from the saved settings. */
    void apply() {
        List<String> ids = active();
        row.removeAllViews();
        visible.clear();
        row.setVisibility(ids.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        int n = Math.max(1, ids.size());
        int gap = Math.round(GAP_DP * d), h = Math.round(HEIGHT_DP * d);
        int w = (act.getResources().getDisplayMetrics().widthPixels - 2 * pagePadPx - (n - 1) * gap) / n;
        for (Map.Entry<String, CardController> e : byId.entrySet()) {
            CardController c = e.getValue();
            if (!ids.contains(e.getKey())) {
                c.stop();
                continue;
            }
            c.setSize(w, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (!visible.isEmpty()) lp.leftMargin = gap;
            row.addView(c.view(), lp);
            visible.add(c.view());
            if (started) c.start();
        }
    }

    /** Debug dump: one entry per visible card with its id, view state and its controller's own state. */
    String debug() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, CardController> e : byId.entrySet()) {
            if (!visible.contains(e.getValue().view())) continue;
            b.append("\n  card ").append(e.getKey()).append(": ").append(e.getValue().view().debug());
            String s = e.getValue().debug();
            if (!s.isEmpty()) b.append(" | ").append(s);
        }
        return b.toString();
    }

    void start() {
        started = true;
        for (Map.Entry<String, CardController> e : byId.entrySet()) {
            if (visible.contains(e.getValue().view())) e.getValue().start();
        }
    }

    void stop() {
        started = false;
        for (CardController c : byId.values()) c.stop();
    }

    CardController get(String id) {
        return byId.get(id);
    }
}

package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.List;

/** Search for a city (Open-Meteo geocoding) or go back to the automatic, IP-based guess. */
final class WeatherLocationDialog {
    interface Callback {
        /** @param place the chosen place, or null for "automatic". */
        void onChosen(WeatherParse.Place place);
    }

    private final Activity act;
    private final Prefs prefs;
    private final Callback callback;
    private final GlassDialog dlg;
    private final EditText input;
    private final LinearLayout results;
    private final float d;
    private int searchId;

    WeatherLocationDialog(Activity act, Prefs prefs, Callback cb) {
        this.act = act;
        this.prefs = prefs;
        this.callback = cb;
        d = act.getResources().getDisplayMetrics().density;
        dlg = new GlassDialog(act, 640).title("Weather Location");
        dlg.message("Currently: " + current());

        input = GlassDialog.makeInput(act, "Search for a city");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, action, ev) -> {
            search();
            return true;
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(dp(8), dp(4), dp(8), dp(8));
        dlg.body().addView(input, ilp);

        results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        dlg.body().addView(results, new LinearLayout.LayoutParams(-1, -2));

        dlg.button("Use automatic", () -> callback.onChosen(null), false);
        dlg.button("Search", this::search, true);
        dlg.button("Close", null, false);
    }

    GlassDialog dialog() {
        return dlg;
    }

    void show() {
        dlg.show();
        input.requestFocus();
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private String current() {
        String loc = prefs.str("loc", null);
        String name = "detecting…";
        if (loc != null) {
            String[] p = loc.split("\\|");
            if (p.length > 2) name = p[2];
        }
        return name + (prefs.str("loc_manual", "").equals("1") ? "" : "  (automatic, from your internet connection)");
    }

    private void note(String s) {
        results.removeAllViews();
        TextView t = GlassDialog.text(act, 14, 300, Color.argb(140, 255, 255, 255));
        t.setText(s);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        results.addView(t);
    }

    private void search() {
        final String q = input.getText().toString().trim();
        if (q.length() < 2) {
            note("Type at least two letters.");
            return;
        }
        final int id = ++searchId;
        note("Searching…");
        new Thread(() -> {
            List<WeatherParse.Place> found = null;
            try {
                found = WeatherParse.parseGeocoding(Http.get("https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(q, "UTF-8") + "&count=8&language=en&format=json"));
            } catch (Exception ignored) {
            }
            final List<WeatherParse.Place> list = found;
            act.runOnUiThread(() -> {
                if (id != searchId || !dlg.isShowing()) return;
                if (list == null) note("Couldn't reach the search service. Check your connection.");
                else if (list.isEmpty()) note("No places found for “" + q + "”.");
                else showResults(list);
            });
        }).start();
    }

    private void showResults(List<WeatherParse.Place> list) {
        results.removeAllViews();
        for (final WeatherParse.Place p : list) {
            View row = GlassDialog.makeRow(act, p.name,
                    String.format(java.util.Locale.ENGLISH, "%.2f, %.2f", p.lat, p.lon), null);
            row.setOnClickListener(v -> {
                dlg.dismiss();
                callback.onChosen(p);
            });
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        results.getChildAt(0).requestFocus();
    }
}

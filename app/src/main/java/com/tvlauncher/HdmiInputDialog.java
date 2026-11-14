package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Settings → PlayStation input: pick which HDMI input is the console, or go back to detecting it by name. */
final class HdmiInputDialog {
    private final Activity act;
    private final Prefs prefs;
    private final GlassDialog dlg;
    private final LinearLayout list;
    private final Ps5 ps5;
    private final Runnable onChanged;
    private final float d;

    HdmiInputDialog(Activity act, Prefs prefs, Ps5 ps5, Runnable onChanged) {
        this.act = act;
        this.prefs = prefs;
        this.ps5 = ps5;
        this.onChanged = onChanged;
        d = act.getResources().getDisplayMetrics().density;
        dlg = new GlassDialog(act, 620).title("PlayStation input");
        dlg.message("Choose the HDMI input your PlayStation is plugged into. The card and the dock tile use it.");
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        dlg.body().addView(list, new LinearLayout.LayoutParams(-1, -2));
        dlg.button("Done", null, false);
        render();
    }

    GlassDialog dialog() {
        return dlg;
    }

    private void render() {
        list.removeAllViews();
        String saved = prefs.str("ps5_input", "");
        Ps5.Info found = ps5.find();

        final View auto = GlassDialog.makeRow(act, "Automatic",
                "Finds an input named PlayStation" + (saved.isEmpty() && found != null ? "  (now: " + found.name + ")" : ""),
                saved.isEmpty() ? "Selected" : "");
        auto.setOnClickListener(v -> choose(""));
        list.addView(auto, new LinearLayout.LayoutParams(-1, -2));

        List<HdmiPick.Row> rows = ps5.inputs();
        for (final HdmiPick.Row r : rows) {
            String port = HdmiPick.portText(r.id);
            String sub = (port.isEmpty() ? "" : port + "  •  ") + HdmiPick.statusWord(r.effectiveState());
            View row = GlassDialog.makeRow(act, r.label.isEmpty() ? r.id : r.label, sub, r.id.equals(saved) ? "Selected" : "");
            row.setOnClickListener(v -> choose(r.id));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (rows.isEmpty()) {
            TextView t = GlassDialog.text(act, 14, 300, Color.argb(140, 255, 255, 255));
            t.setText("This TV did not list any HDMI inputs.");
            t.setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
            list.addView(t);
        }
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private void choose(String inputId) {
        if (inputId.isEmpty()) prefs.remove("ps5_input"); else prefs.putStr("ps5_input", inputId);
        onChanged.run();
        render();
    }
}

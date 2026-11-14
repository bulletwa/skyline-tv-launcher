package com.tvlauncher;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Settings → Cards: hide them all for a clean home screen, or choose which (up to three) to show. */
final class CardsDialog {
    private final Activity act;
    private final Prefs prefs;
    private final GlassDialog dlg;
    private List<String> enabled;

    CardsDialog(Activity act, Prefs prefs, final Runnable onChanged) {
        this.act = act;
        this.prefs = prefs;
        enabled = new ArrayList<>(prefs.cardsEnabled());
        dlg = new GlassDialog(act, 640).title("Cards");

        dlg.section("Home screen");
        final View master = dlg.row("Show cards", "Turn off for a clean home screen with just the clock and dock",
                onOff(prefs.cardsShown()), null, true);
        master.setOnClickListener(v -> {
            boolean on = !CardsDialog.this.prefs.cardsShown();
            CardsDialog.this.prefs.setCardsShown(on);
            GlassDialog.setRowValue(master, onOff(on));
        });

        dlg.section("Choose up to " + CardsConfig.MAX + " cards");
        for (final String id : CardsConfig.ALL) {
            final View row = dlg.row(CardsConfig.label(id), CardsConfig.description(id),
                    onOff(enabled.contains(id)), null, true);
            row.setOnClickListener(v -> {
                boolean turnOn = !enabled.contains(id);
                if (turnOn && CardsConfig.full(enabled, id)) {
                    Toast.makeText(CardsDialog.this.act, "Up to " + CardsConfig.MAX + " cards at once. Turn one off first.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                enabled = CardsConfig.set(enabled, id, turnOn);
                CardsDialog.this.prefs.setCardsEnabled(enabled);
                GlassDialog.setRowValue(row, onOff(turnOn));
                if (turnOn && !CardsDialog.this.prefs.cardsShown()) { // choosing a card implies wanting to see it
                    CardsDialog.this.prefs.setCardsShown(true);
                    GlassDialog.setRowValue(master, onOff(true));
                }
            });
        }

        dlg.section("Clean mode");
        final View clean = dlg.row("Scroll up to hide the apps",
                "Press Up at the top (the Wi-Fi and settings buttons) to hide the cards and apps. With no cards, Up from the dock does it. Down brings everything back.",
                onOff(prefs.cleanOnUp()), null, true);
        clean.setOnClickListener(v -> {
            boolean on = !CardsDialog.this.prefs.cleanOnUp();
            CardsDialog.this.prefs.setCleanOnUp(on);
            GlassDialog.setRowValue(clean, onOff(on));
        });

        dlg.button("Done", null, false);
        dlg.onClosed(onChanged);
    }

    private static String onOff(boolean on) {
        return on ? "On" : "Off";
    }

    GlassDialog dialog() {
        return dlg;
    }
}

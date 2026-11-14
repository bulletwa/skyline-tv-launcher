package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Toast;

import java.util.Locale;

/**
 * PlayStation card: whether the console is on, and Center jumps to its HDMI input. Event driven: it listens for
 * the TV's input state changes, so it costs nothing while idle.
 */
final class Ps5CardController implements CardController {
    private final Activity act;
    private final CardView card;
    private final Ps5 ps5;
    private final Runnable chooser; // opens the "which HDMI input" dialog
    private Ps5.Info info;
    private boolean started;

    Ps5CardController(Activity act, CardView card, Ps5 ps5, Runnable chooser) {
        this.act = act;
        this.card = card;
        this.ps5 = ps5;
        this.chooser = chooser;
        card.setTitleStyle(26, 300, 2);
        card.setOnClickListener(v -> onClick());
        render();
    }

    @Override public CardView view() { return card; }

    @Override public void setSize(int widthPx, int heightPx) {}

    @Override
    public void start() {
        started = true;
        ps5.listen(this::render);
        render();
    }

    @Override
    public void stop() {
        started = false;
        ps5.stopListening();
    }

    @Override public String debug() {
        return info == null ? "not set up" : "state=" + info.state + " port=" + info.port();
    }

    private static GradientDrawable art(int top, int bottom) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF000000 | top, 0xFF000000 | bottom});
    }

    /** Re-reads the input state and redraws the card. */
    void render() {
        info = ps5.find();
        if (info == null) {
            card.setAccent("PLAYSTATION 5", 0, Color.argb(220, 255, 255, 255));
            card.setTitle("Set up your PlayStation");
            card.setSubtitle("Choose which HDMI input it is plugged into");
            card.setFooter(0, 0, "Press Center to choose");
            card.setImage(art(0x1B2A55, 0x0A1024), false);
            return;
        }
        String port = info.port();
        int dot = info.state == HdmiPick.CONNECTED ? Color.rgb(60, 200, 120)
                : info.state == HdmiPick.STANDBY ? Color.rgb(240, 170, 60) : 0;
        card.setAccent((info.name + (port.isEmpty() ? "" : "  •  " + port)).toUpperCase(Locale.ENGLISH), dot,
                Color.argb(225, 255, 255, 255));
        switch (info.state) {
            case HdmiPick.CONNECTED:
                card.setTitle("Switch to your PlayStation");
                card.setSubtitle("It is on");
                card.setFooter(0, 0, "Press Center to switch");
                card.setImage(art(0x2D63D6, 0x0B1A45), false);
                break;
            case HdmiPick.STANDBY:
                card.setTitle("PlayStation in standby");
                card.setSubtitle("Turn it on, or switch anyway");
                card.setFooter(0, 0, "Press Center to switch");
                card.setImage(art(0x1E3A7A, 0x0A1430), false);
                break;
            default:
                card.setTitle("PlayStation not connected");
                card.setSubtitle("Check the HDMI cable");
                card.setFooter(0, 0, "Press Center to choose input");
                card.setImage(art(0x2A2F3C, 0x0C0E14), false);
        }
    }

    private void onClick() {
        // Nothing to switch to (not found, or nothing plugged in): let the user pick the right input instead.
        if (info == null || info.state == HdmiPick.DISCONNECTED) {
            chooser.run();
            return;
        }
        if (!ps5.switchTo(act, info)) {
            Toast.makeText(act, "Couldn't switch input. Try the TV's own input list.", Toast.LENGTH_LONG).show();
        }
    }
}

package com.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Editing the dock from the home screen: hold OK on an app for a small menu (Open app, Move, Remove); the held app
 * swells and jiggles, and Move slides it along the dock with Left/Right.
 */
final class DockEditor {
    private final MainActivity a;

    DockEditor(MainActivity a) {
        this.a = a;
    }

    /** Press and hold OK on a dock app: it swells and jiggles, and a small menu opens above-left of it. */
    void showDockMenu(final AppEntry e) {
        View f = a.getCurrentFocus();
        if (!(f instanceof TileView) || !a.dockTiles.contains(f) || GlassDialog.top != null) return; // one menu only
        liftOn((TileView) f);
        int[] xy = new int[2];
        f.getLocationOnScreen(xy);
        GlassDialog dlg = new GlassDialog(a, 140).anchoredAt(xy[0], xy[1]);
        dlg.compactRow("Open app", () -> a.launch(e));
        dlg.compactRow("Move", () -> startMove());
        dlg.compactRow("Remove", () -> {
            List<String> dock = new ArrayList<>(a.currentDockPkgs());
            dock.remove(e.pkg);
            a.prefs.setDock(dock);
            a.rebuildLists();
        });
        dlg.onClosed(() -> a.ui.post(() -> { if (!moving) liftOff(); })); // Move keeps it held
        dlg.show();
    }

    static final long HOLD_MS = 300;
    final Runnable holdOpen = this::openHeldMenu;

    void openHeldMenu() {
        View f = a.getCurrentFocus();
        if (moving || GlassDialog.top != null || !(f instanceof TileView) || !a.dockTiles.contains(f)) return;
        swallowCenterUp = true; // the release that follows must not open the app
        f.setPressed(false);
        showDockMenu((AppEntry) f.getTag());
    }

    TileView held;

    void liftOn(TileView t) {
        held = t;
        a.carousel.setLifted(t);
        t.startJiggle();
    }

    void liftOff() {
        if (held == null) return;
        held.stopJiggle();
        a.carousel.setLifted(null);
        held = null;
    }

    boolean moving, swallowCenterUp;
    List<String> moveOrder;

    void startMove() {
        if (!(a.getCurrentFocus() instanceof TileView) || !a.dockTiles.contains(a.getCurrentFocus())) return;
        moving = true;
        moveOrder = new ArrayList<>();
        for (AppEntry a : a.dockApps) moveOrder.add(a.pkg);
        android.widget.Toast.makeText(a, "Left / Right to move, OK to drop, Back to cancel", android.widget.Toast.LENGTH_LONG).show();
    }

    void moveStep(int dir) {
        View f = a.getCurrentFocus();
        int i = a.dockTiles.indexOf(f), j = i + dir;
        if (i < 0 || j < 0 || j >= a.dockTiles.size()) return;
        java.util.Collections.swap(a.dockTiles, i, j);
        java.util.Collections.swap(a.dockApps, i, j);
        a.carousel.swapAnimated(i, j);
    }

    void endMove(boolean keep) {
        moving = false;
        liftOff();
        if (keep) {
            List<String> dock = new ArrayList<>();
            for (AppEntry a : a.dockApps) dock.add(a.pkg);
            a.prefs.setDock(dock);
            return;
        }
        View f = a.getCurrentFocus();
        String pkg = f != null && f.getTag() instanceof AppEntry ? ((AppEntry) f.getTag()).pkg : null;
        a.rebuildLists(); // back to the saved order
        for (TileView t : a.dockTiles) if (pkg != null && pkg.equals(((AppEntry) t.getTag()).pkg)) t.requestFocus();
    }
}

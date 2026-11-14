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

/** Debug-only helpers: the screen-state dump (`am broadcast -a com.tvlauncher.DUMP`, see tools/tv.sh) and a fake music session. */
final class DebugTools {
    private final MainActivity a;

    DebugTools(MainActivity a) {
        this.a = a;
    }

    private android.media.session.MediaSession fakeSession;

    /** Debug only: a media session that looks like a music app, to exercise the Now Playing card end to end. */
    void debugFakePlay(String spec) {
        if (fakeSession != null) {
            fakeSession.release();
            fakeSession = null;
        }
        if (spec == null || spec.equals("off")) return;
        String[] p = (spec + "||").split("\\|", -1); // always at least title and artist, even if either is missing
        Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(art);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 300, 300, 0xFFFF4D6D, 0xFF3A1C71, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 300, 300, paint);
        paint.setShader(null);
        paint.setColor(0x55FFFFFF);
        c.drawCircle(150, 150, 90, paint);
        paint.setColor(0xCCFFFFFF);
        paint.setTextSize(120);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText("♪", 150, 190, paint);
        final android.media.session.MediaSession s = new android.media.session.MediaSession(a, "debug-fake");
        s.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, p[0].isEmpty() ? "Midnight City" : p[0])
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, p[1].isEmpty() ? "M83" : p[1])
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Hurry Up, We're Dreaming")
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
        s.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() { setFakeState(s, true); }
            @Override public void onPause() { setFakeState(s, false); }
        });
        setFakeState(s, true);
        s.setActive(true);
        fakeSession = s;
    }

    static void setFakeState(android.media.session.MediaSession s, boolean playing) {
        s.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setState(playing ? android.media.session.PlaybackState.STATE_PLAYING : android.media.session.PlaybackState.STATE_PAUSED, 0, 1f)
                .setActions(android.media.session.PlaybackState.ACTION_PLAY | android.media.session.PlaybackState.ACTION_PAUSE
                        | android.media.session.PlaybackState.ACTION_PLAY_PAUSE).build());
    }

    /** Debug: `adb shell am broadcast -a com.tvlauncher.DUMP --es id N` logs the whole screen state as one text line. */
    final BroadcastReceiver dumpReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.hasExtra("perf") && HomeWatcherService.instance != null) // debug: --es perf on|off, same as the setting
                HomeWatcherService.instance.setPerfOverlay("on".equals(i.getStringExtra("perf")));
            if (i.hasExtra("style")) a.sky.setStyle(i.getStringExtra("style")); // debug: --es style classic|vangogh
            for (String line : dumpState().split("\n")) Log.i("State", i.getStringExtra("id") + " " + line); // logcat splits lines
            if (i.hasExtra("tree")) for (String line : tree().split("\n")) Log.i("State", i.getStringExtra("id") + " #T#" + line);
            if (i.hasExtra("frame")) dumpFrame(); // debug: the sky surface's own pixels as a PNG, to compare with a screenshot
        }
    };

    void dumpFrame() {
        android.view.SurfaceView sv = a.sky.surface();
        if (sv == null) return;
        final Bitmap b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        android.view.PixelCopy.request(sv, b, r -> {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null), "frame.png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
                Log.i("State", "frame written " + r);
            } catch (java.io.IOException e) {
                Log.w("State", "frame failed", e);
            }
        }, a.ui);
    }

    String dumpState() {
        StringBuilder sb = new StringBuilder();
        GlassDialog dlg = GlassDialog.top;
        sb.append("focus=").append(dlg != null ? "dialog" : describe(a.getCurrentFocus()));
        sb.append(" dialogs=").append(GlassDialog.open).append(" mode=").append(a.clean ? "clean" : a.edit.moving ? "moving" : "home");
        if (dlg != null) sb.append(" dialog=").append(dlg.describe());
        if (dlg != null && a.edit.held != null) { // geometry: how far the menu is above the held icon, in px
            android.graphics.Rect m = dlg.bounds(), h = new android.graphics.Rect();
            a.edit.held.getGlobalVisibleRect(h);
            sb.append(" menu=").append(m.left).append(',').append(m.top).append(',').append(m.right).append(',').append(m.bottom)
                    .append(" icon=").append(h.left).append(',').append(h.top).append(',').append(h.right).append(',').append(h.bottom)
                    .append(" gapPx=").append(h.top - m.bottom);
        }
        sb.append(" header=").append(a.headerGrown ? "large" : "small")
                .append(" inkText=").append((a.ink.inkNow & 0xFFFFFF) == Contrast.DARK_INK ? "dark" : "white")
                .append(" inkBtn=").append((a.ink.btnInkNow & 0xFFFFFF) == Contrast.DARK_INK ? "dark" : "white");
        sb.append(" scrollY=").append(a.scrollY());
        sb.append(" home=").append(a.prefs.skylineHome() ? "skyline" : "googletv");
        HomeWatcherService hw = HomeWatcherService.instance;
        sb.append(" perf=").append(hw == null ? "no-service" : hw.perfShown() ? "on[" + hw.perfText() + "]" : "off");
        sb.append(" cards=").append(a.homeCards.any() ? a.homeCards.visibleViews().size() : 0);
        sb.append(" dockShown=").append(a.dockBand.getVisibility() == View.VISIBLE && a.dockBand.getAlpha() > 0.5f);
        sb.append(" held=").append(a.edit.held == null ? "-" : ((AppEntry) a.edit.held.getTag()).label);
        sb.append("\n  text: ").append(a.infoLine.getText()).append(" / ").append(a.clockView.getText()).append(" / ")
                .append(a.dateView.getText());
        sb.append("\n  dock: ");
        View f = a.getCurrentFocus();
        for (int i = 0; i < a.dockTiles.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.dockTiles.get(i) == f ? "[" : "").append(((AppEntry) a.dockTiles.get(i).getTag()).label)
                    .append(a.dockTiles.get(i) == f ? "]" : "");
        }
        sb.append("\n  ink: ").append(a.ink.lastTextReport).append(" ; ").append(a.ink.lastBtnReport);
        sb.append("\n  rects: text ").append(a.ink.textRects).append(" btn ").append(a.ink.btnRects);
        sb.append("\n  motion: carousel ").append(a.carousel.debug()).append(" ; swallowOK=").append(a.edit.swallowCenterUp);
        if (a.edit.held != null) {
            sb.append(" ; held scale=").append(String.format(java.util.Locale.ENGLISH, "%.2f rot=%.1f alpha=%.2f",
                    a.edit.held.getScaleX(), a.edit.held.getRotation(), a.edit.held.getAlpha()));
        }
        if (a.weatherCard != null) sb.append("\n  week: ").append(a.weatherCard.weekSummary());
        sb.append(homeCardsDebug());
        sb.append("\n  art: dock ").append(withArt(a.dockTiles)).append('/').append(a.dockTiles.size())
                .append(" grid ").append(withArt(a.gridTiles)).append('/').append(a.gridTiles.size());
        sb.append("\n  dockLook: ").append(dockLook());
        sb.append("\n  grid: ").append(gridLabels());
        sb.append("\n  system: sleepTimer=").append(sleepTimer()).append(" takeover=")
                .append(HomeWatcherService.instance != null ? "bound" : "UNBOUND").append(" snapshot=").append(HomeSnapshot.bmp != null);
        sb.append("\n  sky: ").append(a.sky.debug());
        sb.append("\n  issues: ").append(issues());
        sb.append("\n  grid=").append(a.gridTiles.size()).append(" apps");
        return sb.toString();
    }

    private String homeCardsDebug() {
        return a.homeCards.any() ? a.homeCards.debug() : "";
    }

    private static int withArt(List<TileView> l) {
        int n = 0;
        for (TileView t : l) if (t.hasBitmap()) n++;
        return n;
    }

    /** Scale, fade and blur of the dock apps that can be seen: the depth-of-field is a look, but its numbers are checkable. */
    private String dockLook() {
        StringBuilder b = new StringBuilder();
        for (TileView t : a.dockTiles) {
            if (t.getAlpha() < 0.05f || t.getVisibility() != View.VISIBLE) continue;
            if (b.length() > 0) b.append(", ");
            b.append(((AppEntry) t.getTag()).label).append(' ').append(t.lookReport());
        }
        return b.length() == 0 ? "none visible" : b.toString();
    }

    /** Grid labels are canvas-drawn, so the tree never sees them: list them with focus (*) and cut-off (~) marks. */
    private String gridLabels() {
        StringBuilder b = new StringBuilder();
        for (TileView t : a.gridTiles) {
            if (b.length() > 0) b.append(", ");
            b.append(t.isFocused() ? "*" : "").append(((AppEntry) t.getTag()).label).append(t.labelCut() ? "~" : "");
        }
        return b.toString();
    }

    private String sleepTimer() {
        long end = SleepTimer.endTime(a.prefs);
        return end <= System.currentTimeMillis() ? "off" : SleepTimer.describe(end, System.currentTimeMillis());
    }

    /** Where focus is, for the debug log: "header 1", "card 2", "dock 3 (Netflix)", "grid 5", "clean". */
    String describe(View v) {
        if (v == null) return "none";
        if (v == a.cleanAnchor) return "clean";
        int i = a.headerButtons.indexOf(v);
        if (i >= 0) return "header " + i;
        i = a.homeCards.visibleViews().indexOf(v);
        if (i >= 0) return "card " + i;
        i = a.dockTiles.indexOf(v);
        if (i >= 0) return "dock " + i + " (" + ((AppEntry) v.getTag()).label + ")";
        i = a.gridTiles.indexOf(v);
        return i >= 0 ? "grid " + i : v.getClass().getSimpleName();
    }

    // ---- generic, works for every screen: walk the real views, no per-feature code

    private static boolean shown(View v) {
        return v.getVisibility() == View.VISIBLE && v.getAlpha() > 0.05f;
    }

    private static void walk(View v, List<View> out) {
        if (!shown(v)) return;
        out.add(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out);
        }
    }

    private static String rect(android.graphics.Rect r) {
        return r.left + "," + r.top + "," + r.right + "," + r.bottom;
    }

    /** Roots to inspect: the open dialog if there is one, else the home screen. */
    private View root() {
        GlassDialog d = GlassDialog.top;
        return d != null && d.getWindow() != null ? d.getWindow().getDecorView() : a.getWindow().getDecorView();
    }

    /** Every visible text with its on-screen rectangle, focus marked with *, truncated text with ~. */
    String tree() {
        List<View> all = new ArrayList<>();
        walk(root(), all);
        StringBuilder sb = new StringBuilder();
        for (View v : all) {
            android.graphics.Rect r = new android.graphics.Rect();
            if (!v.getGlobalVisibleRect(r)) continue;
            if (v instanceof TextView && ((TextView) v).getText().length() > 0) {
                TextView t = (TextView) v;
                sb.append(v.hasFocus() || v.isFocused() ? "*" : " ").append(trunc(t) ? "~" : " ").append(" \"")
                        .append(t.getText().toString().replace("\n", " ")).append("\" ").append(rect(r)).append('\n');
            } else if (v.isFocusable() && !(v instanceof android.view.ViewGroup && ((android.view.ViewGroup) v).getChildCount() > 0 && false)) {
                sb.append(v.isFocused() ? "*" : " ").append("  [").append(v.getClass().getSimpleName()).append("] ").append(rect(r)).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean trunc(TextView t) {
        android.text.Layout l = t.getLayout();
        if (l == null) return false;
        for (int i = 0; i < l.getLineCount(); i++) if (l.getEllipsisCount(i) > 0) return true;
        return false;
    }

    /** Layout problems found without looking at pixels: cut-off text, text on text, focus that cannot be seen, things off screen. */
    String issues() {
        List<View> all = new ArrayList<>();
        walk(root(), all);
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        List<String> out = new ArrayList<>();
        List<TextView> texts = new ArrayList<>();
        List<android.graphics.Rect> rects = new ArrayList<>();
        for (View v : all) {
            android.graphics.Rect r = new android.graphics.Rect();
            boolean vis = v.getGlobalVisibleRect(r);
            if (v.isFocused() && !vis) out.add("focus on a view that is not visible: " + v.getClass().getSimpleName());
            if (v instanceof TextView && ((TextView) v).getText().length() > 0 && vis) {
                TextView t = (TextView) v;
                String s = t.getText().toString();
                if (trunc(t)) out.add("cut off: \"" + (s.length() > 28 ? s.substring(0, 28) + "…" : s) + "\"");
                if (r.right > dm.widthPixels + 2 || r.bottom > dm.heightPixels + 2 || r.left < -2 || r.top < -2) out.add("off screen: \"" + s + "\"");
                texts.add(t);
                rects.add(r);
            }
        }
        for (int i = 0; i < texts.size(); i++) {
            for (int j = i + 1; j < texts.size(); j++) {
                android.graphics.Rect x = new android.graphics.Rect(rects.get(i));
                if (!x.intersect(rects.get(j))) continue;
                long inter = (long) x.width() * x.height();
                long small = Math.min((long) rects.get(i).width() * rects.get(i).height(), (long) rects.get(j).width() * rects.get(j).height());
                if (small > 0 && inter * 4 > small) out.add("text overlaps text: \"" + shortText(texts.get(i)) + "\" / \"" + shortText(texts.get(j)) + "\"");
            }
        }
        return out.isEmpty() ? "none" : String.join("; ", out.subList(0, Math.min(out.size(), 6)));
    }

    private static String shortText(TextView t) {
        String s = t.getText().toString();
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }
}

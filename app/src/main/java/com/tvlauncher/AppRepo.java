package com.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lists launchable apps and renders their TV banners at the size they are shown. */
final class AppRepo {
    private final Context ctx;
    private final PackageManager pm;

    AppRepo(Context c) {
        ctx = c.getApplicationContext();
        pm = ctx.getPackageManager();
    }

    List<AppEntry> queryApps() {
        List<ResolveInfo> ris = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> seen = new HashSet<>();
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : ris) add(out, seen, ri);
        // Apps without a leanback entry (phone-only) are still launchable.
        List<ResolveInfo> plain = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (ResolveInfo ri : plain) add(out, seen, ri);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private void add(List<AppEntry> out, Set<String> seen, ResolveInfo ri) {
        if (ri.activityInfo == null) return;
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) return;
        out.add(new AppEntry(String.valueOf(ri.loadLabel(pm)), ri.activityInfo));
    }

    /** Opaque RGB_565 bitmap exactly w x h px. */
    Bitmap loadBanner(AppEntry e, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        if (e.isPlayStation()) {
            Ps5Banner.draw(ctx, c, w, h, e.label, e.detail);
            return b;
        }
        Drawable banner = null;
        try {
            banner = e.info.loadBanner(pm);
        } catch (Exception ignored) {
        }
        if (banner != null) {
            c.drawColor(Color.BLACK);
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
        } else {
            int hue = Math.abs(e.pkg.hashCode()) % 360;
            c.drawColor(Color.HSVToColor(new float[]{hue, 0.45f, 0.30f}));
            Drawable icon = e.info.loadIcon(pm);
            int s = (int) (h * 0.58f);
            int l = (w - s) / 2, t = (h - s) / 2;
            icon.setBounds(l, t, l + s, t + s);
            icon.draw(c);
        }
        return b;
    }
}

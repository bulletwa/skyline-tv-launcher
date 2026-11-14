package com.tvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * A small stats panel at the top left, drawn over any app. It only exists while shown: nothing is scheduled, no file is
 * read and no frame callback is registered once it is hidden. Owned by the accessibility service (its overlay window type
 * needs no extra permission).
 */
final class PerfOverlay {
    private static final String TAG = "PerfOverlay";
    private static final long SAMPLE_MS = 1000;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView view;
    private int frames;
    private long windowStart;
    private long lastRx = -1, lastTx = -1, lastNetAt;
    /** Latest text, for the debug dump. */
    volatile String last = "";

    PerfOverlay(Context ctx) {
        this.ctx = ctx;
    }

    boolean isShown() {
        return view != null;
    }

    void show() {
        if (view != null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView v = new TextView(ctx);
            v.setTypeface(Typeface.MONOSPACE);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            v.setTextColor(Color.WHITE);
            v.setLineSpacing(0, 1.05f);
            float d = ctx.getResources().getDisplayMetrics().density;
            v.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(170, 0, 0, 0));
            bg.setCornerRadius(8 * d);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.START | Gravity.TOP;
            lp.x = (int) (12 * d);
            lp.y = (int) (12 * d);
            wm.addView(v, lp);
            view = v;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot show", e);
            view = null;
            return;
        }
        frames = 0;
        windowStart = SystemClock.uptimeMillis();
        lastRx = TrafficStats.getTotalRxBytes();
        lastTx = TrafficStats.getTotalTxBytes();
        lastNetAt = windowStart;
        Choreographer.getInstance().postFrameCallback(frameCb);
        ui.postDelayed(sample, SAMPLE_MS);
        sample.run(); // first reading now, not a second from now
    }

    void hide() {
        ui.removeCallbacks(sample);
        Choreographer.getInstance().removeFrameCallback(frameCb);
        TextView v = view;
        view = null;
        last = "";
        if (v == null) return;
        try {
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(v);
        } catch (RuntimeException ignored) {
        }
    }

    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (view == null) return;
            frames++;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable sample = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            TextView v = view;
            if (v == null) return;
            long now = SystemClock.uptimeMillis();
            int fps = PerfStats.fps(frames, now - windowStart);
            if (fps >= 0) {
                frames = 0;
                windowStart = now;
            }
            PerfStats.Mem mem = PerfStats.parseMeminfo(read("/proc/meminfo"));
            String screen = "";
            try {
                Display dsp = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
                Point p = new Point();
                dsp.getRealSize(p);
                screen = PerfStats.screenLine(p.x, p.y, dsp.getRefreshRate());
            } catch (RuntimeException ignored) {
            }
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
            long dt = now - lastNetAt;
            long down = PerfStats.bytesPerSec(lastRx, rx, dt), up = PerfStats.bytesPerSec(lastTx, tx, dt);
            lastRx = rx;
            lastTx = tx;
            lastNetAt = now;
            String text = PerfStats.ramLine(mem) + "\n" + PerfStats.freeLine(mem)
                    + "\n" + screen + "\n" + PerfStats.fpsLine(fps)
                    + "\n" + link() + "\n" + PerfStats.rateLine(down, up);
            last = text.replace('\n', '|');
            v.setText(text);
            ui.postDelayed(this, SAMPLE_MS);
        }
    };

    /** Link type and Wi-Fi signal/speed. Reads only RSSI and link speed; the SSID is never touched. */
    private String link() {
        try {
            Context app = ctx.getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return PerfStats.linkLine("none", 0, 0);
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return PerfStats.linkLine("ethernet", 0, 0);
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return PerfStats.linkLine("none", 0, 0);
            WifiInfo wi = ((WifiManager) app.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            return PerfStats.linkLine("wifi", wi == null ? 0 : wi.getRssi(), wi == null ? 0 : wi.getLinkSpeed());
        } catch (RuntimeException e) {
            return "Network --";
        }
    }

    private static String read(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path), 2048)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 4096) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}

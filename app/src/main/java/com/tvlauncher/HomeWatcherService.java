package com.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * This TV ignores set-home-activity (Google TV launcher stays the Home role holder), so when the
 * stock launcher's home window comes to the front we immediately start ours. It also performs the
 * sleep timer's "lock screen" action, which a normal app is not allowed to do.
 */
public class HomeWatcherService extends AccessibilityService {
    private static final String STOCK_PKG = "com.google.android.apps.tv.launcherx";

    /** Set while the service is bound. */
    static volatile HomeWatcherService instance;

    private long lastStart;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView cover;
    private long coverShownAt;
    /** Debug: keep the cover up this long (ms) so it can be screenshotted; 0 = normal behaviour. */
    static volatile long debugHoldMs;
    /** True while our launcher is the resumed activity (set by MainActivity). */
    static volatile boolean launcherResumed;

    private final Runnable removeCover = new Runnable() {
        @Override public void run() {
            hideCover();
        }
    };

    /**
     * Covers Google TV's home screen with a picture of ours until our launcher is drawn. It is added the moment the
     * stock home window appears, before our launcher has even been asked to start.
     */
    private void showCover() {
        // A late event for Google's home can arrive after our launcher is already up; it must not bring the cover back.
        if (launcherResumed) return;
        main.removeCallbacks(removeCover);
        if (cover == null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                ImageView v = new ImageView(this);
                v.setScaleType(ImageView.ScaleType.FIT_XY);
                Bitmap b = HomeSnapshot.bmp;
                if (b != null && !b.isRecycled()) v.setImageBitmap(b); else v.setBackgroundColor(Color.BLACK);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE);
                wm.addView(v, lp);
                cover = v;
                coverShownAt = SystemClock.uptimeMillis();
            } catch (RuntimeException e) {
                Log.w("HomeWatcher", "cannot show the cover", e);
                cover = null;
            }
        }
        main.postDelayed(removeCover, Math.max(2500, debugHoldMs)); // safety: never leave it up if the launcher fails to start
    }

    private void hideCover() {
        main.removeCallbacks(removeCover);
        ImageView v = cover;
        cover = null;
        if (v == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeViewImmediate(v);
        } catch (RuntimeException ignored) {
        }
        Log.i("HomeWatcher", "cover was up for " + (SystemClock.uptimeMillis() - coverShownAt) + " ms");
    }

    /** Called by the launcher once it is on screen: the cover comes off a moment later, when its first frame is out. */
    static void launcherShown() {
        launcherResumed = true;
        final HomeWatcherService s = instance;
        if (s == null) return;
        s.main.removeCallbacks(s.removeCover);
        s.main.postDelayed(s.removeCover, Math.max(160, debugHoldMs));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (new Prefs(this).perfOverlay()) setPerfOverlay(true);
        // After a reboot Google's home is already up before this service binds, so no window event ever arrives for it.
        if (SystemClock.elapsedRealtime() < 3 * 60_000 && !launcherResumed && new Prefs(this).skylineHome()) takeOverHome();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideCover();
        if (perf != null) perf.hide();
        main.removeCallbacks(muteCheck);
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    // ------------------------------------------------------------------ performance overlay

    private PerfOverlay perf;
    private final KeyHold muteHold = new KeyHold();
    private final Runnable muteCheck = new Runnable() {
        @Override public void run() {
            if (!muteHold.due(SystemClock.uptimeMillis())) return;
            setPerfOverlay(!perfShown());
            Log.i("HomeWatcher", "Mute held: performance overlay " + (perfShown() ? "on" : "off"));
        }
    };

    /** Shows or hides the stats panel and remembers the choice. Nothing runs while it is hidden. */
    void setPerfOverlay(boolean on) {
        new Prefs(this).setPerfOverlay(on);
        if (perf == null) perf = new PerfOverlay(this);
        if (on) perf.show(); else perf.hide();
    }

    boolean perfShown() {
        return perf != null && perf.isShown();
    }

    String perfText() {
        return perf == null ? "" : perf.last;
    }

    /** A long press of Mute toggles the panel, whichever app is in front. The key itself is never swallowed. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int a = e.getAction();
        if (a != KeyEvent.ACTION_DOWN && a != KeyEvent.ACTION_UP) return false;
        if (muteHold.swallow(e.getKeyCode(), a == KeyEvent.ACTION_DOWN)) {
            if (a == KeyEvent.ACTION_UP) muteHold.onKey(e.getKeyCode(), false, 0);
            return true;
        }
        if (KeyHold.isMute(e.getKeyCode())) Log.i("HomeWatcher", "Mute " + (a == KeyEvent.ACTION_DOWN ? "down" : "up") + " repeat=" + e.getRepeatCount());
        if (muteHold.onKey(e.getKeyCode(), a == KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis())) {
            main.removeCallbacks(muteCheck);
            main.postDelayed(muteCheck, KeyHold.HOLD_MS + 30);
        }
        return false;
    }

    boolean sleepNow() {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null) return;
        if (!STOCK_PKG.contentEquals(pkg) || !cls.toString().endsWith("HomeActivity")) return;
        if (!new Prefs(this).skylineHome()) return; // user switched Home back to Google TV
        takeOverHome();
    }

    private final Runnable retryTakeOver = new Runnable() {
        @Override public void run() {
            if (!launcherResumed && new Prefs(HomeWatcherService.this).skylineHome()) takeOverHome();
        }
    };

    /** Covers the stock home and starts our launcher; used when its window appears and when this service connects. */
    private void takeOverHome() {
        showCover(); // first, before anything else: the quicker it is up the less of the stock home is seen
        long now = System.currentTimeMillis();
        if (now - lastStart < 700) {
            // Waking from the screensaver, Google's home re-foregrounds itself right after our start. Dropping this event
            // left it on top, so look again once the debounce has passed.
            main.removeCallbacks(retryTakeOver);
            main.postDelayed(retryTakeOver, 700 - (now - lastStart) + 50);
            return;
        }
        lastStart = now;
        Intent i = new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}

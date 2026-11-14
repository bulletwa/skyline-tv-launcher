package com.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.util.Log;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AppsHost {
    private static final int GRID_COLS = 4;
    static final int PAGE_PAD_DP = 48;
    private static final String[] PREFERRED = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "in.startv.hotstar", "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android", "com.plexapp.android", "com.android.vending",
            "com.google.android.apps.youtube.music", "org.xbmc.kodi"
    };

    float d;
    Prefs prefs;
    private AppRepo repo;
    final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Pools.fixed(1, "launcher-io");
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private ScrollView scroll;
    TextView infoLine, clockView, dateView;
    LinearLayout headerLeft;
    View dividerView;
    SkyRenderer.Input lastSky;
    final InkController ink = new InkController(this);
    final CleanMode cm = new CleanMode(this);
    final ObstacleFeed obstacles = new ObstacleFeed(this);
    final DockEditor edit = new DockEditor(this);
    final DebugTools dbg = new DebugTools(this);
    boolean headerGrown;
    DockCarousel carousel;
    private boolean carouselPlaced; // false until the first focus after a rebuild, which snaps instead of animating
    private GridLayout grid;
    FrameLayout dockBand;
    /** 1 = the normal dock; {@link CleanMode#cleanDockScale} = the compact full-width bar shown in clean mode. */
    float dockScale = 1f;
    SkyController sky;
    private WeatherState weather;

    final List<RoundButton> headerButtons = new ArrayList<>();
    private String lastCc = "";
    HomeCards homeCards;
    private View rootView; // the whole home screen, for HomeSnapshot
    private SpotlightController spotlight;
    private NewsController news;
    WeatherCardController weatherCard;
    private Ps5 ps5;
    HintView hint;
    View cleanAnchor;
    boolean clean; // apps hidden too: just the header and the wallpaper
    boolean started;
    final List<TileView> dockTiles = new ArrayList<>();
    final List<TileView> gridTiles = new ArrayList<>();
    private List<AppEntry> allApps = new ArrayList<>();
    List<AppEntry> dockApps = new ArrayList<>();
    private TileView lastDockFocus;
    private boolean firstFocusDone;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH);

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock();
            sky.tick();
            updateWeatherCard();
        }
    };
    private final BroadcastReceiver pkgChange = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        reclaimHome(getIntent());
        repo = new AppRepo(this);
        ps5 = new Ps5(this, prefs);
        buildUi();
        reloadApps();
        applySceneExtra(getIntent());
    }

    /** `am start -n com.tvlauncher/.MainActivity --es scene rain` previews a fixed sky; `--es scene auto` resets. */
    private void applySceneExtra(Intent i) {
        if (i == null) return;
        // `--es ink dark` / `--es ink white` force the text colour to check how each looks; `--es ink auto` hands it back
        if (i.hasExtra("ink")) {
            String v = i.getStringExtra("ink");
            if ("dark".equals(v)) { if (ink.inkAnim != null) ink.inkAnim.cancel(); ink.paintInk(0xFF000000 | Contrast.DARK_INK); }
            else if ("white".equals(v)) { if (ink.inkAnim != null) ink.inkAnim.cancel(); ink.paintInk(Color.WHITE); }
            else ink.applyInk(false);
        }
        // `--el holdcover 6000` keeps the Home cover up that long so it can be screenshotted; `--el holdcover 0` = normal
        if (i.hasExtra("holdcover")) HomeWatcherService.debugHoldMs = i.getLongExtra("holdcover", 0);
        // `--es style vangogh` / `--es style classic` switches the background style (saved)
        if (i.hasExtra("style")) sky.setStyle("vangogh".equals(i.getStringExtra("style")) ? "vangogh" : "classic");
        // `--es season autumn` (or spring/summer/winter/auto) forces the season's nature (saved)
        if (i.hasExtra("season")) sky.setSeason(i.getStringExtra("season"));
        if (i.hasExtra("strike")) sky.debugStrike("hold".equals(i.getStringExtra("strike")));
        // `--es fakeplay "Title|Artist"` publishes a fake media session so the Now Playing card can be tested; `--es fakeplay off`
        if (i.hasExtra("fakeplay")) dbg.debugFakePlay(i.getStringExtra("fakeplay"));
        // `--es cards spotlight,news,weather` / `--es cardsshow 0` switch card layouts for testing (these ARE saved)
        if (i.hasExtra("cards") || i.hasExtra("cardsshow")) {
            if (i.hasExtra("cards")) prefs.setCardsEnabled(CardsConfig.parse(i.getStringExtra("cards")));
            if (i.hasExtra("cardsshow")) prefs.setCardsShown(!"0".equals(i.getStringExtra("cardsshow")));
            if (homeCards != null) cardsChanged();
        }
        // `--ez sleepnow true` runs exactly what the sleep timer runs when it expires (logged as "SleepTimer")
        if (i.getBooleanExtra("sleepnow", false)) {
            android.util.Log.i("SleepTimer", "fired via debug extra: " + SleepTimer.fire(this));
        }
        if (!i.hasExtra("scene")) return;
        sky.setScene(Scenes.build(i.getStringExtra("scene")));
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, t);
        IntentFilter p = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_REPLACED);
        p.addDataScheme("package");
        registerReceiver(pkgChange, p);
        if (BuildConfig.DEBUG) registerReceiver(dbg.dumpReceiver, new IntentFilter("com.tvlauncher.DUMP"));
        updateClock();
        started = true;
        ink.start();
        obstacles.scheduleObstacles();
        refreshPlayStation(); // a console may have been plugged in or renamed while we were away
        sky.start();
        homeCards.start();
        hint.bounce();
        if (clean) {
            ui.removeCallbacks(cm.cleanNudge);
            ui.postDelayed(cm.cleanNudge, CleanMode.CLEAN_NUDGE_MS);
        }
    }

    /**
     * Another app (or a translucent panel such as the TV's settings) took the screen. Anything that is not seen is
     * suspended: the sky stops drawing and the cards stop cycling, fetching and listening. onResume restarts them.
     */
    @Override
    protected void onPause() {
        // A picture of the home screen for the cover that hides Google TV's home on the way back (clock left out: it
        // would be out of date). Taken first, while everything is still drawn.
        HomeWatcherService.launcherResumed = false;
        if (started && !isFinishing()) HomeSnapshot.capture(rootView, sky.currentBitmap(), headerLeft);
        super.onPause();
        sky.pauseAnimation();
        homeCards.stop();
        hint.stopBounce();
        ui.removeCallbacks(cm.cleanNudge);
        ui.removeCallbacks(obstacles.obstacleUpdate);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeWatcherService.launcherShown();
        sky.resumeAnimation();
        if (started) {
            homeCards.start();
            if (clean) ui.postDelayed(cm.cleanNudge, CleanMode.CLEAN_NUDGE_MS);
        }
        obstacles.scheduleObstacles();
    }

    /** The launcher is no longer visible at all: hand back the memory that only drawing needed. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // keep HomeSnapshot: it is only 0.9 MB and is what makes Home look instant
            bitmaps.evictAll();
            ImageLoader.get().trim();
            sky.releaseMemory();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ink.stop();
        if (edit.moving) edit.endMove(false); else edit.liftOff();
        ui.removeCallbacks(cm.cleanNudge);
        if (ink.inkAnim != null) ink.inkAnim.cancel();
        carousel.cancelAnimations();
        sky.stop();
        homeCards.stop();
        unregisterReceiver(tick);
        unregisterReceiver(pkgChange);
        if (BuildConfig.DEBUG) unregisterReceiver(dbg.dumpReceiver);
    }

    /** Opening Skyline from Google TV's app row (not via Home) switches Home back to Skyline. */
    private void reclaimHome(Intent i) {
        if (i != null && i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) && !i.hasCategory(Intent.CATEGORY_HOME)) prefs.setSkylineHome(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        reclaimHome(intent);
        applySceneExtra(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (clean) cm.exitClean(); else focusRecentDock();
        }
    }

    // ---------------------------------------------------------------- UI

    int dp(float v) { return Math.round(v * d); }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Fonts.get(this, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK);
        FrameLayout wallpaper = new FrameLayout(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        lastCc = prefs.str("cc", "");
        sky = new SkyController(this, prefs, (w, place) -> {
            weather = w;
            weatherPlace = place;
            updateClock();
            updateWeatherCard();
            // a new country (first run, or a new location) changes which local channels exist
            String cc = prefs.str("cc", "");
            if (!cc.equals(lastCc)) {
                lastCc = cc;
                if (!cc.isEmpty() && news != null) news.channelsChanged();
            }
        });
        sky.attach(wallpaper);
        sky.setSkyListener(in -> {
            lastSky = in;
            ink.applyInk(true);
        });
        sky.setScene(Scenes.build(prefs.str("scene", "auto")));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));

        int screenH = getResources().getDisplayMetrics().heightPixels;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setClipChildren(false);
        top.setClipToPadding(false); // focus zoom may overshoot the page margins
        top.setPadding(dp(PAGE_PAD_DP), 0, dp(PAGE_PAD_DP), 0);
        column.addView(top, new LinearLayout.LayoutParams(-1, screenH));

        // ---- header
        FrameLayout header = new FrameLayout(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, dp(76));
        hlp.topMargin = dp(20);
        top.addView(header, hlp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        header.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));
        headerLeft = left; // scaled up and centred whenever there is free space (clean mode, or no cards)
        left.setPivotX(0);
        left.setPivotY(0);
        // The weather text changes its width when new readings arrive: keep the block centred without animating.
        left.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) cm.layoutHeader(false);
        });

        infoLine = text(12, 400, Color.argb(190, 255, 255, 255));
        infoLine.setLetterSpacing(0.22f);
        left.addView(infoLine);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);
        clockView = text(54, 200, Color.WHITE);
        clockRow.addView(clockView);
        View divider = new View(this);
        dividerView = divider;
        divider.setBackgroundColor(Color.argb(80, 255, 255, 255));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(30));
        dlp.setMargins(dp(18), 0, dp(18), dp(8));
        clockRow.addView(divider, dlp);
        dateView = text(14, 300, Color.argb(210, 255, 255, 255));
        dateView.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams datelp = new LinearLayout.LayoutParams(-2, -2);
        datelp.bottomMargin = dp(8);
        clockRow.addView(dateView, datelp);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(-2, -2);
        crlp.topMargin = dp(4);
        left.addView(clockRow, crlp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setClipChildren(false);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        rlp.topMargin = dp(4);
        header.addView(right, rlp);
        header.setClipChildren(false);

        RoundButton wifi = new RoundButton(this, RoundButton.WIFI);
        wifi.setOnClickListener(v -> startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        RoundButton gear = new RoundButton(this, RoundButton.GEAR);
        gear.setOnClickListener(v -> openLauncherSettings());
        headerButtons.add(wifi);
        headerButtons.add(gear);
        for (RoundButton rb : headerButtons) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(36), dp(36));
            blp.leftMargin = dp(12);
            right.addView(rb, blp);
        }

        // ---- cards
        LinearLayout cardRow = new LinearLayout(this);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        cardRow.setClipChildren(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(220));
        clp.topMargin = dp(10);
        top.addView(cardRow, clp);
        // Every card is a CardController; HomeCards lays out the ones that are switched on (or none).
        homeCards = new HomeCards(this, prefs, cardRow, dp(PAGE_PAD_DP));
        spotlight = new SpotlightController(this, new CardView(this), 0, 0);
        news = new NewsController(this, new CardView(this), prefs, 0, 0);
        weatherCard = new WeatherCardController(this, new CardView(this));
        homeCards.register(CardsConfig.SPOTLIGHT, spotlight);
        homeCards.register(CardsConfig.NEWS, news);
        homeCards.register(CardsConfig.NOW_PLAYING, new NowPlayingController(this, new CardView(this)));
        homeCards.register(CardsConfig.WEATHER, weatherCard);
        homeCards.register(CardsConfig.PS5, new Ps5CardController(this, new CardView(this), ps5, this::openPs5Chooser));
        homeCards.apply();

        // The free space sits above the dock so the dock stays at the bottom even with no cards.
        top.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // ---- dock
        dockBand = new FrameLayout(this);
        dockBand.setBackground(new GlassDrawable(dp(28), 1.2f * d, 96));
        // The frost slice depends on where the band is on screen, so redraw it while the page scrolls.
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            dockBand.invalidate();
            cm.onPageScrolled(y);
            obstacles.scheduleObstacles(); // the dock moves up the screen as the page scrolls
        });
        dockBand.setClipToOutline(true); // banners scrolling past the ends follow the rounded corners
        LinearLayout.LayoutParams dblp = new LinearLayout.LayoutParams(-1, dp(125));
        dblp.bottomMargin = dp(6);
        top.addView(dockBand, dblp);

        // Points at the app drawer one step down; it stays visible in clean mode, where the dock is hidden.
        hint = new HintView(this);
        top.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        // A looping carousel: the selected app stays in the middle and the rest circle around it.
        // A very faint glow behind the middle app; DockCarousel fades it in only while the screen is clean.
        View shine = new View(this);
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
        glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(120));
        glow.setColors(new int[]{Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255)});
        shine.setBackground(glow);
        shine.setAlpha(0f);
        dockBand.addView(shine, new FrameLayout.LayoutParams(dp(280), -1, Gravity.CENTER_HORIZONTAL));
        carousel = new DockCarousel(this, dp(180 + 16));
        carousel.setShine(shine);
        dockBand.addView(carousel, new FrameLayout.LayoutParams(-1, -1));

        // ---- all apps
        LinearLayout apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.VERTICAL);
        apps.setClipChildren(false);
        apps.setClipToPadding(false);
        apps.setPadding(dp(PAGE_PAD_DP), dp(8), dp(PAGE_PAD_DP), dp(40));
        column.addView(apps, new LinearLayout.LayoutParams(-1, -2));
        TextView appsLabel = text(12, 400, Color.argb(130, 255, 255, 255));
        appsLabel.setLetterSpacing(0.22f);
        appsLabel.setText("ALL APPS");
        LinearLayout.LayoutParams allp = new LinearLayout.LayoutParams(-2, -2);
        allp.leftMargin = dp(8);
        allp.bottomMargin = dp(8);
        apps.addView(appsLabel, allp);
        grid = new GridLayout(this);
        grid.setColumnCount(GRID_COLS);
        grid.setClipChildren(false);
        apps.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        // Invisible 1px target that holds focus in clean mode, so keys still reach us with nothing highlighted.
        cleanAnchor = new View(this);
        cleanAnchor.setFocusable(false);
        root.addView(cleanAnchor, new FrameLayout.LayoutParams(1, 1));

        // The dock highlight and its depth-of-field only belong to the dock: follow focus in and out of it.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> onFocusMoved(newFocus));
        root.getViewTreeObserver().addOnGlobalLayoutListener(obstacles::scheduleObstacles);

        setContentView(root);
        updateClock();
    }

    private boolean inDock(View v) {
        return v instanceof TileView && !((TileView) v).grid;
    }

    /** The dock's depth-of-field shows while the dock has focus and relaxes when focus is elsewhere. */
    /**
     * Shows the dock at {@code s} times its size while it still spans the page: the band is laid out 1/s times wider and
     * scaled about its bottom centre, so a smaller dock simply shows more apps. Animated by {@link CleanMode}.
     */
    void setDockScale(float s) {
        dockScale = s;
        float avail = getResources().getDisplayMetrics().widthPixels - 2f * dp(PAGE_PAD_DP);
        android.view.ViewGroup.LayoutParams lp = dockBand.getLayoutParams();
        int w = s >= 0.999f ? -1 : Math.round(avail / s);
        if (lp.width != w) {
            lp.width = w;
            dockBand.setLayoutParams(lp);
        }
        dockBand.setPivotX((w < 0 ? avail : w) / 2f);
        dockBand.setPivotY(dp(125));
        dockBand.setScaleX(s);
        dockBand.setScaleY(s);
        dockBand.setTranslationX(avail / 2f * (1f - 1f / s));
        obstacles.scheduleObstacles();
    }

    private void onFocusMoved(View now) {
        boolean dock = inDock(now);
        android.util.Log.d("Focus", dbg.describe(now));
        obstacles.scheduleObstacles(); // a focused card grows a little
        if (clean) return; // the dock is fading out; don't fight it
        carousel.setFocused(dock);
        if (dock && !wasInDock) hint.bounce();
        wasInDock = dock;
    }

    private boolean wasInDock;

    /** The Cards settings changed (or cards were switched on/off): rebuild the row and keep focus sensible. */
    @Override
    public void cardsChanged() {
        boolean wasStarted = started;
        homeCards.apply();
        if (wasStarted) homeCards.start();
        if (clean && homeCards.any()) cm.exitClean();
        else if (!clean && !dockTiles.isEmpty()) focusRecentDock();
        obstacles.scheduleObstacles();
        cm.layoutHeader(true); // fills the free space when the last card is switched off, and shrinks back when one comes on
    }

    private String weatherPlace = "";

    private void updateWeatherCard() {
        if (weather == null || weatherCard == null) return;
        SkyRenderer.Input in = sky.currentSky();
        weatherCard.update(weather, weatherPlace, in.sunElev, in.morning());
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(timeFmt.format(now));
        dateView.setText(dateFmt.format(now).toUpperCase(Locale.ENGLISH));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING";
        StringBuilder sb = new StringBuilder(g);
        if (weather != null) {
            String t = weather.temperatureText();
            if (t != null) sb.append(" • ").append(t);
            sb.append(" • ").append(weather.description());
            if (weather.aqiText() != null) sb.append(" • ").append(weather.aqiText());
        }
        infoLine.setText(sb);
    }

    // ---------------------------------------------------------------- apps

    private void reloadApps() {
        io.execute(() -> {
            final List<AppEntry> apps = repo.queryApps();
            ui.post(() -> {
                applyPlayStation(apps);
                rebuildLists();
            });
        });
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : allApps) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    // ---------------------------------------------------------------- PlayStation entry

    /**
     * Sets {@code allApps} to the installed apps plus, when the TV has a PlayStation on an HDMI input, a
     * "PlayStation 5" entry. The first time it is found it is also added to the end of the dock (once; if you
     * later remove it, it stays removed).
     */
    private void applyPlayStation(List<AppEntry> apps) {
        List<AppEntry> out = new ArrayList<>(apps);
        Ps5.Info p = ps5.find();
        if (p != null) {
            out.add(AppEntry.playStation(p.name, p.port()));
            java.util.Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        }
        allApps = out;
        if (p != null && !prefs.str("ps5_dock_added", "").equals("1")) {
            List<String> dock = new ArrayList<>(currentDockPkgs());
            if (!dock.contains(Ps5.DOCK_ID)) dock.add(Ps5.DOCK_ID);
            prefs.setDock(dock);
            prefs.putStr("ps5_dock_added", "1");
        }
    }

    /** Re-checks the console (it may have been plugged in, renamed or re-chosen); rebuilds only if that changed. */
    private void refreshPlayStation() {
        if (allApps.isEmpty()) return; // the first app load will include it
        Ps5.Info p = ps5.find();
        AppEntry cur = find(Ps5.DOCK_ID);
        String was = cur == null ? "" : cur.label + "|" + cur.detail;
        String now = p == null ? "" : p.name + "|" + p.port();
        if (was.equals(now)) return;
        List<AppEntry> apps = new ArrayList<>(allApps);
        if (cur != null) apps.remove(cur);
        bitmaps.remove(Ps5.DOCK_ID + "#d");
        bitmaps.remove(Ps5.DOCK_ID + "#g");
        applyPlayStation(apps);
        rebuildLists();
    }

    @Override
    public void playStationChanged() {
        refreshPlayStation();
        CardController c = homeCards.get(CardsConfig.PS5);
        if (c instanceof Ps5CardController) ((Ps5CardController) c).render();
    }

    private void openPs5Chooser() {
        new HdmiInputDialog(this, prefs, ps5, this::playStationChanged).dialog().show();
    }

    List<String> currentDockPkgs() {
        List<String> saved = prefs.dock();
        if (saved != null) return saved;
        List<String> out = new ArrayList<>();
        for (String p : PREFERRED) if (find(p) != null) out.add(p);
        for (AppEntry e : allApps) {
            if (out.size() >= 8) break;
            if (!out.contains(e.pkg)) out.add(e.pkg);
        }
        return out;
    }

    void rebuildLists() {
        Set<String> hidden = prefs.hidden();
        dockApps = new ArrayList<>();
        for (String p : currentDockPkgs()) {
            AppEntry e = find(p);
            if (e != null && !hidden.contains(p)) dockApps.add(e);
        }

        dockTiles.clear();
        for (AppEntry e : dockApps) dockTiles.add(makeTile(e, false));
        carousel.setTiles(dockTiles);

        grid.removeAllViews();
        gridTiles.clear();
        int pageW = getResources().getDisplayMetrics().widthPixels - 2 * dp(PAGE_PAD_DP);
        int cell = pageW / GRID_COLS;
        for (AppEntry e : allApps) {
            if (hidden.contains(e.pkg)) continue;
            TileView t = makeTile(e, true);
            t.setCellWidth(cell);
            grid.addView(t, new GridLayout.LayoutParams());
            gridTiles.add(t);
        }

        updateFavourite();
        carouselPlaced = false;
        lastDockFocus = null;
        if (clean) {
            // The dock was just edited from clean mode: bring it back rather than park focus on hidden tiles.
            firstFocusDone = true;
            cm.exitClean();
        } else if (!firstFocusDone && !dockTiles.isEmpty()) {
            firstFocusDone = true;
            focusRecentDock();
        } else if (!dockTiles.isEmpty()) {
            dockTiles.get(0).requestFocus();
        }
    }

    /** Your most-launched app (3+ launches) becomes a spotlight slide with its own artwork. */
    private void updateFavourite() {
        List<String> pkgs = new ArrayList<>();
        for (AppEntry e : allApps) pkgs.add(e.pkg);
        String fav = prefs.favourite(pkgs, 3);
        final AppEntry e = fav == null ? null : find(fav);
        if (e == null) {
            spotlight.setFavourite(null);
            return;
        }
        io.execute(() -> {
            final Bitmap b = repo.loadBanner(e, 480, 270);
            ui.post(() -> {
                SpotlightController.Spot s = new SpotlightController.Spot();
                s.accent = "YOUR FAVOURITE";
                s.accentColor = Color.rgb(140, 220, 200);
                s.title = e.label;
                s.subtitle = "You open this most";
                s.art = new AppArtDrawable(b);
                s.click = () -> launch(e);
                spotlight.setFavourite(s);
            });
        });
    }

    private TileView makeTile(final AppEntry e, final boolean inGrid) {
        final TileView t = new TileView(this, inGrid);
        t.setTag(e);
        t.setLabel(e.label);
        t.setOnClickListener(v -> launch(e));
        t.setOnLongClickListener(v -> { if (inGrid) showAppOptions(e); else edit.showDockMenu(e); return true; });
        t.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            if (inGrid) {
                revealGridTile(t);
                return;
            }
            lastDockFocus = t;
            // The first focus after a rebuild snaps into place; after that the ring turns to the new app.
            carousel.select(dockTiles.indexOf(t), carouselPlaced);
            carouselPlaced = true;
            scroll.smoothScrollTo(0, 0);
        });
        final String key = e.pkg + (inGrid ? "#g" : "#d");
        Bitmap cached = bitmaps.get(key);
        if (cached != null) {
            t.setBitmap(cached);
        } else {
            final int w = (int) t.bw, h = (int) t.bh;
            io.execute(() -> {
                final Bitmap b = repo.loadBanner(e, w, h);
                bitmaps.put(key, b);
                ui.post(() -> { if (t.getTag() == e) t.setBitmap(b); });
            });
        }
        return t;
    }

    /**
     * Scrolls so the focused grid app is fully on screen. Done here (not left to the ScrollView) because a dock app's
     * smoothScrollTo(0, 0) may still be running when a quick Down reaches the grid, and would drag the page back up.
     */
    private void revealGridTile(TileView t) {
        android.graphics.Rect r = new android.graphics.Rect();
        t.getDrawingRect(r);
        scroll.offsetDescendantRectToMyCoords(t, r);
        int top = scroll.getScrollY(), view = scroll.getHeight();
        int target = top;
        if (r.bottom + dp(40) > top + view) target = r.bottom + dp(40) - view;
        if (r.top - dp(24) < target) target = Math.max(0, r.top - dp(24));
        scroll.smoothScrollTo(0, target);
    }

    int scrollY() { return scroll.getScrollY(); }

    /** Clean mode: Left/Right turn the compact dock ring a step; the bar stays small and nothing is highlighted. */
    private void turnCleanDock(int dir) {
        if (dockTiles.isEmpty()) return;
        int cur = lastDockFocus == null ? -1 : dockTiles.indexOf(lastDockFocus);
        int next = DockFocus.step(Math.max(cur, 0), dir, dockTiles.size());
        lastDockFocus = dockTiles.get(next); // so Down brings the bar up on the app that is in the middle
        carousel.select(next, true);
    }

    /** Clean mode: OK opens the app that is in the middle of the compact bar. */
    private void launchCleanDock() {
        if (lastDockFocus != null && lastDockFocus.getTag() instanceof AppEntry) launch((AppEntry) lastDockFocus.getTag());
    }

    /** Leaving clean mode: the dock grows around the app that is in the middle, not a different "recent" one. */
    void focusSelectedDock() {
        if (lastDockFocus != null && lastDockFocus.getTag() instanceof AppEntry && !((AppEntry) lastDockFocus.getTag()).isPlayStation()) {
            scroll.scrollTo(0, 0);
            lastDockFocus.requestFocus();
        } else {
            focusRecentDock();
        }
    }

    void focusRecentDock() {
        if (dockTiles.isEmpty()) return;
        TileView best = dockTiles.get(0);
        long bestT = -1;
        for (TileView t : dockTiles) {
            AppEntry e = (AppEntry) t.getTag();
            if (e.isPlayStation()) continue; // Home must never land on a tile that switches input and wakes the console
            long lt = prefs.lastLaunch(e.pkg);
            if (lt > bestT || bestT < 0 && best.getTag() != null && ((AppEntry) best.getTag()).isPlayStation()) { bestT = lt; best = t; }
        }
        scroll.scrollTo(0, 0);
        best.requestFocus();
    }

    void launch(AppEntry e) {
        if (!e.isPlayStation()) prefs.markLaunched(e.pkg);
        if (e.isPlayStation()) { // not an app: jump to the console's HDMI input
            Ps5.Info p = ps5.find();
            if (p == null || !ps5.switchTo(this, p)) {
                Toast.makeText(this, "Couldn't switch input. Try the TV's own input list.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startSafely(i);
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- dialogs (temporary plain versions)

    private void openLauncherSettings() {
        new SettingsFlow(this, prefs, sky, news, this).open();
    }

    // AppsHost: what the settings dialogs need from the home screen.

    @Override public List<AppEntry> apps() { return allApps; }

    @Override public List<String> dock() { return new ArrayList<>(currentDockPkgs()); }

    @Override
    public void saveDock(List<String> pkgs) {
        if (pkgs == null) prefs.remove("dock"); else prefs.setDock(pkgs);
        rebuildLists();
    }

    @Override public Set<String> hidden() { return prefs.hidden(); }

    @Override
    public void saveHidden(Set<String> hidden) {
        prefs.setHidden(hidden);
        rebuildLists();
    }

    private void showAppOptions(final AppEntry e) {
        final List<String> dock = new ArrayList<>(currentDockPkgs());
        final boolean inDock = dock.contains(e.pkg);
        GlassDialog dlg = new GlassDialog(this, 420).title(e.label);
        if (!inDock) {
            dlg.row("Move into dock", null, () -> { dock.add(e.pkg); prefs.setDock(dock); rebuildLists(); });
        } else {
            final int idx = dock.indexOf(e.pkg);
            if (idx > 0) {
                dlg.row("Move left", null, () -> { dock.remove(idx); dock.add(idx - 1, e.pkg); prefs.setDock(dock); rebuildLists(); });
            }
            if (idx < dock.size() - 1) {
                dlg.row("Move right", null, () -> { dock.remove(idx); dock.add(idx + 1, e.pkg); prefs.setDock(dock); rebuildLists(); });
            }
            dlg.row("Remove from dock", null, () -> { dock.remove(e.pkg); prefs.setDock(dock); rebuildLists(); });
        }
        dlg.row("Hide", "Remove from the home screen", () -> {
            Set<String> h = new HashSet<>(prefs.hidden());
            h.add(e.pkg);
            prefs.setHidden(h);
            rebuildLists();
        });
        if (e.isPlayStation()) {
            dlg.row("Choose HDMI input", "Which input is your PlayStation on", () -> openPs5Chooser());
        } else {
            dlg.row("App info", null, () -> startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + e.pkg))));
        }
        dlg.show();
    }

    // ---------------------------------------------------------------- keys
    // (see the D-pad rules below: Up/Down move between header, cards, dock and apps; sideways never leaves a row)

    private void focusDock() {
        if (lastDockFocus != null) lastDockFocus.requestFocus();
        else if (!dockTiles.isEmpty()) dockTiles.get(0).requestFocus();
    }

    /**
     * Pressing Left/Right at the end of a row must stay in that row. Without this the focus search finds the
     * nearest focusable thing in that direction and jumps into the dock from the cards or header.
     */
    private boolean atSideEdge(View f, int key) {
        boolean left = key == KeyEvent.KEYCODE_DPAD_LEFT;
        List<? extends View> row = null;
        if (headerButtons.contains(f)) row = headerButtons;
        else if (homeCards.visibleViews().contains(f)) row = homeCards.visibleViews();
        if (row != null) {
            int i = row.indexOf(f);
            return left ? i == 0 : i == row.size() - 1;
        }
        int g = gridTiles.indexOf(f);
        if (g >= 0) return left ? g % GRID_COLS == 0 : g % GRID_COLS == GRID_COLS - 1 || g == gridTiles.size() - 1;
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) {
            int uk = ev.getKeyCode();
            if (edit.swallowCenterUp && (uk == KeyEvent.KEYCODE_DPAD_CENTER || uk == KeyEvent.KEYCODE_ENTER)) {
                edit.swallowCenterUp = false; // the OK that dropped an app must not also open it
                return true;
            }
            if (uk == KeyEvent.KEYCODE_DPAD_CENTER || uk == KeyEvent.KEYCODE_ENTER) ui.removeCallbacks(edit.holdOpen);
            if (edit.moving) return true;
            // In clean mode nothing is highlighted: swallow the key-up so it cannot click something unseen.
            return clean && getCurrentFocus() == cleanAnchor || super.dispatchKeyEvent(ev);
        }
        View f = getCurrentFocus();
        int k = ev.getKeyCode();
        List<CardView> cards = homeCards.visibleViews();

        // A fresh OK press forgets any release that was meant to be swallowed but went to a dialog instead.
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && ev.getRepeatCount() == 0 && !edit.moving) edit.swallowCenterUp = false;
        if (edit.moving) { // carrying a dock app: only these keys do anything
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) edit.moveStep(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) edit.moveStep(1);
            else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { edit.endMove(true); edit.swallowCenterUp = true; }
            else if (k == KeyEvent.KEYCODE_BACK) edit.endMove(false);
            return true;
        }

        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && ev.getRepeatCount() == 0
                && f instanceof TileView && dockTiles.contains(f) && !edit.moving) {
            ui.removeCallbacks(edit.holdOpen);
            ui.postDelayed(edit.holdOpen, edit.HOLD_MS); // the system waits ~500 ms for a long press; a edit.held OK feels quicker at 300
        }
        // Holding OK on a dock app: the first repeat opens its menu; the release afterwards must not open the app.
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && ev.getRepeatCount() > 0
                && f instanceof TileView && dockTiles.contains(f)) {
            if (!edit.swallowCenterUp) {
                edit.swallowCenterUp = true;
                f.setPressed(false);
                edit.showDockMenu((AppEntry) f.getTag());
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && f instanceof TileView) {
            showAppOptions((AppEntry) f.getTag());
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (clean) cm.exitClean();
            else if (!dockTiles.isEmpty()) focusRecentDock();
            return true;
        }
        if (f == null) return super.dispatchKeyEvent(ev);

        // ---- clean mode: apps hidden, header only
        if (clean) {
            if (f == cleanAnchor) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) turnCleanDock(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                else if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && ev.getRepeatCount() == 0) launchCleanDock();
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN) cm.exitClean();
                else if (k == KeyEvent.KEYCODE_DPAD_UP) headerButtons.get(headerButtons.size() - 1).requestFocus();
                return true; // nothing else does anything while the screen is clean
            }
            if (headerButtons.contains(f) && k == KeyEvent.KEYCODE_DPAD_DOWN) {
                cm.exitClean();
                return true;
            }
        }

        // ---- the dock is a ring: Left/Right turn it and never reach an end
        if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && dockTiles.contains(f)) {
            int next = DockFocus.step(dockTiles.indexOf(f), k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, dockTiles.size());
            dockTiles.get(next).requestFocus();
            return true; // the stock focus search cannot see the carousel's edit.moving apps, so we do the edit.moving
        }

        // ---- left/right in the other rows never leave the row they are in
        if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && atSideEdge(f, k)) return true;

        if (k == KeyEvent.KEYCODE_DPAD_UP) {
            if (headerButtons.contains(f)) { // the top of the screen: one more Up hides everything but the clock
                if (prefs.cleanOnUp()) cm.enterClean();
                return true;
            }
            if (cards.contains(f)) {
                headerButtons.get(Math.min(cards.indexOf(f), headerButtons.size() - 1)).requestFocus();
                return true;
            }
            if (dockTiles.contains(f)) {
                if (!cards.isEmpty()) cards.get(0).requestFocus();
                else if (prefs.cleanOnUp()) cm.enterClean();
                else headerButtons.get(headerButtons.size() - 1).requestFocus();
                return true;
            }
            if (gridTiles.contains(f) && gridTiles.indexOf(f) < GRID_COLS && lastDockFocus != null) {
                lastDockFocus.requestFocus();
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (headerButtons.contains(f)) {
                if (!cards.isEmpty()) cards.get(Math.min(headerButtons.indexOf(f), cards.size() - 1)).requestFocus();
                else focusDock();
                return true;
            }
            if (cards.contains(f)) {
                focusDock();
                return true;
            }
            if (dockTiles.contains(f)) {
                if (!gridTiles.isEmpty()) gridTiles.get(0).requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }
}

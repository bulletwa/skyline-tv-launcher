package com.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the wallpaper: location, weather refresh (15 min), change detection and a 3 s crossfade.
 * Idle cost is one cheap check per minute (driven by TIME_TICK) and one network fetch per 15 min.
 */
final class SkyController {
    interface Listener {
        void onWeather(WeatherState w, String place);
    }

    private static final long REFRESH_MS = 15 * 60 * 1000L;
    private static final double DEFAULT_LAT = 28.61, DEFAULT_LON = 77.21; // until the IP guess arrives

    private final Context ctx;
    private final Prefs prefs;
    private final Listener listener;
    private java.util.function.Consumer<SkyRenderer.Input> skyListener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Pools.fixed(1, "sky-build");
    private SkyStyle style = new ClassicStyle();

    private ImageView under, over;
    private SkyView rain; // the living sky: moving clouds / mist / stars, and rain, snow, lightning
    private boolean animated = true;
    private Bitmap shown;
    private boolean fading, rendering, pending, started;

    private WeatherState weather = new WeatherState();
    private double lat = DEFAULT_LAT, lon = DEFAULT_LON;
    private String place = "";

    private String lastSig;
    private double lastSunKey = Double.NaN, lastMoonElev = Double.NaN;
    private SkyRenderer.Input scene; // fixed scene overrides the live sky when non-null

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            fetchWeather();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    SkyController(Context c, Prefs prefs, Listener l) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        listener = l;
    }

    void attach(FrameLayout host) {
        under = new ImageView(ctx);
        over = new ImageView(ctx);
        for (ImageView v : new ImageView[]{under, over}) {
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            host.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        over.setAlpha(0f);
        // Directly above the wallpaper, behind everything else; visible while the sky is animated or it rains/snows.
        rain = new SkyView(ctx);
        animated = !"0".equals(prefs.str("sky_anim", "1"));
        style = SkyStyle.byName(prefs.str("sky_style", "classic"));
        SkyRenderer.seasonOverride = seasonIndex(prefs.str("sky_season", "auto"));
        rain.setPainted(style.painted());
        rain.setAmbient(animated);
        host.addView(rain, new FrameLayout.LayoutParams(-1, -1));
    }

    /** For the debug dump: which sky is showing and how the animation is doing. */
    String debug() {
        SkyRenderer.Input in = currentSky();
        return (scene != null ? "scene(fixed)" : "live") + " " + (in != null && in.weather != null ? in.weather.signature() : "?")
                + " | season: " + seasonShown() + " | style: " + style.debug()
                + " | " + (rain == null ? "no surface" : rain.lastStats + " | " + rain.fx.debug());
    }

    /** The live sky surface, whose real pixels the text colour is measured against (null before attach). */
    android.view.SurfaceView surface() {
        return rain;
    }

    /** The cards and dock on screen (rounded rectangles in sky coordinates): rain splashes on them, snow settles on them. */
    void setObstacles(float[] rects) {
        if (rain != null) rain.setObstacles(rects);
    }

    /** Called (on the UI thread) whenever a different sky is about to be shown, e.g. to pick a readable text colour. */
    void setSkyListener(java.util.function.Consumer<SkyRenderer.Input> l) {
        skyListener = l;
    }

    /** The launcher is covered by another app: stop drawing (it resumes in {@link #resumeAnimation}). */
    void pauseAnimation() {
        if (rain != null) rain.setSuspended(true);
    }

    void resumeAnimation() {
        if (rain != null) rain.setSuspended(false);
    }

    /** The launcher has been in the background: free the moving layers' memory; start() builds them again. */
    void releaseMemory() {
        if (rain != null) rain.releaseLayers();
        lastSig = null;
    }

    boolean isAnimated() {
        return animated;
    }

    /** Settings: clouds, mist and stars on or off (rain and snow still animate when it rains or snows). */
    void setAnimated(boolean on) {
        animated = on;
        prefs.putStr("sky_anim", on ? "1" : "0");
        rain.setAmbient(on);
        lastSig = null;
        maybeRender(true); // rebuilds the sky with or without its moving layers
    }

    /** Settings: classic or Van Gogh. Rebuilds the sky and crossfades to it. */
    void setStyle(String name) {
        prefs.putStr("sky_style", name);
        style = SkyStyle.byName(name);
        rain.setPainted(style.painted());
        lastSig = null;
        maybeRender(true);
    }

    static int seasonIndex(String name) {
        for (int i = 0; i < 4; i++) if (SkyModel.seasonName(i).equalsIgnoreCase(name)) return i;
        return -1; // "auto": follow the calendar
    }

    /** Settings: "auto" follows the calendar; spring, summer, autumn or winter forces that season's nature. */
    void setSeason(String name) {
        prefs.putStr("sky_season", name);
        SkyRenderer.seasonOverride = seasonIndex(name);
        lastSig = null;
        maybeRender(true);
    }

    String seasonSetting() {
        return prefs.str("sky_season", "auto");
    }

    /** The season the sky is showing now, for the dump and the Settings row. */
    String seasonShown() {
        return SkyModel.seasonName(SkyRenderer.currentSeason(lat));
    }

    String styleName() {
        return style.name();
    }

    /** Rain/snow/storm layer follows what the sky is showing (live weather or a fixed scene). */
    private void applyPrecip(SkyRenderer.Input in) {
        WeatherState w = in.weather;
        SkyView.Mode m = w.snow ? SkyView.Mode.SNOW : w.rain ? SkyView.Mode.RAIN : SkyView.Mode.NONE;
        rain.configure(m, w.intensity(), w.storm, SkyModel.daylight(in.sunElev));
    }

    /** The sky for right now (a fixed scene, or the live sun and weather); the Weather card paints with it. */
    SkyRenderer.Input currentSky() {
        return scene != null ? scene : SkyRenderer.Input.at(System.currentTimeMillis(), lat, lon, weather);
    }

    void debugStrike(boolean hold) {
        rain.debugStrike(hold);
    }

    Bitmap currentBitmap() {
        return shown;
    }

    WeatherState weather() {
        return weather;
    }

    String place() {
        return place;
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        if (started) return;
        started = true;
        loadStored();
        listener.onWeather(weather, place);
        maybeRender(true);
        long age = System.currentTimeMillis() - storedTime();
        ui.postDelayed(refresh, age >= REFRESH_MS || weather.days.isEmpty() || weather.hours.isEmpty() ? 0 : REFRESH_MS - age);
    }

    void stop() {
        started = false;
        ui.removeCallbacks(refresh);
    }

    /** Called once a minute. */
    void tick() {
        maybeRender(false);
    }

    /** Location changed in settings: refetch right away. */
    void locationChanged() {
        loadStored();
        lastSig = null;
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    /** Fixed scene (settings) or null for the live sky. */
    void setScene(SkyRenderer.Input s) {
        scene = s;
        maybeRender(true);
    }

    // ------------------------------------------------------------------ state

    private long storedTime() {
        String s = prefs.str("weather", null);
        return s == null ? 0 : WeatherParse.decodeTime(s);
    }

    private void loadStored() {
        String loc = prefs.str("loc", null);
        if (loc != null) {
            try {
                String[] p = loc.split("\\|");
                lat = Double.parseDouble(p[0]);
                lon = Double.parseDouble(p[1]);
                place = p.length > 2 ? p[2] : "";
            } catch (RuntimeException ignored) {
            }
        }
        String ws = prefs.str("weather", null);
        WeatherState w = ws == null ? null : WeatherParse.decode(ws);
        if (w != null) weather = w;
    }

    static String encodeLoc(double lat, double lon, String name) {
        return String.format(Locale.ENGLISH, "%.4f|%.4f|%s", lat, lon, name.replace("|", " "));
    }

    // ------------------------------------------------------------------ network

    private void fetchWeather() {
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureLocation();
                    String f = Http.get(String.format(Locale.ENGLISH,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,weather_code,cloud_cover,precipitation,visibility,"
                                    + "apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m"
                                    + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
                                    + "uv_index_max,sunrise,sunset"
                                    + "&forecast_days=7&forecast_hours=26&timezone=auto",
                            lat, lon));
                    final WeatherState w = WeatherParse.parseForecast(f);
                    w.applyAqi(fetchAqi());
                    try {
                        String m = Http.get("https://aviationweather.gov/api/data/metar?format=json&bbox="
                                + WeatherParse.metarBbox(lat, lon));
                        WeatherParse.applyMetar(w, m, lat, lon, System.currentTimeMillis() / 1000, 40, 90);
                    } catch (Exception ignored) {
                    }
                    prefs.putStr("weather", WeatherParse.encode(w, System.currentTimeMillis()));
                    ui.post(new Runnable() {
                        @Override public void run() {
                            weather = w;
                            listener.onWeather(w, place);
                            maybeRender(false);
                        }
                    });
                } catch (Exception e) {
                    // Offline or API down: keep the last known sky; the next 15 min tick retries.
                }
            }
        });
    }

    /**
     * A real monitoring-station reading when a WAQI token is configured (local.properties: waqi.token), else
     * Open-Meteo's modelled estimate, which is flagged as an estimate because it under-reads over north India.
     */
    private Aqi.Reading fetchAqi() {
        long now = System.currentTimeMillis() / 1000;
        if (!BuildConfig.WAQI_TOKEN.isEmpty()) {
            try {
                Aqi.Reading r = WeatherParse.parseWaqi(Http.get(String.format(Locale.ENGLISH,
                        "https://api.waqi.info/feed/geo:%.4f;%.4f/?token=%s", lat, lon, BuildConfig.WAQI_TOKEN)),
                        lat, lon, now);
                if (r != null) return r;
            } catch (Exception ignored) {
                // fall through to the model
            }
        }
        try {
            return WeatherParse.parseModelAqi(Http.get(String.format(Locale.ENGLISH,
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=us_aqi,pm2_5,pm10",
                    lat, lon)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Uses the saved location, or makes an IP-based guess once. */
    private void ensureLocation() throws Exception {
        // A saved location without a country code predates it: look the guess up again to fill it in.
        if (prefs.str("loc", null) != null
                && (!prefs.str("cc", "").isEmpty() || prefs.str("loc_manual", "").equals("1"))) return;
        WeatherParse.Place p = WeatherParse.parseIpLocation(Http.get("https://ipwho.is/"));
        lat = p.lat;
        lon = p.lon;
        place = p.name;
        prefs.putStr("loc", encodeLoc(p.lat, p.lon, p.name));
        prefs.putStr("cc", p.cc);
    }

    // ------------------------------------------------------------------ rendering

    private void maybeRender(boolean force) {
        if (under == null) return;
        final SkyRenderer.Input in = scene != null ? scene
                : SkyRenderer.Input.at(System.currentTimeMillis(), lat, lon, weather);
        in.season = SkyRenderer.currentSeason(lat);
        applyPrecip(in);
        double sunKey = Math.max(-19, in.sunElev); // deep night looks the same, don't redraw for it
        String sig = in.weather.signature() + (scene != null ? "S" : "") + "|s" + in.season;
        double moonElev = in.moonElev;
        boolean moonUp = moonElev > -4 && in.sunElev < 4;
        boolean changed = force || lastSig == null || !sig.equals(lastSig)
                || Math.abs(sunKey - lastSunKey) >= 0.5
                || (moonUp && Math.abs(moonElev - lastMoonElev) >= 0.5);
        if (!changed) return;
        if (skyListener != null) skyListener.accept(in);
        if (rendering || fading) {
            pending = true;
            return;
        }
        rendering = true;
        lastSig = sig;
        lastSunKey = sunKey;
        lastMoonElev = moonElev;
        final boolean withMotion = animated;
        worker.execute(new Runnable() {
            @Override public void run() {
                // The still picture feeds the ImageViews and the frosted glass; the animated sky is its still base
                // plus the moving layers (or, with motion off, the still picture itself for the rain surface).
                // Animated: only the still base is drawn here (the moving layers are built separately), and that same
                // picture also feeds the ImageViews and the frosted glass, which never show the moving parts anyway.
                // Motion off: one full still picture. Either way a sky change costs a single render.
                final Bitmap b;
                final SkyView.Frame frame;
                if (withMotion) {
                    b = style.renderBase(in);
                    frame = new SkyView.Frame(b, style.layers(in));
                } else {
                    b = style.render(in);
                    frame = new SkyView.Frame(b, null);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        rendering = false;
                        show(b, frame);
                    }
                });
            }
        });
    }

    private void show(final Bitmap b, SkyView.Frame frame) {
        if (shown == null) {
            shown = b;
            under.setImageBitmap(b);
            rain.setFrame(frame, false);
            Glass.get().setWallpaper(b);
            return;
        }
        fading = true;
        rain.setFrame(frame, true); // the sky surface hides the ImageViews, so it crossfades by itself
        over.setImageBitmap(b);
        over.setAlpha(0f);
        over.animate().alpha(1f).setDuration(3000).withEndAction(new Runnable() {
            @Override public void run() {
                shown = b;
                under.setImageBitmap(b);
                Glass.get().setWallpaper(b);
                over.setAlpha(0f);
                over.setImageDrawable(null);
                // The previous bitmap is not recycled: the rain surface may still be drawing it. GC frees it.
                fading = false;
                if (pending) {
                    pending = false;
                    maybeRender(false);
                }
            }
        }).start();
    }
}

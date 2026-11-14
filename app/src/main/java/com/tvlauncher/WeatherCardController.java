package com.tvlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import java.util.Locale;

/**
 * Weather card: temperature, conditions and the week's forecast, painted in the colours of the current sky. Fed by the
 * same data as the header, so it needs no network or timers of its own.
 */
final class WeatherCardController implements CardController {
    private final Activity act;
    private final CardView card;
    private WeatherState weather;
    private String place = "";
    private double sunElev = 30;
    private boolean morning;
    private final WeekStrip week;
    private final DayArc arc;

    WeatherCardController(Activity act, CardView card) {
        this.act = act;
        this.card = card;
        week = new WeekStrip(act);
        card.setExtra(week);
        arc = new DayArc(act);
        arc.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        float dp = act.getResources().getDisplayMetrics().density;
        lp.topMargin = Math.round(40 * dp);
        lp.rightMargin = Math.round(14 * dp);
        card.addView(arc, lp);
        card.setOnClickListener(v -> showDetails());
        card.setTitleStyle(44, 200, 1);
        card.setAccent("WEATHER", 0, Color.argb(220, 255, 255, 255));
        card.setTitle("--");
        card.setSubtitle("Waiting for the first reading");
        card.setImage(sky(), false);
    }

    /** For the debug dump: the week as the strip draws it, e.g. "Today 27/19 c0, Sun 28/18 c1". */
    String weekSummary() {
        return week.drawnSummary() + " | " + arc.drawnSummary();
    }

    @Override public String debug() {
        if (weather == null) return "no weather yet";
        WeatherState w = weather;
        return String.format(Locale.ENGLISH, "code=%d cloud=%d%% vis=%.0fm precip=%.1f aqi=%d(%s) days=%d hours=%d feels=%.0f hum=%d wind=%.0f uv=%.0f metar=%s sunElev=%.0f",
                w.code, w.cloud, w.visibilityM, w.precip, w.aqi, w.aqiStation ? "station" : "model", w.days.size(),
                w.hours.size(), w.feelsC, w.humidity, w.windKmh, w.uv, w.metarStation, sunElev);
    }

    @Override public CardView view() { return card; }

    @Override public void setSize(int widthPx, int heightPx) {}

    @Override public void start() { render(); }

    @Override public void stop() {}

    /** Called when new weather arrives and once a minute (the sun moves, so the card's sky does too). */
    void update(WeatherState w, String place, double sunElevation, boolean morning) {
        this.weather = w;
        this.place = place == null ? "" : place;
        this.sunElev = sunElevation;
        this.morning = morning;
        render();
    }

    private GradientDrawable sky() {
        WeatherState w = weather != null ? weather : new WeatherState();
        int[] c = SkyModel.weatherSky(sunElev, morning, w);
        // darkened until white text on it reaches 4.5:1, so the card stays readable on a bright sky
        int top = Contrast.darkenFor(c[0], 4.5), bottom = Contrast.darkenFor(SkyModel.mix(c[0], c[1], 0.8), 4.5);
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF000000 | top, 0xFF000000 | bottom});
    }

    private void render() {
        if (weather == null) return;
        WeatherState w = weather;
        card.setAccent((place.isEmpty() ? "WEATHER" : place.split(",")[0].trim()).toUpperCase(Locale.ENGLISH), 0,
                Color.argb(225, 255, 255, 255));
        card.setTitle(Double.isNaN(w.tempC) ? "--" : Math.round(w.tempC) + "°");
        WeatherState.Day today = w.days.isEmpty() ? null : w.days.get(0);
        card.setSubtitle(titleCase(w.description()) + (today == null ? "" : "  ·  H:" + today.max + "°  L:" + today.min + "°"));
        week.set(w.days, w.hours, w.tempC);
        arc.set(w.sunriseMin, w.sunsetMin);
        card.setFooter(0, 0, null);
        card.setImage(sky(), false);
    }

    static String compass(int deg) {
        String[] n = {"North", "North-east", "East", "South-east", "South", "South-west", "West", "North-west"};
        return "From the " + n[((deg % 360 + 360) % 360 + 22) / 45 % 8].toLowerCase(Locale.ENGLISH);
    }

    static String uvText(double uv) {
        String c = uv < 3 ? "Low" : uv < 6 ? "Moderate" : uv < 8 ? "High" : uv < 11 ? "Very high" : "Extreme";
        return String.format(Locale.ENGLISH, "%.0f  %s", uv, c);
    }

    static String clock(int min) {
        return String.format(Locale.ENGLISH, "%d:%02d", min / 60, min % 60);
    }

    private static String titleCase(String caps) {
        String l = caps.toLowerCase(Locale.ENGLISH);
        return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }

    private void showDetails() {
        if (weather == null) return;
        WeatherState w = weather;
        GlassDialog dlg = new GlassDialog(act, 560).title("Weather" + (place.isEmpty() ? "" : " · " + place));
        dlg.row("Temperature", null, w.temperatureText() == null ? "--" : w.temperatureText(), null, true);
        dlg.row("Conditions", null, titleCase(w.description()), null, true);
        if (!Double.isNaN(w.feelsC)) dlg.row("Feels like", null, Math.round(w.feelsC) + "°C", null, true);
        if (w.humidity >= 0) dlg.row("Humidity", null, w.humidity + "%", null, true);
        if (!Double.isNaN(w.windKmh)) dlg.row("Wind", w.windDir < 0 ? null : compass(w.windDir),
                Math.round(w.windKmh) + " km/h", null, true);
        if (!Double.isNaN(w.uv)) dlg.row("UV index", null, uvText(w.uv), null, true);
        if (w.sunriseMin >= 0 && w.sunsetMin >= 0) dlg.row("Sunrise / sunset", null,
                clock(w.sunriseMin) + " / " + clock(w.sunsetMin), null, true);
        dlg.row("Cloud cover", null, w.cloud + "%", null, true);
        dlg.row("Visibility", null, String.format(Locale.ENGLISH, "%.1f km", w.visibilityM / 1000.0), null, true);
        for (WeatherState.Day dy : w.days) {
            dlg.row(dy.name, dy.rainPct > 0 ? dy.rainPct + "% chance of rain" : null, dy.max + "° / " + dy.min + "°", null, true);
        }
        dlg.section("Air quality (India CPCB scale)");
        dlg.row("AQI", w.aqiStation ? "From the nearest monitoring station" : "Modelled estimate: can read low in north India",
                w.aqi < 0 ? "--" : (w.aqiStation ? "" : "~") + w.aqi + "  " + w.aqiCategory, null, true);
        if (!Double.isNaN(w.pm25)) dlg.row("PM2.5", null, String.format(Locale.ENGLISH, "%.0f µg/m³", w.pm25), null, true);
        if (!Double.isNaN(w.pm10)) dlg.row("PM10", null, String.format(Locale.ENGLISH, "%.0f µg/m³", w.pm10), null, true);
        if (!w.aqiSource.isEmpty()) dlg.row("Source", null, w.aqiSource, null, true);
        if (w.metarStation != null) dlg.row("Airport observation", "Overrode the model for rain or fog", w.metarStation, null, true);
        dlg.button("Close", null, false);
        dlg.show();
    }
}

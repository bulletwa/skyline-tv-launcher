package com.tvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** JSON -> model. No Android classes besides org.json, so it runs in plain unit tests. */
final class WeatherParse {
    private WeatherParse() {}

    static final class Place {
        final String name, cc; // cc = ISO country code, may be empty
        final double lat, lon;
        Place(String name, double lat, double lon, String cc) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.cc = cc == null ? "" : cc;
        }
    }

    private static double num(JSONObject o, String k, double def) {
        return o.isNull(k) ? def : o.optDouble(k, def);
    }

    static WeatherState parseForecast(String json) throws JSONException {
        JSONObject c = new JSONObject(json).getJSONObject("current");
        WeatherState w = new WeatherState();
        w.tempC = num(c, "temperature_2m", Double.NaN);
        w.code = (int) num(c, "weather_code", 0);
        w.cloud = (int) num(c, "cloud_cover", 0);
        w.precip = num(c, "precipitation", 0);
        w.visibilityM = num(c, "visibility", 24000);
        w.derive();
        w.days = parseDays(json);
        w.feelsC = num(c, "apparent_temperature", Double.NaN);
        w.humidity = (int) num(c, "relative_humidity_2m", -1);
        w.windKmh = num(c, "wind_speed_10m", Double.NaN);
        w.windDir = (int) num(c, "wind_direction_10m", -1);
        parseExtras(json, w);
        return w;
    }

    /** Hourly strip, UV and sun times; all optional so a partial response still gives a card. */
    private static void parseExtras(String json, WeatherState w) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject d = root.optJSONObject("daily");
            if (d != null) {
                JSONArray uv = d.optJSONArray("uv_index_max"), sr = d.optJSONArray("sunrise"),
                        ss = d.optJSONArray("sunset");
                if (uv != null && !uv.isNull(0)) w.uv = uv.getDouble(0);
                if (sr != null && !sr.isNull(0)) w.sunriseMin = minuteOfDay(sr.getString(0));
                if (ss != null && !ss.isNull(0)) w.sunsetMin = minuteOfDay(ss.getString(0));
            }
            JSONObject h = root.optJSONObject("hourly");
            if (h != null) {
                JSONArray t = h.getJSONArray("time"), temp = h.getJSONArray("temperature_2m"),
                        c = h.getJSONArray("weather_code");
                JSONArray pr = h.optJSONArray("precipitation_probability"), isDay = h.optJSONArray("is_day");
                String now = root.getJSONObject("current").optString("time", "");
                int start = 0;
                for (int i = 0; i < t.length(); i++) { // first hour at or after the current one
                    if (now.isEmpty() || t.getString(i).compareTo(now.substring(0, Math.min(13, now.length()))) >= 0) {
                        start = i;
                        break;
                    }
                }
                for (int i = start; i < t.length() && w.hours.size() < 24; i++) {
                    if (temp.isNull(i) || c.isNull(i)) continue;
                    int hr = Integer.parseInt(t.getString(i).substring(11, 13));
                    w.hours.add(new WeatherState.Hour(hr, c.getInt(i), (int) Math.round(temp.getDouble(i)),
                            pr == null || pr.isNull(i) ? 0 : pr.getInt(i),
                            isDay == null || isDay.isNull(i) || isDay.getInt(i) == 1));
                }
            }
        } catch (JSONException | RuntimeException ignored) {
            w.hours.clear();
        }
    }

    /** "2024-05-01T05:48" -> minutes after midnight. */
    private static int minuteOfDay(String iso) {
        return Integer.parseInt(iso.substring(11, 13)) * 60 + Integer.parseInt(iso.substring(14, 16));
    }

    /** The "daily" block of the forecast; empty when missing or malformed. */
    static List<WeatherState.Day> parseDays(String json) {
        List<WeatherState.Day> out = new ArrayList<>();
        try {
            JSONObject d = new JSONObject(json).getJSONObject("daily");
            JSONArray t = d.getJSONArray("time"), c = d.getJSONArray("weather_code"),
                    hi = d.getJSONArray("temperature_2m_max"), lo = d.getJSONArray("temperature_2m_min");
            JSONArray pr = d.optJSONArray("precipitation_probability_max");
            for (int i = 0; i < t.length() && i < 7; i++) {
                if (c.isNull(i) || hi.isNull(i) || lo.isNull(i)) continue;
                String name = java.time.LocalDate.parse(t.getString(i)).getDayOfWeek()
                        .getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
                out.add(new WeatherState.Day(name, c.getInt(i), (int) Math.round(hi.getDouble(i)),
                        (int) Math.round(lo.getDouble(i)), pr == null || pr.isNull(i) ? 0 : pr.getInt(i)));
            }
        } catch (JSONException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /** Open-Meteo's modelled air quality. It under-reads badly over north India, so it is marked as an estimate. */
    static Aqi.Reading parseModelAqi(String json) {
        try {
            JSONObject c = new JSONObject(json).getJSONObject("current");
            double pm25 = num(c, "pm2_5", Double.NaN), pm10 = num(c, "pm10", Double.NaN);
            int v = Aqi.cpcb(pm25, pm10);
            return v < 0 ? null : new Aqi.Reading(v, "Modelled estimate", false, pm25, pm10);
        } catch (JSONException e) {
            return null;
        }
    }

    private static final int MAX_STATION_KM = 60, MAX_STATION_AGE_S = 6 * 3600;

    /**
     * A WAQI monitoring-station feed. WAQI reports each pollutant as a US-scale sub-index, so the PM
     * concentrations are recovered from it and the CPCB index is computed from those. Rejects errors, stations
     * more than 60 km away (the demo token answers with Shanghai), readings older than 6 hours and feeds
     * without PM data.
     */
    static Aqi.Reading parseWaqi(String json, double lat, double lon, long nowSec) {
        try {
            JSONObject root = new JSONObject(json);
            if (!"ok".equals(root.optString("status"))) return null;
            JSONObject data = root.getJSONObject("data");
            JSONObject city = data.optJSONObject("city");
            if (city != null && city.optJSONArray("geo") != null && city.getJSONArray("geo").length() == 2) {
                double sLat = city.getJSONArray("geo").getDouble(0), sLon = city.getJSONArray("geo").getDouble(1);
                if (distanceKm(lat, lon, sLat, sLon) > MAX_STATION_KM) return null;
            }
            JSONObject time = data.optJSONObject("time");
            if (time != null && time.has("v") && nowSec - time.optLong("v") > MAX_STATION_AGE_S) return null;
            JSONObject iaqi = data.optJSONObject("iaqi");
            if (iaqi == null) return null;
            double pm25 = Double.NaN, pm10 = Double.NaN;
            if (iaqi.optJSONObject("pm25") != null) pm25 = Aqi.usPm25FromIndex(iaqi.getJSONObject("pm25").optDouble("v", Double.NaN));
            if (iaqi.optJSONObject("pm10") != null) pm10 = Aqi.usPm10FromIndex(iaqi.getJSONObject("pm10").optDouble("v", Double.NaN));
            int v = Aqi.cpcb(pm25, pm10);
            if (v < 0) return null;
            String name = city != null ? city.optString("name", "Monitoring station") : "Monitoring station";
            return new Aqi.Reading(v, name, true, pm25, pm10);
        } catch (JSONException e) {
            return null;
        }
    }

    /** ipwho.is style response. */
    static Place parseIpLocation(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.optBoolean("success", true)) throw new JSONException("lookup failed");
        String city = o.optString("city", "");
        String cc = o.optString("country_code", "");
        String name = city.isEmpty() ? o.optString("country", "Unknown") : city + (cc.isEmpty() ? "" : ", " + cc);
        return new Place(name, o.getDouble("latitude"), o.getDouble("longitude"), cc);
    }

    /** Open-Meteo geocoding search results. */
    static List<Place> parseGeocoding(String json) throws JSONException {
        List<Place> out = new ArrayList<>();
        JSONArray a = new JSONObject(json).optJSONArray("results");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            StringBuilder n = new StringBuilder(r.optString("name"));
            String admin = r.optString("admin1", "");
            if (!admin.isEmpty() && !admin.equals(r.optString("name"))) n.append(", ").append(admin);
            String cc = r.optString("country_code", "");
            if (!cc.isEmpty()) n.append(", ").append(cc);
            out.add(new Place(n.toString(), r.getDouble("latitude"), r.getDouble("longitude"), cc));
        }
        return out;
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** aviationweather.gov bounding box around a point (~40 km). */
    static String metarBbox(double lat, double lon) {
        double dLat = 0.40, dLon = 0.40 / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        return String.format(Locale.ENGLISH, "%.3f,%.3f,%.3f,%.3f", lat - dLat, lon - dLon, lat + dLat, lon + dLon);
    }

    /**
     * Lets the nearest fresh airport observation override the model for rain, storms, snow and fog.
     * Never clears model precipitation (models miss local rain, not the other way round).
     *
     * @return true when an observation changed the state
     */
    static boolean applyMetar(WeatherState w, String json, double lat, double lon, long nowSec, int maxKm, int maxAgeMin) {
        try {
            JSONArray a = new JSONArray(json);
            JSONObject best = null;
            double bestKm = Double.MAX_VALUE;
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.getJSONObject(i);
                if (!m.has("lat") || !m.has("lon") || !m.has("obsTime")) continue;
                if (nowSec - m.getLong("obsTime") > maxAgeMin * 60L) continue;
                double km = distanceKm(lat, lon, m.getDouble("lat"), m.getDouble("lon"));
                if (km <= maxKm && km < bestKm) {
                    best = m;
                    bestKm = km;
                }
            }
            if (best == null) return false;
            String wx = best.optString("wxString", "");
            boolean changed = false;
            boolean heavy = wx.contains("+");
            int code = -1;
            if (wx.contains("TS")) code = 95;
            else if (wx.contains("SN") || wx.contains("SG")) code = heavy ? 75 : 71;
            else if (wx.contains("SH")) code = heavy ? 82 : 80;
            else if (wx.contains("RA")) code = heavy ? 65 : wx.contains("-") ? 61 : 63;
            else if (wx.contains("DZ")) code = 51;
            else if (wx.contains("FG")) code = 45;
            if (code >= 0) {
                if (code != 45 || !w.rain) w.code = code;
                if (code != 45) w.cloud = Math.max(w.cloud, 80);
                changed = true;
            }
            if (wx.contains("FG") || wx.contains("BR")) {
                double miles = parseMiles(best.opt("visib"));
                if (miles >= 0) {
                    w.visibilityM = Math.min(w.visibilityM, miles * 1609.34);
                    changed = true;
                }
            }
            if (changed) {
                w.metarStation = best.optString("icaoId", null);
                w.derive();
            }
            return changed;
        } catch (JSONException e) {
            return false;
        }
    }

    /** METAR visibility is statute miles, possibly the string "10+". */
    private static double parseMiles(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).replace("+", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ persistence

    static String encode(WeatherState w, long fetchedAtMs) {
        return String.format(Locale.ENGLISH, "%d,%d,%s,%s,%s,%d,%s,%d,%d,%s,%s,%s,%s,%s", w.code, w.cloud,
                Double.isNaN(w.tempC) ? "nan" : String.valueOf(w.tempC), w.precip, w.visibilityM, w.aqi,
                w.metarStation == null ? "-" : w.metarStation, fetchedAtMs,
                w.aqiStation ? 1 : 0, Double.isNaN(w.pm25) ? "nan" : String.valueOf(w.pm25),
                Double.isNaN(w.pm10) ? "nan" : String.valueOf(w.pm10),
                w.aqiSource.isEmpty() ? "-" : w.aqiSource.replace(',', ';'), encodeDays(w.days),
                encodeExtras(w));
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "n" : String.valueOf(v);
    }

    private static double unnum(String s) {
        return s.equals("n") ? Double.NaN : Double.parseDouble(s);
    }

    /** feels;humidity;wind;windDir;uv;sunrise;sunset;hours(h:code:temp:pop:day/...) with ';' and no commas. */
    private static String encodeExtras(WeatherState w) {
        StringBuilder h = new StringBuilder();
        for (WeatherState.Hour x : w.hours) {
            if (h.length() > 0) h.append('/');
            h.append(x.hour).append(':').append(x.code).append(':').append(x.temp).append(':')
                    .append(x.rainPct).append(':').append(x.day ? 1 : 0);
        }
        return num(w.feelsC) + ";" + w.humidity + ";" + num(w.windKmh) + ";" + w.windDir + ";" + num(w.uv)
                + ";" + w.sunriseMin + ";" + w.sunsetMin + ";" + (h.length() == 0 ? "-" : h);
    }

    private static void decodeExtras(String s, WeatherState w) {
        String[] f = s.split(";");
        if (f.length < 8) return;
        w.feelsC = unnum(f[0]);
        w.humidity = Integer.parseInt(f[1]);
        w.windKmh = unnum(f[2]);
        w.windDir = Integer.parseInt(f[3]);
        w.uv = unnum(f[4]);
        w.sunriseMin = Integer.parseInt(f[5]);
        w.sunsetMin = Integer.parseInt(f[6]);
        if (!f[7].equals("-")) {
            for (String part : f[7].split("/")) {
                String[] x = part.split(":");
                w.hours.add(new WeatherState.Hour(Integer.parseInt(x[0]), Integer.parseInt(x[1]),
                        Integer.parseInt(x[2]), Integer.parseInt(x[3]), x[4].equals("1")));
            }
        }
    }

    private static String encodeDays(List<WeatherState.Day> days) {
        if (days.isEmpty()) return "-";
        StringBuilder b = new StringBuilder();
        for (WeatherState.Day d : days) {
            if (b.length() > 0) b.append('/');
            b.append(d.name).append(':').append(d.code).append(':').append(d.max).append(':').append(d.min)
                    .append(':').append(d.rainPct);
        }
        return b.toString();
    }

    private static List<WeatherState.Day> decodeDays(String s) {
        List<WeatherState.Day> out = new ArrayList<>();
        if (s.equals("-")) return out;
        for (String part : s.split("/")) {
            String[] f = part.split(":");
            out.add(new WeatherState.Day(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                    Integer.parseInt(f[3]), Integer.parseInt(f[4])));
        }
        return out;
    }

    /** Returns null when the string is not valid. fetchedAt is read back via {@link #decodeTime}. */
    static WeatherState decode(String s) {
        try {
            String[] p = s.split(",");
            WeatherState w = new WeatherState();
            w.code = Integer.parseInt(p[0]);
            w.cloud = Integer.parseInt(p[1]);
            w.tempC = p[2].equals("nan") ? Double.NaN : Double.parseDouble(p[2]);
            w.precip = Double.parseDouble(p[3]);
            w.visibilityM = Double.parseDouble(p[4]);
            w.aqi = Integer.parseInt(p[5]);
            w.metarStation = p[6].equals("-") ? null : p[6];
            if (p.length >= 12) { // air-quality details were added later; older saved weather lacks them
                w.aqiStation = p[8].equals("1");
                w.pm25 = p[9].equals("nan") ? Double.NaN : Double.parseDouble(p[9]);
                w.pm10 = p[10].equals("nan") ? Double.NaN : Double.parseDouble(p[10]);
                w.aqiSource = p[11].equals("-") ? "" : p[11];
            }
            if (p.length >= 13) {
                try {
                    w.days = decodeDays(p[12]);
                } catch (RuntimeException e) {
                    w.days = new ArrayList<>();
                }
            }
            if (p.length >= 14) {
                try {
                    decodeExtras(p[13], w);
                } catch (RuntimeException e) {
                    w.hours.clear();
                }
            }
            if (w.aqi >= 0) w.aqiCategory = Aqi.cpcbCategory(w.aqi);
            w.derive();
            return w;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static long decodeTime(String s) {
        try {
            String[] p = s.split(",");
            return Long.parseLong(p[7]);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

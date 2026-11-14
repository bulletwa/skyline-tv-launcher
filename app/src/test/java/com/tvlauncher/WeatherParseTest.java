package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class WeatherParseTest {
    private static final String FORECAST = "{\"latitude\":18.52,\"current\":{\"time\":\"2026-10-30T20:45\","
            + "\"temperature_2m\":23.9,\"weather_code\":1,\"cloud_cover\":28,\"precipitation\":0.00,"
            + "\"visibility\":9800.00,\"is_day\":0}}";
    // Real aviationweather.gov response shape (Delhi, 15:00Z = epoch 1793372400).
    private static final String METAR = "[{\"icaoId\":\"VIDD\",\"obsTime\":1793372400,\"visib\":0.93,\"wxString\":\"BR\","
            + "\"lat\":28.583,\"lon\":77.211,\"cover\":\"BKN\"},"
            + "{\"icaoId\":\"VIDP\",\"obsTime\":1793372400,\"visib\":\"10+\",\"lat\":28.567,\"lon\":77.117}]";
    private static final long NOW = 1793372400L + 30 * 60;

    @Test
    public void parsesForecast() throws Exception {
        WeatherState w = WeatherParse.parseForecast(FORECAST);
        assertEquals(23.9, w.tempC, 0.01);
        assertEquals(1, w.code);
        assertEquals(28, w.cloud);
        assertEquals(9800, w.visibilityM, 0.1);
        assertEquals("24°C", w.temperatureText());
        assertFalse(w.rain);
    }

    @Test
    public void parsesIpLocation() throws Exception {
        WeatherParse.Place p = WeatherParse.parseIpLocation(
                "{\"success\":true,\"city\":\"Delhi\",\"country_code\":\"IN\",\"latitude\":28.65,\"longitude\":77.23}");
        assertEquals("Delhi, IN", p.name);
        assertEquals(28.65, p.lat, 0.001);
    }

    @Test
    public void parsesGeocodingResults() throws Exception {
        List<WeatherParse.Place> r = WeatherParse.parseGeocoding("{\"results\":[{\"name\":\"Pune\","
                + "\"latitude\":18.51,\"longitude\":73.85,\"admin1\":\"Maharashtra\",\"country_code\":\"IN\"}]}");
        assertEquals(1, r.size());
        assertEquals("Pune, Maharashtra, IN", r.get(0).name);
        assertTrue(WeatherParse.parseGeocoding("{}").isEmpty());
    }

    @Test
    public void distanceAndBbox() {
        assertEquals(111.2, WeatherParse.distanceKm(0, 0, 1, 0), 0.5);
        assertTrue(WeatherParse.distanceKm(28.65, 77.23, 28.583, 77.211) < 10);
        String[] b = WeatherParse.metarBbox(28.65, 77.23).split(",");
        assertEquals(4, b.length);
        assertTrue(Double.parseDouble(b[0]) < 28.65 && Double.parseDouble(b[2]) > 28.65);
    }

    @Test
    public void metarMistLowersVisibility() {
        WeatherState w = new WeatherState();
        assertTrue(WeatherParse.applyMetar(w, METAR, 28.65, 77.23, NOW, 40, 90));
        assertEquals("VIDD", w.metarStation); // nearest of the two
        assertEquals(1497, w.visibilityM, 5);
        assertTrue(w.fogAmount() > 0);
    }

    @Test
    public void metarRainOverridesDryModel() {
        String rainy = "[{\"icaoId\":\"VABB\",\"obsTime\":1793372400,\"wxString\":\"+RA\",\"lat\":19.1,\"lon\":72.87}]";
        WeatherState w = new WeatherState();
        w.cloud = 10;
        assertTrue(WeatherParse.applyMetar(w, rainy, 19.07, 72.87, NOW, 40, 90));
        assertTrue(w.rain);
        assertEquals(65, w.code);
        assertTrue(w.cloud >= 80);
    }

    @Test
    public void metarThunderstormAndFog() {
        WeatherState w = new WeatherState();
        WeatherParse.applyMetar(w, "[{\"icaoId\":\"X\",\"obsTime\":1793372400,\"wxString\":\"VCTS\",\"lat\":1,\"lon\":1}]",
                1, 1, NOW, 40, 90);
        assertTrue(w.storm);
        WeatherState f = new WeatherState();
        WeatherParse.applyMetar(f, "[{\"icaoId\":\"Y\",\"obsTime\":1793372400,\"wxString\":\"FG\",\"visib\":0.2,\"lat\":1,\"lon\":1}]",
                1, 1, NOW, 40, 90);
        assertTrue(f.fog);
        assertEquals(45, f.code);
    }

    @Test
    public void metarIgnoredWhenStaleOrFar() {
        String rainy = "[{\"icaoId\":\"VABB\",\"obsTime\":1793372400,\"wxString\":\"RA\",\"lat\":19.1,\"lon\":72.87}]";
        WeatherState w = new WeatherState();
        assertFalse(WeatherParse.applyMetar(w, rainy, 19.07, 72.87, 1793372400L + 95 * 60, 40, 90)); // 95 min old
        assertFalse(WeatherParse.applyMetar(w, rainy, 28.65, 77.23, NOW, 40, 90)); // 1100+ km away
        assertFalse(w.rain);
    }

    @Test
    public void metarNeverClearsModelRain() {
        String dry = "[{\"icaoId\":\"VIDD\",\"obsTime\":1793372400,\"lat\":28.58,\"lon\":77.21,\"visib\":\"10+\"}]";
        WeatherState w = new WeatherState();
        w.code = 63;
        w.derive();
        assertFalse(WeatherParse.applyMetar(w, dry, 28.65, 77.23, NOW, 40, 90));
        assertTrue(w.rain);
    }

    @Test
    public void metarGarbageIsHarmless() {
        assertFalse(WeatherParse.applyMetar(new WeatherState(), "not json", 0, 0, NOW, 40, 90));
        assertFalse(WeatherParse.applyMetar(new WeatherState(), "[]", 0, 0, NOW, 40, 90));
    }

    @Test
    public void encodeDecodeRoundTrip() throws Exception {
        WeatherState w = WeatherParse.parseForecast(FORECAST);
        w.aqi = 77;
        w.metarStation = "VIDD";
        String s = WeatherParse.encode(w, 12345L);
        WeatherState r = WeatherParse.decode(s);
        assertEquals(w.signature(), r.signature());
        assertEquals(23.9, r.tempC, 0.01);
        assertEquals("VIDD", r.metarStation);
        assertEquals(12345L, WeatherParse.decodeTime(s));
        assertNull(WeatherParse.decode("oops"));
    }

    @Test
    public void extrasSurviveEncodeDecodeAndOldStringsStillDecode() {
        WeatherState w = new WeatherState();
        w.tempC = 20;
        w.feelsC = 22.5;
        w.humidity = 55;
        w.windKmh = 12;
        w.windDir = 270;
        w.uv = 7;
        w.sunriseMin = 350;
        w.sunsetMin = 1100;
        w.hours.add(new WeatherState.Hour(14, 2, 21, 10, true));
        w.hours.add(new WeatherState.Hour(23, 0, 15, 0, false));
        WeatherState r = WeatherParse.decode(WeatherParse.encode(w, 1L));
        assertEquals(22.5, r.feelsC, 0.01);
        assertEquals(55, r.humidity);
        assertEquals(270, r.windDir);
        assertEquals(1100, r.sunsetMin);
        assertEquals(2, r.hours.size());
        assertEquals(15, r.hours.get(1).temp);
        assertEquals(false, r.hours.get(1).day);
        WeatherState old = WeatherParse.decode("0,10,20.0,0.0,24000.0,-1,-,1,0,nan,nan,-,-");
        assertEquals(0, old.hours.size());
        assertEquals(-1, old.humidity);
    }
}

package com.tvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AqiTest {
    private static final double NAN = Double.NaN;
    private static final long NOW = 1_793_375_000L;

    // ------------------------------------------------------------ CPCB maths

    @Test
    public void cpcbBreakpointsMatchTheNationalTable() {
        assertEquals(0, Aqi.cpcb(0, NAN));
        assertEquals(50, Aqi.cpcb(30, NAN));
        assertEquals(100, Aqi.cpcb(60, NAN));
        assertEquals(200, Aqi.cpcb(90, NAN));
        assertEquals(300, Aqi.cpcb(120, NAN));
        assertEquals(400, Aqi.cpcb(250, NAN));
        assertEquals(500, Aqi.cpcb(9999, NAN));
        assertEquals(50, Aqi.cpcb(NAN, 50));
        assertEquals(200, Aqi.cpcb(NAN, 250));
    }

    @Test
    public void cpcbInterpolatesAndTakesTheWorstPollutant() {
        assertEquals(88, Aqi.cpcb(52.7, 60.3)); // the model's numbers for Ghaziabad that evening
        assertEquals(300, Aqi.cpcb(30, 350)); // PM10 dominates
        assertEquals(-1, Aqi.cpcb(NAN, NAN));
        assertEquals(-1, Aqi.cpcb(-5, NAN));
    }

    @Test
    public void realDelhiWinterNumbersAreVeryPoorOrWorse() {
        assertTrue(Aqi.cpcb(180, 320) >= 301);
        assertEquals("Very Poor", Aqi.cpcbCategory(Aqi.cpcb(180, 320)));
    }

    @Test
    public void categoriesCoverTheScale() {
        assertEquals("Good", Aqi.cpcbCategory(50));
        assertEquals("Satisfactory", Aqi.cpcbCategory(51));
        assertEquals("Moderate", Aqi.cpcbCategory(200));
        assertEquals("Poor", Aqi.cpcbCategory(201));
        assertEquals("Very Poor", Aqi.cpcbCategory(400));
        assertEquals("Severe", Aqi.cpcbCategory(401));
    }

    @Test
    public void usIndexInversionRoundTripsThroughTheEpaBands() {
        assertEquals(12.0, Aqi.usPm25FromIndex(50), 0.01);
        assertEquals(35.4, Aqi.usPm25FromIndex(100), 0.01);
        assertEquals(150.4, Aqi.usPm25FromIndex(200), 0.01);
        assertEquals(54.0, Aqi.usPm10FromIndex(50), 0.01);
        assertEquals(254.0, Aqi.usPm10FromIndex(150), 0.01);
        assertTrue(Double.isNaN(Aqi.usPm25FromIndex(NAN)));
        for (int idx = 0; idx <= 500; idx += 25) { // monotonic
            assertTrue(Aqi.usPm25FromIndex(idx + 25) >= Aqi.usPm25FromIndex(idx));
        }
    }

    // ------------------------------------------------------------ WAQI

    private static String waqi(String status, int pm25, Integer pm10, double lat, double lon, long ageSec) {
        return "{\"status\":\"" + status + "\",\"data\":{\"aqi\":" + pm25 + ",\"city\":{\"geo\":[" + lat + "," + lon
                + "],\"name\":\"Anand Vihar, Delhi, India\"},\"iaqi\":{\"pm25\":{\"v\":" + pm25 + "}"
                + (pm10 == null ? "" : ",\"pm10\":{\"v\":" + pm10 + "}") + ",\"t\":{\"v\":18}},"
                + "\"time\":{\"v\":" + (NOW - ageSec) + "}}}";
    }

    @Test
    public void waqiStationReadingIsConvertedToCpcb() {
        // US sub-index 200 for PM2.5 = 150.4 µg/m³; CPCB band 120-250 -> 300 + 30.4/130*100 = 323
        Aqi.Reading r = WeatherParse.parseWaqi(waqi("ok", 200, null, 28.65, 77.31, 600), 28.6653, 77.4392, NOW);
        assertNotNull(r);
        assertTrue(r.station);
        assertEquals("Anand Vihar, Delhi, India", r.source);
        assertEquals(150.4, r.pm25, 0.1);
        assertEquals(323, r.value, 1);
        assertEquals("Very Poor", r.category);
    }

    @Test
    public void waqiUsesTheWorseOfPm25AndPm10() {
        Aqi.Reading r = WeatherParse.parseWaqi(waqi("ok", 100, 300, 28.65, 77.31, 0), 28.6653, 77.4392, NOW);
        assertNotNull(r);
        assertEquals(Aqi.cpcb(Aqi.usPm25FromIndex(100), Aqi.usPm10FromIndex(300)), r.value);
        assertTrue(r.value > Aqi.cpcb(Aqi.usPm25FromIndex(100), Double.NaN));
    }

    @Test
    public void waqiRejectsErrorsFarStationsAndStaleData() {
        assertNull(WeatherParse.parseWaqi(waqi("error", 100, null, 28.65, 77.31, 0), 28.6653, 77.4392, NOW));
        assertNull(WeatherParse.parseWaqi(waqi("ok", 100, null, 31.2, 121.4, 0), 28.6653, 77.4392, NOW)); // Shanghai
        assertNull(WeatherParse.parseWaqi(waqi("ok", 100, null, 28.65, 77.31, 8 * 3600), 28.6653, 77.4392, NOW)); // 8 h old
        assertNull(WeatherParse.parseWaqi("{\"status\":\"ok\",\"data\":{\"aqi\":55,\"iaqi\":{\"o3\":{\"v\":55}}}}", 1, 1, NOW)); // no PM
        assertNull(WeatherParse.parseWaqi("not json", 1, 1, NOW));
    }

    // ------------------------------------------------------------ model fallback

    @Test
    public void modelReadingIsMarkedAsAnEstimate() {
        String json = "{\"current\":{\"us_aqi\":71,\"pm2_5\":52.7,\"pm10\":60.3}}";
        Aqi.Reading r = WeatherParse.parseModelAqi(json);
        assertNotNull(r);
        assertFalse(r.station);
        assertEquals("Modelled estimate", r.source);
        assertEquals(88, r.value);
        assertEquals(52.7, r.pm25, 0.001);
    }

    @Test
    public void modelReadingToleratesNulls() {
        assertNull(WeatherParse.parseModelAqi("{\"current\":{\"us_aqi\":null,\"pm2_5\":null,\"pm10\":null}}"));
        Aqi.Reading r = WeatherParse.parseModelAqi("{\"current\":{\"pm2_5\":null,\"pm10\":120}}");
        assertNotNull(r);
        assertEquals(Aqi.cpcb(NAN, 120), r.value);
        assertNull(WeatherParse.parseModelAqi("garbage"));
    }

    // ------------------------------------------------------------ state + persistence

    @Test
    public void aqiSurvivesEncodeDecode() {
        WeatherState w = new WeatherState();
        w.applyAqi(new Aqi.Reading(212, "Anand Vihar, Delhi", true, 110.5, 240));
        WeatherState r = WeatherParse.decode(WeatherParse.encode(w, 5L));
        assertNotNull(r);
        assertEquals(212, r.aqi);
        assertTrue(r.aqiStation);
        assertEquals("Anand Vihar; Delhi", r.aqiSource.replace(",", ";")); // commas are escaped in storage
        assertEquals(110.5, r.pm25, 0.001);
        assertEquals("Poor", r.aqiCategory);
    }

    @Test
    public void oldStoredWeatherStillDecodes() {
        WeatherState r = WeatherParse.decode("1,39,23.6,0.0,7820.0,106,-,1793375774366");
        assertNotNull(r);
        assertEquals(106, r.aqi);
        assertFalse(r.aqiStation);
    }
}

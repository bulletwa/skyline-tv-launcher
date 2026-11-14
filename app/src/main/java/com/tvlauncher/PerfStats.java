package com.tvlauncher;

import java.util.Locale;

/**
 * Pure parsing and formatting for the performance overlay. There is no CPU line: an app cannot read /proc/stat on Android 11,
 * this TV pins the CPU clock at its maximum, and `dumpsys cpuinfo` is a frozen snapshot.
 */
final class PerfStats {
    private PerfStats() {}

    static final class Mem {
        long totalKb, availKb, swapTotalKb, swapFreeKb;
    }

    /** Reads MemTotal, MemAvailable, SwapTotal and SwapFree (kB) from /proc/meminfo text. */
    static Mem parseMeminfo(String text) {
        Mem m = new Mem();
        if (text == null) return m;
        for (String line : text.split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c);
            long v = number(line.substring(c + 1));
            if (k.equals("MemTotal")) m.totalKb = v;
            else if (k.equals("MemAvailable")) m.availKb = v;
            else if (k.equals("SwapTotal")) m.swapTotalKb = v;
            else if (k.equals("SwapFree")) m.swapFreeKb = v;
        }
        return m;
    }

    private static long number(String s) {
        StringBuilder d = new StringBuilder();
        for (char ch : s.toCharArray()) if (ch >= '0' && ch <= '9') d.append(ch); else if (d.length() > 0) break;
        try {
            return d.length() == 0 ? 0 : Long.parseLong(d.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String ramLine(Mem m) {
        if (m.totalKb <= 0) return "RAM  --";
        long used = m.totalKb - m.availKb;
        return String.format(Locale.ENGLISH, "RAM  %.2f / %.2f GB", used / 1048576.0, m.totalKb / 1048576.0);
    }

    static String freeLine(Mem m) {
        if (m.totalKb <= 0) return "Free --";
        return String.format(Locale.ENGLISH, "Free %d MB  Swap %d MB", m.availKb / 1024, (m.swapTotalKb - m.swapFreeKb) / 1024);
    }

    static String screenLine(int w, int h, float hz) {
        return String.format(Locale.ENGLISH, "%dx%d  %.0f Hz", w, h, hz);
    }

    static String fpsLine(int fps) {
        return fps < 0 ? "FPS  --" : "FPS  " + fps;
    }

    /** FPS from a frame count over an interval; -1 when the interval is too short to trust. */
    static int fps(int frames, long elapsedMs) {
        return elapsedMs < 200 ? -1 : (int) Math.round(frames * 1000.0 / elapsedMs);
    }

    /** Bytes per second from two TrafficStats counters; -1 when a counter is unsupported or went backwards. */
    static long bytesPerSec(long prevBytes, long curBytes, long elapsedMs) {
        if (prevBytes < 0 || curBytes < prevBytes || elapsedMs < 200) return -1;
        return (curBytes - prevBytes) * 1000 / elapsedMs;
    }

    /** "3.2 Mb/s" style; megabits per second, the unit Wi-Fi and ISPs use. */
    static String mbps(long bytesPerSec) {
        if (bytesPerSec < 0) return "--";
        double mb = bytesPerSec * 8 / 1e6;
        return String.format(Locale.ENGLISH, mb < 10 ? "%.2f" : "%.1f", mb);
    }

    static String rateLine(long rxBytesPerSec, long txBytesPerSec) {
        return "DL " + mbps(rxBytesPerSec) + "  UL " + mbps(txBytesPerSec) + " Mb/s";
    }

    /** kind: "wifi", "ethernet" or anything else for no network. rssi dBm and link Mbps are ignored when unknown (<= 0 link). */
    static String linkLine(String kind, int rssiDbm, int linkMbps) {
        if ("ethernet".equals(kind)) return "Ethernet";
        if (!"wifi".equals(kind)) return "Offline";
        StringBuilder sb = new StringBuilder("Wi-Fi");
        if (rssiDbm < 0 && rssiDbm > -127) sb.append("  ").append(rssiDbm).append(" dBm");
        if (linkMbps > 0) sb.append("  ").append(linkMbps).append(" Mbps");
        return sb.toString();
    }
}

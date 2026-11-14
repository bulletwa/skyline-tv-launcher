package com.tvlauncher;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML/text cleaning and feed date handling. Pure Java. */
final class TextUtil {
    private TextUtil() {}

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    /** Strips tags, decodes entities and collapses whitespace. */
    static String clean(String s) {
        if (s == null) return "";
        String t = TAGS.matcher(s).replaceAll(" ");
        t = decodeEntities(t);
        // entities can themselves encode tags (&lt;p&gt;): strip once more
        if (t.indexOf('<') >= 0) t = TAGS.matcher(t).replaceAll(" ");
        return WS.matcher(t).replaceAll(" ").trim();
    }

    static String decodeEntities(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            if (e.startsWith("#x") || e.startsWith("#X")) r = codePoint(e.substring(2), 16, m.group());
            else if (e.startsWith("#")) r = codePoint(e.substring(1), 10, m.group());
            else switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                case "nbsp": r = " "; break;
                case "ndash": r = "–"; break;
                case "mdash": r = "—"; break;
                case "lsquo": r = "‘"; break;
                case "rsquo": r = "’"; break;
                case "ldquo": r = "“"; break;
                case "rdquo": r = "”"; break;
                case "hellip": r = "…"; break;
                default: r = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix, String orig) {
        try {
            int cp = Integer.parseInt(digits, radix);
            return new String(Character.toChars(cp));
        } catch (RuntimeException e) {
            return orig;
        }
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        if (cut < max / 2) cut = max;
        return s.substring(0, cut).trim() + "…";
    }

    // ------------------------------------------------------------------ dates

    private static final String[] RFC822 = {
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, d MMM yyyy HH:mm:ss z", "EEE, dd MMM yyyy HH:mm Z", "EEE, d MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss", "EEE MMM dd HH:mm:ss z yyyy"
    };

    /** Epoch millis for RFC 822 or ISO 8601 text, 0 when it cannot be read. */
    static long parseDate(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.isEmpty()) return 0;
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException ignored) {
        }
        for (String f : RFC822) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f, Locale.ENGLISH);
                df.setTimeZone(TimeZone.getTimeZone("GMT"));
                df.setLenient(true);
                return df.parse(s).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }

    /** "just now", "12m ago", "2h ago", "3d ago", "2w ago"; empty when the date is unknown. */
    static String ago(long publishedMs, long nowMs) {
        if (publishedMs <= 0) return "";
        long s = (nowMs - publishedMs) / 1000;
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 7) return d + "d ago";
        return (d / 7) + "w ago";
    }
}

package com.tvlauncher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal HTTP GET: manual redirects, http->https upgrade (cleartext is blocked), browser User-Agent. */
final class Http {
    static final String UA = "Mozilla/5.0 (Linux; Android 11; Smart TV Pro) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private Http() {}

    static String upgrade(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    static String get(String url) throws IOException {
        return new String(getBytes(url, null, "application/json, text/xml, */*"), "UTF-8");
    }

    static byte[] getBytes(String url, String referer, String accept) throws IOException {
        return fetch(url, referer, accept, MAX_BYTES, false);
    }

    /** Some CDNs answer 403 without a browser User-Agent, a Referer and an image Accept header. */
    static byte[] getBytes(String url, String referer, String accept, int maxBytes) throws IOException {
        return fetch(url, referer, accept, maxBytes, false);
    }

    /** The first {@code maxBytes} of a page (enough for its <head>); a longer page is cut, not an error. */
    static String getPagePrefix(String url, int maxBytes) throws IOException {
        return new String(fetch(url, null, "text/html,application/xhtml+xml,*/*;q=0.8", maxBytes, true), "UTF-8");
    }

    private static byte[] fetch(String url, String referer, String accept, int maxBytes, boolean truncate) throws IOException {
        String cur = upgrade(url);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", accept);
            if (referer != null) c.setRequestProperty("Referer", referer);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without Location");
                    cur = upgrade(new URL(new URL(cur), loc).toString());
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code + " for " + cur);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > maxBytes) {
                            if (truncate) break;
                            throw new IOException("response too large");
                        }
                    }
                    return out.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }
}

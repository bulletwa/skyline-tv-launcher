package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One shared picture cache (~12 MB) for every card and dialog. Pictures are decoded at the size they are
 * shown, as RGB_565, and failures are retried no sooner than 10 minutes later.
 */
final class ImageLoader {
    interface Callback {
        /** @param b null when the picture could not be loaded. Always called on the UI thread. */
        void onImage(Bitmap b);
    }

    private static final long RETRY_MS = 10 * 60 * 1000L;
    private static final ImageLoader INSTANCE = new ImageLoader();

    static ImageLoader get() {
        return INSTANCE;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Pools.fixed(3, "image");
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final Map<String, Long> failed = new ConcurrentHashMap<>();
    private final Map<String, List<Callback>> waiting = new HashMap<>(); // UI thread only

    /** The launcher went to the background: give the picture memory back (visible tiles keep their own copy). */
    void trim() {
        cache.evictAll();
        failed.clear();
    }

    private ImageLoader() {}

    private static String key(String url, int w) {
        return url + "@" + w;
    }

    Bitmap peek(String url, int w) {
        return url == null ? null : cache.get(key(url, w));
    }

    boolean recentlyFailed(String url, int w) {
        Long t = failed.get(key(url, w));
        return t != null && System.currentTimeMillis() - t < RETRY_MS;
    }

    /** Loads (or finds in cache) and calls back on the UI thread. Safe to call repeatedly. */
    void load(final String url, final String referer, final int w, final int h, final Callback cb) {
        final String k = key(url, w);
        Bitmap hit = cache.get(k);
        if (hit != null) {
            cb.onImage(hit);
            return;
        }
        if (recentlyFailed(url, w)) {
            cb.onImage(null);
            return;
        }
        List<Callback> list = waiting.get(k);
        if (list != null) {
            list.add(cb);
            return;
        }
        list = new ArrayList<>();
        list.add(cb);
        waiting.put(k, list);
        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    byte[] data = Http.getBytes(url, referer, "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                            Http.MAX_IMAGE_BYTES);
                    b = decode(data, w, h);
                } catch (Exception ignored) {
                } catch (OutOfMemoryError oom) {
                    cache.evictAll();
                }
                final Bitmap result = b;
                if (result != null) {
                    cache.put(k, result);
                    failed.remove(k);
                } else {
                    failed.put(k, System.currentTimeMillis());
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        List<Callback> cbs = waiting.remove(k);
                        if (cbs != null) for (Callback c : cbs) c.onImage(result);
                    }
                });
            }
        });
    }

    /** Warm the cache without caring about the result. */
    void preload(String url, String referer, int w, int h) {
        if (url == null || cache.get(key(url, w)) != null || recentlyFailed(url, w)) return;
        load(url, referer, w, h, new Callback() {
            @Override public void onImage(Bitmap b) {}
        });
    }

    /** Decodes so the result is about w x h (never much larger), RGB_565. */
    static Bitmap decode(byte[] data, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 40_000_000L) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}

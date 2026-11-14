package com.tvlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is playing, from any app that publishes a media session (Apple Music, Spotify, YouTube Music, ...).
 * Entirely event driven: no polling, no timers. Needs Notification access (see {@link MediaListenerService}).
 */
final class NowPlayingController implements CardController {
    private final Activity act;
    private final CardView card;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MediaSessionManager msm;
    private final ComponentName listener;
    private final ImageLoader loader = ImageLoader.get();
    private int imgW = 420, imgH = 220;

    private boolean started, registered, hasAccess = true;
    private final List<MediaController> controllers = new ArrayList<>();
    private MediaController current;
    private String shownKey = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) {
                    adopt(list);
                }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { render(); }
        @Override public void onMetadataChanged(MediaMetadata m) { render(); }
        @Override public void onSessionDestroyed() { ui.post(new Runnable() { @Override public void run() { connect(); } }); }
    };

    NowPlayingController(Activity act, CardView card) {
        this.act = act;
        this.card = card;
        msm = (MediaSessionManager) act.getSystemService(Activity.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(act, MediaListenerService.class);
        card.setOnClickListener(v -> onClick());
        showNothing();
    }

    @Override public CardView view() { return card; }

    @Override
    public void setSize(int widthPx, int heightPx) {
        imgW = widthPx;
        imgH = heightPx;
    }

    @Override
    public void start() {
        started = true;
        connect(); // also re-checks access, which the user may have granted while we were away
    }

    @Override
    public void stop() {
        started = false;
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        current = null;
        if (registered) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
            registered = false;
        }
    }

    // ------------------------------------------------------------------ sessions

    private void connect() {
        if (!started) return;
        try {
            List<MediaController> list = msm.getActiveSessions(listener);
            hasAccess = true;
            if (!registered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listener, ui);
                registered = true;
            }
            adopt(list);
        } catch (SecurityException e) {
            hasAccess = false;
            showNoAccess();
        }
    }

    private void adopt(List<MediaController> list) {
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(callback, ui);
                controllers.add(c);
            }
        }
        render();
    }

    @Override public String debug() {
        return "access=" + hasAccess + " sessions=" + controllers.size() + " showing="
                + (current == null ? "nothing" : current.getPackageName() + (playing(current) ? " playing" : " paused"))
                + " trackKey=" + (shownKey.isEmpty() ? "-" : shownKey.length() > 30 ? shownKey.substring(0, 30) : shownKey);
    }

    private static boolean playing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
    }

    private static boolean hasTitle(MediaController c) {
        MediaMetadata m = c.getMetadata();
        return m != null && (!isEmpty(m.getString(MediaMetadata.METADATA_KEY_TITLE))
                || !isEmpty(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)));
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** The one that is playing, else the first paused one that knows what it was playing. */
    private MediaController pick() {
        for (MediaController c : controllers) if (playing(c) && hasTitle(c)) return c;
        for (MediaController c : controllers) if (hasTitle(c)) return c;
        return null;
    }

    // ------------------------------------------------------------------ showing

    private void showNothing() {
        card.setAccent("NOW PLAYING", 0, Color.argb(220, 255, 255, 255));
        card.setTitleStyle(22, 400, 2);
        card.setTitle("Nothing playing");
        card.setSubtitle("Start music in any app");
        card.setFooter(0, 0, null);
        card.setImage(new TileDrawable(act, 0x26304A, "♪"), false);
        shownKey = "";
    }

    private void showNoAccess() {
        card.setAccent("NOW PLAYING", 0, Color.argb(220, 255, 255, 255));
        card.setTitleStyle(22, 400, 2);
        card.setTitle("Allow notification access");
        card.setSubtitle("Needed to see what is playing");
        card.setFooter(0, 0, "Press Center to see how");
        card.setImage(new TileDrawable(act, 0x4A3030, "!"), false);
        shownKey = "";
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = act.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void render() {
        if (!started || !hasAccess) return;
        final MediaController c = pick();
        current = c;
        if (c == null) {
            showNothing();
            return;
        }
        MediaMetadata m = c.getMetadata();
        String title = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        boolean on = playing(c);
        String app = appLabel(c.getPackageName());

        card.setAccent((on ? "NOW PLAYING" : "PAUSED") + "  •  " + app.toUpperCase(Locale.ENGLISH),
                on ? Color.rgb(60, 200, 120) : 0, Color.argb(225, 255, 255, 255));
        card.setTitleStyle(22, 400, 2);
        card.setTitle(title);
        card.setSubtitle(artist + (isEmpty(album) || isEmpty(artist) ? "" : "  •  " + album));
        card.setFooter(0, 0, on ? "Press Center to pause" : "Press Center to play");

        String key = c.getPackageName() + "|" + title + "|" + artist;
        if (!key.equals(shownKey)) { // new track: change the picture; a pause/play keeps it
            shownKey = key;
            card.setImage(new TileDrawable(act, 0x26304A, firstLetter(app)), true);
            loadArt(m, key);
        }
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (!isEmpty(s)) return s;
        return "";
    }

    private static String firstLetter(String s) {
        return s.isEmpty() ? "♪" : s.substring(0, 1).toUpperCase(Locale.ENGLISH);
    }

    private void loadArt(MediaMetadata m, final String key) {
        Bitmap bmp = firstBitmap(m);
        if (bmp != null) {
            setArt(bmp, key);
            return;
        }
        String uri = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                m.getString(MediaMetadata.METADATA_KEY_ART_URI), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        if (uri.isEmpty()) return;
        final Uri u = Uri.parse(uri);
        if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
            loader.load(uri, null, 480, 480, new ImageLoader.Callback() {
                @Override public void onImage(Bitmap b) {
                    if (b != null) setArt(b, key);
                }
            });
        } else {
            new Thread(new Runnable() {
                @Override public void run() {
                    try (InputStream in = act.getContentResolver().openInputStream(u)) {
                        final Bitmap b = in == null ? null : BitmapFactory.decodeStream(in);
                        if (b != null) ui.post(new Runnable() { @Override public void run() { setArt(b, key); } });
                    } catch (Exception ignored) {
                        // an app that does not share its artwork just keeps the tile
                    }
                }
            }).start();
        }
    }

    private static Bitmap firstBitmap(MediaMetadata m) {
        String[] keys = {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON};
        for (String k : keys) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private void setArt(Bitmap b, String key) {
        if (!key.equals(shownKey)) return; // the track changed while this loaded
        Drawable d = new NowPlayingArt(b);
        card.setImage(d, true);
    }

    // ------------------------------------------------------------------ input

    private void onClick() {
        if (!hasAccess) {
            showHowToAllow();
            return;
        }
        MediaController c = current;
        if (c == null) {
            Toast.makeText(act, "Nothing is playing", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playing(c)) c.getTransportControls().pause();
        else c.getTransportControls().play();
    }

    private void showHowToAllow() {
        GlassDialog dlg = new GlassDialog(act, 620).title("Allow notification access");
        dlg.message("Android only shows what is playing to apps that have Notification access. The launcher reads "
                + "nothing else: no notifications are read or stored.");
        dlg.message("Open Settings, find Apps, Special app access, Notification access, and switch on TV Launcher.");
        dlg.button("Open settings", new Runnable() {
            @Override public void run() {
                try {
                    act.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception e) {
                    try {
                        act.startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e2) {
                        Toast.makeText(act, "Couldn't open Settings", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }, false);
        dlg.button("Close", null, false);
        dlg.show();
    }
}

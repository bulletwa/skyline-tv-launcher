package com.tvlauncher;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app read other apps' media sessions (what is playing) when it
 * holds "Notification access", i.e. is an enabled notification listener, so the Now Playing card needs this
 * component to exist and be enabled. No notification content is read or stored.
 */
public class MediaListenerService extends NotificationListenerService {
}

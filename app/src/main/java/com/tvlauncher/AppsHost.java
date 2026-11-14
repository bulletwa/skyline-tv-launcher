package com.tvlauncher;

import java.util.List;
import java.util.Set;

/** What the settings dialogs need from the home screen. */
interface AppsHost {
    List<AppEntry> apps();

    /** Current dock order (package names). */
    List<String> dock();

    /** Save a new dock order; null restores the automatic default. */
    void saveDock(List<String> pkgs);

    Set<String> hidden();

    void saveHidden(Set<String> hidden);

    /** The Cards settings changed: rebuild the card row. */
    void cardsChanged();

    /** Which HDMI input is the PlayStation changed: refresh its dock entry and card. */
    void playStationChanged();
}

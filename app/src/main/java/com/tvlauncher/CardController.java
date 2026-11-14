package com.tvlauncher;

/**
 * One home-screen card. To add a card: implement this, add its id to {@link CardsConfig}, and register it in
 * {@link HomeCards}. Only the cards that are switched on are started, so hidden cards cost nothing.
 */
interface CardController {
    CardView view();

    /** The picture size in px this card is shown at, so pictures can be decoded at that size. */
    void setSize(int widthPx, int heightPx);

    /** The home screen is visible and this card is on screen. */
    void start();

    /** The home screen went away or this card was switched off: stop timers, listeners and network work. */
    void stop();

    /** One short line of this card's logic state for the debug dump (what it is showing, why). */
    default String debug() { return ""; }
}

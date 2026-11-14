package com.tvlauncher;

import java.util.List;

/** Small list helpers for the dock customizer. Pure Java. */
final class ListOps {
    private ListOps() {}

    /** Moves the element at {@code from} to {@code to} (clamped). Returns the index it ended up at. */
    static <T> int move(List<T> list, int from, int to) {
        if (from < 0 || from >= list.size()) return from;
        to = Math.max(0, Math.min(list.size() - 1, to));
        if (to == from) return from;
        T item = list.remove(from);
        list.add(to, item);
        return to;
    }
}

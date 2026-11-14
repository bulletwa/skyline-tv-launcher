package com.tvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class ListOpsTest {
    private static ArrayList<String> abcd() {
        return new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
    }

    @Test
    public void movesDownAndUp() {
        ArrayList<String> l = abcd();
        assertEquals(2, ListOps.move(l, 1, 2));
        assertEquals(Arrays.asList("a", "c", "b", "d"), l);
        assertEquals(0, ListOps.move(l, 2, 0));
        assertEquals(Arrays.asList("b", "a", "c", "d"), l);
    }

    @Test
    public void clampsAtTheEnds() {
        ArrayList<String> l = abcd();
        assertEquals(0, ListOps.move(l, 0, -1));
        assertEquals(abcd(), l);
        assertEquals(3, ListOps.move(l, 3, 4));
        assertEquals(abcd(), l);
        assertEquals(3, ListOps.move(l, 0, 99));
        assertEquals(Arrays.asList("b", "c", "d", "a"), l);
    }

    @Test
    public void badIndexIsIgnored() {
        ArrayList<String> l = abcd();
        assertEquals(-1, ListOps.move(l, -1, 2));
        assertEquals(7, ListOps.move(l, 7, 2));
        assertEquals(abcd(), l);
    }
}

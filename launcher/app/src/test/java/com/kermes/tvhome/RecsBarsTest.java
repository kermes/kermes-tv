package com.kermes.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;

import org.junit.Test;

/** The recommendation rating bars, one title each side of every line. -1 is a score the title lacks. */
public class RecsBarsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    public void filmNeedsCriticsAndAudience() {
        assertTrue(RecsBars.film(75, -1, 6.5, 5000, 0, 0, false, false));
        assertFalse(RecsBars.film(74, 64, 9.0, 90000, 0, 0, false, false));   // audience loves it, critics do not
        assertFalse(RecsBars.film(90, 80, 6.4, 90000, 0, 0, false, false));
        // Too few IMDb votes: TMDB's score counts instead.
        assertTrue(RecsBars.film(-1, 65, 5.0, 4999, 7.0, 300, false, false));
        assertFalse(RecsBars.film(-1, 65, 9.0, 4999, 7.0, 299, false, false));
    }

    @Test
    public void filmWithoutCriticsNeedsTheHigherAudienceBar() {
        assertTrue(RecsBars.film(-1, -1, 7.5, 5000, 0, 0, false, false));
        assertFalse(RecsBars.film(-1, -1, 7.4, 5000, 0, 0, false, false));
        assertTrue(RecsBars.film(-1, -1, -1, -1, 7.6, 500, false, false));
    }

    @Test
    public void worthATryAndCatalogueAreStricter() {
        assertTrue(RecsBars.film(93, 80, 7.5, 20000, 0, 0, true, false));
        assertFalse(RecsBars.film(92, 99, 9.0, 90000, 0, 0, true, false));
        assertTrue(RecsBars.film(-1, -1, 7.5, 50000, 0, 0, false, true));
        assertFalse(RecsBars.film(79, 90, 8.5, 90000, 0, 0, false, true));   // one bad Rotten Tomatoes score
    }

    @Test
    public void seriesGoByAudience() {
        assertTrue(RecsBars.series(-1, 7.6, 3000, 0, 0, false));
        assertFalse(RecsBars.series(79, 9.0, 90000, 0, 0, false));
        assertTrue(RecsBars.series(-1, -1, 2999, 8.0, 300, false));
        assertFalse(RecsBars.series(-1, 8.3, 90000, 0, 0, true));
        assertTrue(RecsBars.series(-1, 8.4, 50000, 0, 0, true));
    }

    @Test
    public void justInIsRecentAndNotBad() {
        assertTrue(RecsBars.justIn("2026-10-26", TODAY, 6.0, 1000, 0, 0, -1));
        assertFalse(RecsBars.justIn("2026-10-25", TODAY, 9.0, 90000, 0, 0, -1));   // 15 days ago
        assertFalse(RecsBars.justIn("2026-11-10", TODAY, 9.0, 90000, 0, 0, -1));   // not out yet
        assertFalse(RecsBars.justIn("2026-11-01", TODAY, 9.0, 90000, 0, 0, 39));
        assertTrue(RecsBars.justIn("2026-11-01", TODAY, -1, 10, 6.0, 20, -1));
        assertFalse(RecsBars.justIn(null, TODAY, 9.0, 90000, 0, 0, -1));
    }
}

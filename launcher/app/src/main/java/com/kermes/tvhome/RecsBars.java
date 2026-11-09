package com.kermes.tvhome;

import java.time.LocalDate;

/** The rating bars a title must clear for the recommendations hero, as Recs' class comment lists them. */
final class RecsBars {
    /** How recent a "just in" title is. */
    static final int JUST_IN_DAYS = 14;

    private RecsBars() {
    }

    /** A film's bar (see the class comment); -1 is a score the title does not have. */
    static boolean film(int rt, int mc, double imdb, int imdbVotes, double tmdb, int tmdbVotes,
                        boolean outside, boolean catalogue) {
        if (catalogue) return imdb >= 7.5 && imdbVotes >= 50000 && (rt < 0 || rt >= 80);
        if (outside) return rt >= 93 && mc >= 80 && imdb >= 7.5 && imdbVotes >= 20000;
        boolean byImdb = imdbVotes >= 5000;
        if (rt < 0 && mc < 0) return byImdb ? imdb >= 7.5 : tmdb >= 7.6 && tmdbVotes >= 500;
        boolean critics = rt >= 75 || mc >= 65;
        return critics && (byImdb ? imdb >= 6.5 : tmdb >= 7.0 && tmdbVotes >= 300);
    }

    /** A series' bar: OMDb has no critic score for series, so the audience's, and no Rotten Tomatoes under 80%. */
    static boolean series(int rt, double imdb, int imdbVotes, double tmdb, int tmdbVotes, boolean outside) {
        boolean audience = outside ? imdb >= 8.4 && imdbVotes >= 50000
                : imdbVotes >= 3000 ? imdb >= 7.6 : tmdb >= 8.0 && tmdbVotes >= 300;
        return audience && (rt < 0 || rt >= 80);
    }

    /** Out in the last JUST_IN_DAYS days and not bad: the bar for a "just in" place. */
    static boolean justIn(String date, LocalDate today, double imdb, int imdbVotes, double tmdb, int tmdbVotes, int rt) {
        if (date == null || date.compareTo(today.minusDays(JUST_IN_DAYS).toString()) < 0) return false;
        if (date.compareTo(today.toString()) > 0) return false;
        boolean audience = imdbVotes >= 1000 ? imdb >= 6.0 : tmdb >= 6.0 && tmdbVotes >= 20;
        return audience && (rt < 0 || rt >= 40);
    }
}

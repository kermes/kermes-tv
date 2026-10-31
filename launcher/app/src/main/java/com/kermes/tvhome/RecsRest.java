package com.kermes.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Variety for the recommendations hero (Recs): a title that was in a list rests for REST_DAYS days, so the next day's
 * list is made of others. Plain Java, so the unit tests can run it.
 */
final class RecsRest {
    /** Days a title rests after it was in a list: shown on day D, first choice again on day D + REST_DAYS. */
    static final int REST_DAYS = 4;

    private RecsRest() {
    }

    /** Films and series each get at least this many places when they have the titles, resting ones included. */
    static final int MIN_EACH = 3;

    /**
     * The list: film and series in turn, newest first (so what is just in leads), the titles not resting before any
     * resting one. Neither kind drops below MIN_EACH while it has titles: a kind short of new ones makes up the rest
     * from its resting titles, the longest-rested first, in place of the other kind's last picks. Past that the kind
     * with more new titles takes more places, with no fixed share. When too few qualify, resting titles fill the list,
     * the longest-rested first. The extras (a worth-a-try pick, a catalogue film) go to random places from the third
     * on, so the list does not have the same shape every day.
     */
    static List<Recs.Item> arrange(List<Recs.Item> films, List<Recs.Item> series, List<Recs.Item> extras,
                                   Map<String, Long> resting, Random random) {
        int room = Recs.MAX - extras.size();
        Kind f = new Kind(films, resting), s = new Kind(series, resting);
        // New titles, in turn.
        while (f.total() + s.total() < room && (f.freshLeft() || s.freshLeft())) {
            if (f.freshLeft() && (f.total() <= s.total() || !s.freshLeft())) f.fresh++;
            else s.fresh++;
        }
        // The floor: a kind short of MIN_EACH takes its resting titles, in free places or in the other kind's.
        for (Kind short_ : new Kind[]{f, s}) {
            Kind other = short_ == f ? s : f;
            while (short_.total() < MIN_EACH && short_.restLeft()) {
                if (f.total() + s.total() >= room) {
                    if (other.total() <= MIN_EACH) break;
                    other.dropLast();
                }
                short_.rest++;
            }
        }
        // Still room: resting titles of either kind, the longest-rested first.
        while (f.total() + s.total() < room && (f.restLeft() || s.restLeft())) {
            boolean film = f.restLeft() && (!s.restLeft() || resting.get(f.nextRest().id) <= resting.get(s.nextRest().id));
            (film ? f : s).rest++;
        }
        List<Recs.Item> fs = f.picked(), ss = s.picked();
        List<Recs.Item> out = new ArrayList<>();
        for (int i = 0; i < fs.size() || i < ss.size(); i++) {
            if (i < fs.size()) out.add(fs.get(i));
            if (i < ss.size()) out.add(ss.get(i));
        }
        for (Recs.Item extra : extras) {
            out.add(out.size() < 2 ? out.size() : 2 + random.nextInt(out.size() - 1), extra);
        }
        return out;
    }

    /** One kind's titles: new ones newest first, resting ones longest-rested first, and how many of each are picked. */
    private static final class Kind {
        final List<Recs.Item> freshTitles = new ArrayList<>(), restTitles = new ArrayList<>();
        int fresh, rest;

        Kind(List<Recs.Item> titles, Map<String, Long> resting) {
            for (Recs.Item item : titles) (resting.containsKey(item.id) ? restTitles : freshTitles).add(item);
            // Stable: among titles that rested equally long, newest first as before.
            restTitles.sort(Comparator.comparingLong(item -> resting.get(item.id)));
        }

        int total() {
            return fresh + rest;
        }

        boolean freshLeft() {
            return fresh < freshTitles.size();
        }

        boolean restLeft() {
            return rest < restTitles.size();
        }

        Recs.Item nextRest() {
            return restTitles.get(rest);
        }

        /** Gives up its last pick: a resting one before a new one. */
        void dropLast() {
            if (rest > 0) rest--;
            else fresh--;
        }

        List<Recs.Item> picked() {
            List<Recs.Item> out = new ArrayList<>(freshTitles.subList(0, fresh));
            out.addAll(restTitles.subList(0, rest));
            return out;
        }
    }

    /** The titles still resting on day today (epoch days): those listed in the last REST_DAYS days. */
    static Map<String, Long> rested(Map<String, Long> shownOn, long today) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Long> e : shownOn.entrySet()) {
            if (today - e.getValue() < REST_DAYS) out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}

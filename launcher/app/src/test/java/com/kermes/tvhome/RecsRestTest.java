package com.kermes.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class RecsRestTest {

    private static Recs.Item item(String id) {
        return new Recs.Item(id, id.startsWith("tv:"), false, id, "2026", "", "", "prime", null, "2026-10-01", -1, -1, 7.5);
    }

    private static List<Recs.Item> items(String prefix, int n) {
        List<Recs.Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(item(prefix + i));
        return out;
    }

    private static List<String> ids(List<Recs.Item> items) {
        List<String> out = new ArrayList<>();
        for (Recs.Item item : items) out.add(item.id);
        return out;
    }

    private static int seriesIn(List<Recs.Item> items) {
        int n = 0;
        for (Recs.Item item : items) if (item.series) n++;
        return n;
    }

    private static List<Recs.Item> arrange(List<Recs.Item> films, List<Recs.Item> series, List<Recs.Item> extras,
                                           Map<String, Long> resting) {
        return RecsRest.arrange(films, series, extras, resting, new Random(1));
    }

    @Test
    public void filmAndSeriesInTurnWithNothingResting() {
        List<Recs.Item> out = arrange(items("movie:", 6), items("tv:", 6), Collections.emptyList(),
                Collections.emptyMap());
        assertEquals(Recs.MAX, out.size());
        assertEquals(Arrays.asList("movie:0", "tv:0", "movie:1", "tv:1"), ids(out).subList(0, 4));
    }

    @Test
    public void restingTitlesGiveWayToOthers() {
        Map<String, Long> resting = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            resting.put("movie:" + i, 100L);
            resting.put("tv:" + i, 100L);
        }
        List<Recs.Item> out = arrange(items("movie:", 10), items("tv:", 10), Collections.emptyList(), resting);
        assertEquals(Recs.MAX, out.size());
        for (Recs.Item item : out) assertTrue(item.id, !resting.containsKey(item.id));
    }

    @Test
    public void restingTitlesFillWhenTooFewQualifyLongestRestedFirst() {
        Map<String, Long> resting = new HashMap<>();
        resting.put("movie:0", 102L);
        resting.put("movie:1", 100L);
        resting.put("tv:0", 101L);
        List<Recs.Item> out = arrange(items("movie:", 3), items("tv:", 2), Collections.emptyList(), resting);
        assertEquals(Arrays.asList("movie:2", "tv:1", "movie:1", "tv:0", "movie:0"), ids(out));
    }

    @Test
    public void aKindWithOnlyRestingTitlesStillGetsItsFloorLongestRestedFirst() {
        Map<String, Long> resting = new HashMap<>();
        for (int i = 0; i < 15; i++) resting.put("tv:" + i, 100L + (15 - i));   // tv:14 rested longest
        List<Recs.Item> out = arrange(items("movie:", 15), items("tv:", 15), Collections.emptyList(), resting);
        assertEquals(Recs.MAX, out.size());
        List<String> series = new ArrayList<>();
        for (Recs.Item item : out) if (item.series) series.add(item.id);
        assertEquals(Arrays.asList("tv:14", "tv:13", "tv:12"), series);
        assertEquals("movie:0", out.get(0).id);
    }

    @Test
    public void pastTheFloorTheKindWithMoreNewTitlesTakesMorePlaces() {
        List<Recs.Item> out = arrange(items("movie:", 15), items("tv:", 4), Collections.emptyList(),
                Collections.emptyMap());
        assertEquals(Recs.MAX, out.size());
        assertEquals(4, seriesIn(out));
    }

    @Test
    public void theFloorNeverTakesTheOtherKindBelowItsOwn() {
        Map<String, Long> resting = new HashMap<>();
        for (int i = 0; i < 5; i++) resting.put("tv:" + i, 100L);
        List<Recs.Item> extras = Arrays.asList(item("movie:x"), item("movie:old"));
        List<Recs.Item> out = arrange(items("movie:", 2), items("tv:", 5), extras, resting);
        assertEquals(Recs.MAX - 1, out.size());
        assertTrue(ids(out).containsAll(Arrays.asList("movie:0", "movie:1", "tv:0", "tv:1", "tv:2", "tv:3", "tv:4")));
    }

    @Test
    public void extrasGoToSomePlaceFromTheThirdOnAndTakeOnlyTheirOwnPlaces() {
        List<Recs.Item> extras = Arrays.asList(item("movie:x"), item("movie:old"));
        for (int seed = 0; seed < 50; seed++) {
            List<Recs.Item> out = RecsRest.arrange(items("movie:", 6), items("tv:", 6), extras,
                    Collections.emptyMap(), new Random(seed));
            assertEquals(Recs.MAX, out.size());
            List<String> ids = ids(out);
            assertTrue(ids.indexOf("movie:x") >= 2);
            assertTrue(ids.indexOf("movie:old") >= 2);
            assertEquals(4, seriesIn(out));
        }
    }

    @Test
    public void titlesRestForRestDaysThenComeBack() {
        Map<String, Long> shown = new HashMap<>();
        shown.put("today", 200L);
        shown.put("lastDayOfRest", 200L - (RecsRest.REST_DAYS - 1));
        shown.put("rested", 200L - RecsRest.REST_DAYS);
        Map<String, Long> resting = RecsRest.rested(shown, 200L);
        assertEquals(2, resting.size());
        assertTrue(resting.containsKey("today"));
        assertTrue(resting.containsKey("lastDayOfRest"));
    }
}

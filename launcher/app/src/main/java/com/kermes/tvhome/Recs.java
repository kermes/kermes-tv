package com.kermes.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Recommendations for the hero (recs_hero): recent films and series on the owner's own services, kept only when
 * critics and audiences both rate them well. TMDB says what is new and where it streams in recs_region (its
 * availability comes from JustWatch); OMDb adds IMDb, Rotten Tomatoes and Metacritic by IMDb id. Fetched at most
 * once every 20 hours, in the background, and cached in a file so Home shows the last list at once and offline.
 * The API keys come from work/recs.properties at build time (BuildConfig) and never enter git.
 *
 * The rules, tried on Spain's catalogue in October 2026:
 * - Newest first, film and series in turn: a film by the day it reached streaming (its digital release in
 *   recs_region, else elsewhere, else its release), a series by its new season or premiere. The owner's list keeps
 *   the last six months of those.
 * - Audience scores: IMDb when it has enough votes; TMDB only when IMDb has none, with a higher bar, because TMDB's
 *   averages run high for network procedurals (Tracker, Fire Country: TMDB 7.6+, IMDb 7.1-7.2).
 * - Film: released in the last two years and on streaming in the last year. With a critic score, Rotten Tomatoes 75% or
 *   Metacritic 65, and an audience score of IMDb 6.5 (5,000 votes) or TMDB 7.0 (300 votes). Without one, the audience
 *   bar rises to 7.5. (Tried on Spain in October 2026: the stricter one-year, 80/70/6.8 rules let 6 of 120 films through.)
 * - Catalogue: one older film on the owner's services, from random pages of the best-known ones, with IMDb 7.5
 *   (50,000 votes) and no Rotten Tomatoes score under 80%, at a random place. Up to CATALOGUE_SPARE
 *   more take the places new titles leave, before a resting title comes back.
 * - Series: new in the last six months, or a new season (5th at most) in the last 120 days; kids, news, reality, soap
 *   and talk shows left out. OMDb has no series critic score, so IMDb 7.6 (3,000 votes) or TMDB 7.6 (100 votes).
 *   The season cap is what keeps season 26 of a procedural out: their audience scores are as high as anything.
 * - Worth a try (on any service streaming in recs_region that the owner does not have: Apple TV+, Filmin, Netflix...):
 *   at most one, at a random place, and only exceptional ones: a film with Rotten Tomatoes 93%, Metacritic 80 and IMDb 7.5 (20,000
 *   votes); a series with IMDb 8.4 (50,000 votes), up to its 8th season. The hero says where it streams; OK opens that app when it is
 *   installed, and does nothing otherwise.
 * - Just in: up to MAX_JUST_IN places for a film that reached streaming, or a series that premiered or started a
 *   season, in the last JUST_IN_DAYS days, that missed the bar above but is not bad: IMDb 6.0 (1,000 votes) or TMDB
 *   6.0, and no Rotten Tomatoes score under 40%. No places of their own: as the newest they sort first among the
 *   new titles, count toward the films and series floor, and the hero says "Just in".
 * - Titles the owner marked "Not interested" (hidden, by TMDB id) never come back.
 * - Variety: a title that was in a list rests for RecsRest.REST_DAYS days, so the next day's list is made of others. Rested
 *   titles only fill the list when too few others qualify, the longest-rested first, so the hero is never empty.
 *
 * What each load did goes to logcat (adb logcat -s TvHome.Recs): logcat is a fixed-size ring in memory, so nothing
 * grows on the TV's storage.
 */
final class Recs {
    static final String SERVICES_KEY = "recs_services";
    static final String HIDDEN_KEY = "recs_hidden";
    /** Item.service for a worth-a-try service that recs_services does not list: its TMDB name after this. */
    private static final String NAME_PREFIX = "name:";
    /** Worth-a-try titles from services the owner does not have, at most. */
    private static final int MAX_OUTSIDE = 1;
    /** Older films from the owner's services, at most. */
    private static final int MAX_CATALOGUE = 1;
    /**
     * More catalogue films, used like new ones once the new titles run out, before any resting title comes back: about
     * 30 new titles qualify, and ten a day resting four days would need 40.
     */
    private static final int CATALOGUE_SPARE = 6;
    /** Recent titles below the bar, at most, and how recent. */
    private static final int MAX_JUST_IN = 3;
    private static final int JUST_IN_DAYS = 14;
    private static final Random RANDOM = new Random();
    static final int MAX = 10;
    private static final long MAX_AGE_MS = 20L * 60 * 60 * 1000;
    private static final String TAG = "TvHome.Recs";
    private static final String CACHE = "recs.json";
    /** Part of the cache's signature: a change to the rules or the item fields makes the next load fetch afresh. */
    private static final int RULES = 4;
    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280";
    private static final String TV_GENRES_LEFT_OUT = "10762|10763|10764|10766|10767";
    private static final int TIMEOUT_MS = 15000;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Recs() {
    }

    /** A streaming service from recs_services: "key|Label|tmdb provider ids|app package". */
    static final class Service {
        final String key, label, pkg;
        final Set<Integer> providers = new HashSet<>();

        Service(String spec) {
            String[] p = spec.split("\\|");
            key = p[0].trim();
            label = p[1].trim();
            for (String id : p[2].split(",")) providers.add(Integer.parseInt(id.trim()));
            pkg = p[3].trim();
        }
    }

    static final class Item {
        /** "movie:123" or "tv:456", TMDB's id: what "Not interested" remembers. */
        final String id;
        final boolean series;
        /** From a service the owner does not have: a worth-a-try pick. */
        final boolean outside;
        final String title, year, overview, backdrop, service;
        /** "Nueva temporada" and the like, or null. */
        final String note;
        /** When it came out where it counts (yyyy-MM-dd): streaming for a film, the new season or premiere for a series. */
        final String date;
        final int rottenTomatoes, metacritic;
        final double imdb;
        /** Just out and below the usual bar: one of the "just in" places. */
        final boolean justIn;

        Item(String id, boolean series, boolean outside, String title, String year, String overview, String backdrop,
             String service, String note, String date, int rottenTomatoes, int metacritic, double imdb) {
            this(id, series, outside, title, year, overview, backdrop, service, note, date, rottenTomatoes, metacritic,
                    imdb, false);
        }

        Item(String id, boolean series, boolean outside, String title, String year, String overview, String backdrop,
             String service, String note, String date, int rottenTomatoes, int metacritic, double imdb, boolean justIn) {
            this.justIn = justIn;

            this.date = date;
            this.id = id;
            this.series = series;
            this.outside = outside;
            this.title = title;
            this.year = year;
            this.overview = overview;
            this.backdrop = backdrop;
            this.service = service;
            this.note = note;
            this.rottenTomatoes = rottenTomatoes;
            this.metacritic = metacritic;
            this.imdb = imdb;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("series", series).put("outside", outside).put("title", title).put("year", year)
                    .put("overview", overview).put("backdrop", backdrop).put("service", service).put("note", note).put("date", date)
                    .put("rt", rottenTomatoes).put("mc", metacritic).put("imdb", imdb).put("justIn", justIn);
        }

        static Item fromJson(JSONObject o) {
            return new Item(o.optString("id"), o.optBoolean("series"), o.optBoolean("outside"), o.optString("title"),
                    o.optString("year"), o.optString("overview"), o.optString("backdrop"), o.optString("service"),
                    o.isNull("note") ? null : o.optString("note", null), o.optString("date"),
                    o.optInt("rt", -1), o.optInt("mc", -1), o.optDouble("imdb", -1), o.optBoolean("justIn"));
        }
    }

    static boolean configured() {
        return !BuildConfig.TMDB_TOKEN.isEmpty() && !BuildConfig.OMDB_KEY.isEmpty();
    }

    static List<Service> services(Context context) {
        List<Service> out = new ArrayList<>();
        for (String spec : context.getResources().getStringArray(R.array.recs_services)) {
            try {
                out.add(new Service(spec));
            } catch (RuntimeException ignored) {
                // a malformed line in config.xml: leave that service out
            }
        }
        return out;
    }

    /** The services the owner has (Launcher settings, Mis servicios), else recs_default_services. */
    static Set<String> chosen(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(SERVICES_KEY, null);
        if (saved != null) return new HashSet<>(saved);
        return new HashSet<>(Arrays.asList(context.getResources().getStringArray(R.array.recs_default_services)));
    }

    static void setChosen(Context context, Set<String> keys) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putStringSet(SERVICES_KEY, new HashSet<>(keys)).apply();
    }

    static Set<String> hidden(Context context) {
        return new HashSet<>(context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getStringSet(HIDDEN_KEY, Collections.emptySet()));
    }

    /**
     * "Not interested": never recommend this title again. The cached list is marked stale, so the next load fetches
     * a replacement in the background.
     */
    static void hide(Context context, Item item) {
        Set<String> hidden = hidden(context);
        hidden.add(item.id);
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putStringSet(HIDDEN_KEY, hidden).apply();
        Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            JSONObject cache = readCache(app);
            if (cache == null) return;
            try {
                cache.put("stale", true);
                write(app, cache);
            } catch (IOException | JSONException ignored) {
                // the hidden set alone still keeps the title out of the next fetch
            }
        });
    }

    /** "Other titles": the next load fetches a whole new list, the titles just shown resting like any day's. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            JSONObject cache = readCache(app);
            if (cache == null) return;
            try {
                write(app, cache.put("force", true));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "refresh not saved: " + e);
            }
        });
    }

    static Service service(Context context, String key) {
        for (Service s : services(context)) if (s.key.equals(key)) return s;
        return null;
    }

    /**
     * The service's app, at its TV start screen (TMDB gives no link into the app itself), or null when no installed
     * app is known for it: a worth-a-try title on a service with no app here only says where it streams.
     */
    static Intent intentFor(Context context, Item item) {
        Service s = service(context, item.service);
        if (s == null) return null;
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(s.pkg);
        return launch != null ? launch : pm.getLaunchIntentForPackage(s.pkg);
    }

    /** The service's name: from recs_services, or as TMDB gave it for one that is not listed there ("name:Filmin"). */
    static String label(Context context, Item item) {
        Service s = service(context, item.service);
        if (s != null) return s.label;
        return item.service.startsWith(NAME_PREFIX) ? item.service.substring(NAME_PREFIX.length()) : item.service;
    }

    /**
     * Hands back the cached list at once (when there is one), then, when it is older than 20 hours or was made for
     * other services, fetches a new one and hands that back too. Both on the main thread.
     */
    static void loadAsync(Context context, Consumer<List<Item>> done) {
        Context app = context.getApplicationContext();
        String region = app.getString(R.string.recs_region);
        Set<String> chosen = chosen(app);
        String signature = RULES + ":" + region + ":" + new TreeSet<>(chosen);
        LOADER.execute(() -> {
            JSONObject cache = readCache(app);
            Set<String> hidden = hidden(app);
            List<Item> cached = new ArrayList<>();
            for (Item item : items(cache)) if (!hidden.contains(item.id)) cached.add(item);
            boolean sameServices = cache != null && signature.equals(cache.optString("signature"));
            if (sameServices && !cached.isEmpty()) MAIN.post(() -> done.accept(cached));
            long fetchedAt = cache == null ? 0 : cache.optLong("fetched");
            long ageMs = System.currentTimeMillis() - fetchedAt;
            boolean forced = cache != null && cache.optBoolean("force");
            boolean fresh = sameServices && ageMs < MAX_AGE_MS && !cache.optBoolean("stale") && !forced;
            if (fresh || !configured()) {
                logOnce("cached list: " + cached.size() + " titles, fetched " + hours(ageMs) + " ago"
                        + (configured() ? "" : "; no API keys, never fetched"));
                return;
            }
            long today = LocalDate.now().toEpochDay();
            Map<String, Long> shown = RecsRest.rested(shownOn(cache), today);
            Map<String, Long> resting = new HashMap<>(shown);
            // A refetch before the list is due ("Not interested") replaces that title, not the whole list.
            boolean due = ageMs >= MAX_AGE_MS || forced;
            if (!due) for (Item item : cached) resting.remove(item.id);
            Log.i(TAG, "fetching: " + (forced ? "other titles asked for" : !sameServices ? "new services or rules"
                    : due ? "list " + hours(ageMs) + " old"
                    : "a title was marked not interested") + ", " + resting.size() + " titles resting");
            try {
                List<Item> fetched = fetch(app, region, chosen, hidden, resting);
                Set<String> before = new HashSet<>();
                for (Item item : cached) before.add(item.id);
                int kept = 0, fromRest = 0;
                for (Item item : fetched) {
                    if (before.contains(item.id)) kept++;
                    if (resting.containsKey(item.id)) fromRest++;
                    // Every title in the list starts its rest today, one brought back to fill included: kept at
                    // its first day, it would stay the longest-rested and fill the next list too, and the next.
                    shown.put(item.id, today);
                }
                writeCache(app, signature, fetched, shown);
                Log.i(TAG, "new list: " + fetched.size() + " titles, " + (fetched.size() - kept) + " not in the last one, "
                        + fromRest + " back from rest: " + titles(fetched));
                MAIN.post(() -> done.accept(fetched));
            } catch (IOException | JSONException | RuntimeException e) {
                // Offline or an API hiccup: the cached list stays, and the next return to Home tries again.
                Log.w(TAG, "fetch failed, the cached list stays: " + e);
            }
        });
    }

    private static List<Item> fetch(Context context, String region, Set<String> chosen, Set<String> hidden,
                                    Map<String, Long> resting) throws IOException, JSONException {
        List<Service> mine = new ArrayList<>(), others = new ArrayList<>();
        for (Service s : services(context)) (chosen.contains(s.key) ? mine : others).add(s);
        if (mine.isEmpty()) return Collections.emptyList();
        String language = Locale.getDefault().toLanguageTag();
        LocalDate today = LocalDate.now();
        String base = discoverBase(region, mine, language);

        // One title that fails (a timeout, odd data) is skipped; only a fetch where every title failed is an error,
        // so a dead network never replaces a good cached list with an empty one.
        int tried = 0, failed = 0;
        List<Item> films = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            JSONArray results = tmdb("/discover/movie?" + base + "&page=" + page + "&vote_count.gte=50"
                    + "&primary_release_date.gte=" + today.minusDays(730)).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                if (hidden.contains("movie:" + results.getJSONObject(i).optInt("id"))) continue;
                tried++;
                try {
                    Item item = film(results.getJSONObject(i), region, language, mine, null, false);
                    if (item != null) films.add(item);
                } catch (IOException | JSONException e) {
                    failed++;
                }
            }
        }
        List<Item> series = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            JSONArray results = tmdb("/discover/tv?" + base + "&page=" + page + "&vote_count.gte=20"
                    + "&without_genres=" + enc(TV_GENRES_LEFT_OUT) + "&air_date.gte=" + today.minusDays(120))
                    .optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                if (hidden.contains("tv:" + results.getJSONObject(i).optInt("id"))) continue;
                tried++;
                try {
                    Item item = series(context, results.getJSONObject(i), region, language, mine, today, null);
                    if (item != null) series.add(item);
                } catch (IOException | JSONException e) {
                    failed++;
                }
            }
        }
        if (tried > 0 && failed == tried) throw new IOException("every title failed");
        // Worth-a-try picks are few and exceptional: a resting one is skipped for the next, never used to fill.
        Set<String> skip = new HashSet<>(hidden);
        skip.addAll(resting.keySet());
        List<Item> outside = worthATry(context, region, mine, others, language, today, skip);
        List<Item> catalogue = catalogue(region, mine, language, today, skip, MAX_CATALOGUE + CATALOGUE_SPARE);
        List<Item> spare = new ArrayList<>(catalogue.subList(Math.min(MAX_CATALOGUE, catalogue.size()), catalogue.size()));
        catalogue = new ArrayList<>(catalogue.subList(0, Math.min(MAX_CATALOGUE, catalogue.size())));
        // Newest first (what just came out is what the owner may have been waiting for), film and series in turn:
        // new seasons air every week, and by date alone they would push every film out.
        // A film by the day it reached streaming in the last year, a series by its season in the last six months.
        String filmsSince = today.minusDays(365).toString(), seriesSince = today.minusDays(183).toString();
        films.removeIf(item -> item.date.compareTo(filmsSince) < 0);
        series.removeIf(item -> item.date.compareTo(seriesSince) < 0);
        for (List<Item> kind : Arrays.asList(films, series)) kind.sort((a, b) -> b.date.compareTo(a.date));
        // Just in: the newest MAX_JUST_IN stay among the new titles; the rest, and resting ones, are not brought back.
        List<Item> justIn = new ArrayList<>();
        for (List<Item> kind : Arrays.asList(films, series)) {
            for (Item item : kind) if (item.justIn && !resting.containsKey(item.id)) justIn.add(item);
        }
        justIn.sort((a, b) -> b.date.compareTo(a.date));
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < Math.min(MAX_JUST_IN, justIn.size()); i++) keep.add(justIn.get(i).id);
        for (List<Item> kind : Arrays.asList(films, series)) kind.removeIf(item -> item.justIn && !keep.contains(item.id));
        // After the new films, so they only take places the new titles leave.
        films.addAll(spare);
        Log.i(TAG, tried + " titles checked, " + failed + " failed; qualified: " + films.size() + " films, "
                + series.size() + " series (" + spare.size() + " of the films from the catalogue), " + outside.size()
                + " worth a try, " + catalogue.size() + " catalogue pick, " + keep.size() + " of them just in");
        List<Item> extras = new ArrayList<>(outside);
        extras.addAll(catalogue);
        return RecsRest.arrange(films, series, extras, resting, RANDOM);
    }

    /**
     * The exceptional few on services the owner does not have: one page of each kind from everything streaming in
     * the region, the higher bar, and nothing that is also on one of the owner's services.
     */
    private static List<Item> worthATry(Context context, String region, List<Service> mine, List<Service> known,
                                        String language, LocalDate today, Set<String> hidden) {
        List<Item> out = new ArrayList<>();
        String base = "watch_region=" + region + "&with_watch_monetization_types=flatrate&language=" + enc(language)
                + "&sort_by=popularity.desc";
        try {
            JSONArray films = tmdb("/discover/movie?" + base + "&vote_count.gte=500"
                    + "&primary_release_date.gte=" + today.minusDays(365)).optJSONArray("results");
            for (int i = 0; films != null && i < films.length() && out.size() < MAX_OUTSIDE; i++) {
                if (hidden.contains("movie:" + films.getJSONObject(i).optInt("id"))) continue;
                try {
                    Item item = film(films.getJSONObject(i), region, language, mine, known, false);
                    if (item != null) out.add(item);
                } catch (IOException | JSONException ignored) {
                    // skip this one
                }
            }
            JSONArray shows = tmdb("/discover/tv?" + base + "&vote_count.gte=100&without_genres=" + enc(TV_GENRES_LEFT_OUT)
                    + "&air_date.gte=" + today.minusDays(120)).optJSONArray("results");
            for (int i = 0; shows != null && i < shows.length() && out.size() < MAX_OUTSIDE; i++) {
                if (hidden.contains("tv:" + shows.getJSONObject(i).optInt("id"))) continue;
                try {
                    Item item = series(context, shows.getJSONObject(i), region, language, mine, today, known);
                    if (item != null) out.add(item);
                } catch (IOException | JSONException ignored) {
                    // skip this one
                }
            }
        } catch (IOException | JSONException e) {
            // no worth-a-try picks this time; the owner's own list still stands
        }
        return out;
    }

    /**
     * Older films on the owner's services: random pages of the best-known ones (2,000 TMDB votes, released over two
     * years ago), shuffled, the first want that pass, from two pages at most. Resting and hidden titles are skipped.
     */
    private static List<Item> catalogue(String region, List<Service> mine, String language, LocalDate today,
                                        Set<String> skip, int want) {
        List<Item> out = new ArrayList<>();
        String query = "/discover/movie?" + discoverBase(region, mine, language) + "&vote_count.gte=2000"
                + "&primary_release_date.lte=" + today.minusDays(730);
        try {
            JSONObject first = tmdb(query + "&page=1");
            List<Integer> pages = new ArrayList<>();
            for (int p = 1; p <= Math.min(first.optInt("total_pages", 1), 20); p++) pages.add(p);
            Collections.shuffle(pages, RANDOM);
            for (int n = 0; n < Math.min(2, pages.size()) && out.size() < want; n++) {
                int page = pages.get(n);
                JSONArray results = (page == 1 ? first : tmdb(query + "&page=" + page)).optJSONArray("results");
                List<JSONObject> picks = new ArrayList<>();
                for (int i = 0; results != null && i < results.length(); i++) picks.add(results.getJSONObject(i));
                Collections.shuffle(picks, RANDOM);
                for (JSONObject m : picks) {
                    if (out.size() == want) break;
                    if (skip.contains("movie:" + m.optInt("id"))) continue;
                    try {
                        Item item = film(m, region, language, mine, null, true);
                        if (item != null) out.add(item);
                    } catch (IOException | JSONException ignored) {
                        // skip this one
                    }
                }
            }
        } catch (IOException | JSONException e) {
            // no catalogue picks this time; the new titles still stand
        }
        return out;
    }

    private static String discoverBase(String region, List<Service> services, String language) {
        Set<Integer> providerIds = new LinkedHashSet<>();
        for (Service s : services) providerIds.addAll(s.providers);
        StringBuilder ids = new StringBuilder();
        for (int id : providerIds) ids.append(ids.length() == 0 ? "" : "|").append(id);
        return "watch_region=" + region + "&with_watch_providers=" + enc(ids.toString())
                + "&with_watch_monetization_types=" + enc("flatrate|free|ads") + "&language=" + enc(language)
                + "&sort_by=popularity.desc";
    }

    /** @param outside null for the owner's own list; for worth-a-try picks, the other services recs_services knows. */
    private static Item film(JSONObject m, String region, String language, List<Service> mine, List<Service> outside,
                             boolean catalogue) throws IOException, JSONException {
        JSONObject d = tmdb("/movie/" + m.getInt("id") + "?language=" + enc(language)
                + "&append_to_response=" + enc("external_ids,watch/providers,release_dates"));
        String service = outside == null ? serviceFor(d, region, mine) : serviceOutside(d, region, mine, outside);
        String backdrop = d.optString("backdrop_path", "");
        if (service == null || backdrop.isEmpty() || "null".equals(backdrop)) return null;
        JSONObject o = omdb(d.optJSONObject("external_ids"));
        int rt = rating(o, "Rotten Tomatoes"), mc = intOf(o.optString("Metascore"));
        double imdb = doubleOf(o.optString("imdbRating"));
        int imdbVotes = intOf(o.optString("imdbVotes").replace(",", ""));
        double tmdb = m.optDouble("vote_average");
        int tmdbVotes = m.optInt("vote_count");
        boolean critics = rt >= 75 || mc >= 65;
        boolean byImdb = imdbVotes >= 5000;
        boolean audience = byImdb ? imdb >= 6.5 : tmdb >= 7.0 && tmdbVotes >= 300;
        boolean strongAudience = byImdb ? imdb >= 7.5 : tmdb >= 7.6 && tmdbVotes >= 500;
        boolean pass = rt >= 0 || mc >= 0 ? critics && audience : strongAudience;
        if (outside != null) pass = rt >= 93 && mc >= 80 && imdb >= 7.5 && imdbVotes >= 20000;
        if (catalogue) pass = imdb >= 7.5 && imdbVotes >= 50000 && (rt < 0 || rt >= 80);
        // A catalogue film is dated by its release: the hero shows its year, not "this month".
        String date = catalogue ? d.optString("release_date") : streamingDate(d, region);
        boolean justIn = !pass && outside == null && !catalogue && justIn(date, imdb, imdbVotes, tmdb, tmdbVotes, rt);
        if (!pass && !justIn) return null;
        return new Item("movie:" + m.getInt("id"), false, outside != null, d.optString("title"),
                year(d.optString("release_date")), d.optString("overview"), IMAGES + backdrop, service, null,
                date, rt, mc, imdb, justIn);
    }

    private static Item series(Context context, JSONObject t, String region, String language, List<Service> mine,
                               LocalDate today, List<Service> outside)
            throws IOException, JSONException {
        JSONObject d = tmdb("/tv/" + t.getInt("id") + "?language=" + enc(language)
                + "&append_to_response=" + enc("external_ids,watch/providers"));
        String service = outside == null ? serviceFor(d, region, mine) : serviceOutside(d, region, mine, outside);
        String backdrop = d.optString("backdrop_path", "");
        if (service == null || backdrop.isEmpty() || "null".equals(backdrop)) return null;
        // The newest season that has started, and whether the show itself is new.
        int latest = 0;
        String latestDate = "";
        JSONArray seasons = d.optJSONArray("seasons");
        for (int i = 0; seasons != null && i < seasons.length(); i++) {
            JSONObject s = seasons.getJSONObject(i);
            String air = s.optString("air_date", "");
            if (s.optInt("season_number") > 0 && !air.isEmpty() && !"null".equals(air)
                    && air.compareTo(today.toString()) <= 0 && s.optInt("season_number") > latest) {
                latest = s.optInt("season_number");
                latestDate = air;
            }
        }
        boolean newShow = d.optString("first_air_date", "").compareTo(today.minusDays(183).toString()) >= 0;
        int seasonCap = outside == null ? 5 : 8;
        boolean newSeason = latest >= 2 && latest <= seasonCap && latestDate.compareTo(today.minusDays(120).toString()) >= 0;
        if (!newShow && !newSeason) return null;
        JSONObject o = omdb(d.optJSONObject("external_ids"));
        int rt = rating(o, "Rotten Tomatoes");
        double imdb = doubleOf(o.optString("imdbRating"));
        int imdbVotes = intOf(o.optString("imdbVotes").replace(",", ""));
        boolean audience = imdbVotes >= 3000 ? imdb >= 7.6
                : t.optDouble("vote_average") >= 8.0 && t.optInt("vote_count") >= 300;
        if (outside != null) audience = imdb >= 8.4 && imdbVotes >= 50000;
        boolean pass = audience && (rt < 0 || rt >= 80);
        String date = newSeason ? latestDate : d.optString("first_air_date");
        boolean justIn = !pass && outside == null
                && justIn(date, imdb, imdbVotes, t.optDouble("vote_average"), t.optInt("vote_count"), rt);
        if (!pass && !justIn) return null;
        String note = newSeason ? context.getString(R.string.recs_season, latest) : context.getString(R.string.recs_new_series);
        return new Item("tv:" + t.getInt("id"), true, outside != null, d.optString("name"),
                year(d.optString("first_air_date")), d.optString("overview"), IMAGES + backdrop, service, note,
                date, rt, -1, imdb, justIn);
    }

    /** Out in the last JUST_IN_DAYS days and not bad: the bar for a "just in" place. */
    private static boolean justIn(String date, double imdb, int imdbVotes, double tmdb, int tmdbVotes, int rt) {
        if (date == null || date.compareTo(LocalDate.now().minusDays(JUST_IN_DAYS).toString()) < 0) return false;
        if (date.compareTo(LocalDate.now().toString()) > 0) return false;
        boolean audience = imdbVotes >= 1000 ? imdb >= 6.0 : tmdb >= 6.0 && tmdbVotes >= 20;
        return audience && (rt < 0 || rt >= 40);
    }

    /**
     * Where a worth-a-try title streams by subscription in this region: null when one of the owner's services has it
     * (it belongs in their own list), else a service recs_services knows (its app may be here), else TMDB's name.
     */
    private static String serviceOutside(JSONObject details, String region, List<Service> mine, List<Service> known) {
        JSONObject wp = details.optJSONObject("watch/providers");
        JSONObject here = wp == null ? null : wp.optJSONObject("results");
        here = here == null ? null : here.optJSONObject(region);
        JSONArray list = here == null ? null : here.optJSONArray("flatrate");
        if (list == null || list.length() == 0) return null;
        for (int i = 0; i < list.length(); i++) {
            int id = list.optJSONObject(i).optInt("provider_id");
            for (Service s : mine) if (s.providers.contains(id)) return null;
        }
        for (int i = 0; i < list.length(); i++) {
            int id = list.optJSONObject(i).optInt("provider_id");
            for (Service s : known) if (s.providers.contains(id)) return s.key;
        }
        return NAME_PREFIX + list.optJSONObject(0).optString("provider_name");
    }

    /**
     * The day a film reached streaming: its digital release (TMDB release type 4) in this region, else the earliest
     * one anywhere, else its release date. A film seen in cinemas in spring is "new" when it lands at home.
     */
    private static String streamingDate(JSONObject details, String region) {
        JSONObject rd = details.optJSONObject("release_dates");
        JSONArray countries = rd == null ? null : rd.optJSONArray("results");
        String here = null, anywhere = null;
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            JSONArray dates = c == null ? null : c.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject r = dates.optJSONObject(j);
                if (r == null || r.optInt("type") != 4 || r.optString("release_date").length() < 10) continue;
                String day = r.optString("release_date").substring(0, 10);
                if (region.equals(c.optString("iso_3166_1")) && (here == null || day.compareTo(here) < 0)) here = day;
                if (anywhere == null || day.compareTo(anywhere) < 0) anywhere = day;
            }
        }
        if (here != null) return here;
        if (anywhere != null) return anywhere;
        return details.optString("release_date", "");
    }

    /** The first of the owner's services that has the title in this region, by subscription, free or with ads. */
    private static String serviceFor(JSONObject details, String region, List<Service> mine) {
        JSONObject wp = details.optJSONObject("watch/providers");
        JSONObject here = wp == null ? null : wp.optJSONObject("results");
        here = here == null ? null : here.optJSONObject(region);
        if (here == null) return null;
        Set<Integer> available = new HashSet<>();
        for (String kind : new String[]{"flatrate", "free", "ads"}) {
            JSONArray list = here.optJSONArray(kind);
            for (int i = 0; list != null && i < list.length(); i++) {
                available.add(list.optJSONObject(i).optInt("provider_id"));
            }
        }
        for (Service s : mine) {
            for (int id : s.providers) if (available.contains(id)) return s.key;
        }
        return null;
    }

    // ---- HTTP and parsing ------------------------------------------------------------------------

    private static JSONObject tmdb(String pathAndQuery) throws IOException, JSONException {
        return new JSONObject(get(TMDB + pathAndQuery, "Bearer " + BuildConfig.TMDB_TOKEN));
    }

    private static JSONObject omdb(JSONObject externalIds) throws IOException, JSONException {
        String imdbId = externalIds == null ? "" : externalIds.optString("imdb_id", "");
        if (imdbId.isEmpty() || "null".equals(imdbId)) return new JSONObject();
        return new JSONObject(get("https://www.omdbapi.com/?i=" + enc(imdbId) + "&apikey=" + enc(BuildConfig.OMDB_KEY), null));
    }

    private static String get(String url, String authorization) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        if (authorization != null) conn.setRequestProperty("Authorization", authorization);
        try {
            if (conn.getResponseCode() / 100 != 2) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int rating(JSONObject omdb, String source) {
        JSONArray ratings = omdb.optJSONArray("Ratings");
        for (int i = 0; ratings != null && i < ratings.length(); i++) {
            JSONObject r = ratings.optJSONObject(i);
            if (r != null && source.equals(r.optString("Source"))) return intOf(r.optString("Value").replace("%", ""));
        }
        return -1;
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double doubleOf(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String year(String date) {
        return date != null && date.length() >= 4 ? date.substring(0, 4) : "";
    }

    // ---- cache -----------------------------------------------------------------------------------

    private static JSONObject readCache(Context context) {
        File f = new File(context.getFilesDir(), CACHE);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(readAll(in));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static List<Item> items(JSONObject cache) {
        List<Item> out = new ArrayList<>();
        JSONArray list = cache == null ? null : cache.optJSONArray("items");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null) out.add(Item.fromJson(o));
        }
        return out;
    }

    /**
     * When each title was first put in a list (epoch day): what rests. Pruned to RecsRest.REST_DAYS on every fetch. A cache
     * written before this was kept counts its own list as shown on the day it was fetched.
     */
    private static Map<String, Long> shownOn(JSONObject cache) {
        Map<String, Long> out = new HashMap<>();
        if (cache == null) return out;
        JSONObject o = cache.optJSONObject("shown");
        if (o == null) {
            long day = Math.floorDiv(cache.optLong("fetched"), 24L * 60 * 60 * 1000);
            for (Item item : items(cache)) out.put(item.id, day);
            return out;
        }
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String id = keys.next();
            out.put(id, o.optLong(id));
        }
        return out;
    }

    private static void writeCache(Context context, String signature, List<Item> items, Map<String, Long> shown)
            throws IOException, JSONException {
        JSONArray list = new JSONArray();
        for (Item item : items) list.put(item.toJson());
        JSONObject shownJson = new JSONObject();
        for (Map.Entry<String, Long> e : shown.entrySet()) shownJson.put(e.getKey(), (long) e.getValue());
        write(context, new JSONObject().put("fetched", System.currentTimeMillis()).put("signature", signature)
                .put("items", list).put("shown", shownJson));
    }

    // ---- logcat ----------------------------------------------------------------------------------

    private static String lastLogged;

    /** The cached-list line repeats on every return to Home; logged only when it changes. */
    private static void logOnce(String line) {
        if (line.equals(lastLogged)) return;
        lastLogged = line;
        Log.i(TAG, line);
    }

    private static String hours(long ms) {
        return ms < 0 || ms > 100L * 24 * 60 * 60 * 1000 ? "never" : ms / (60 * 60 * 1000) + "h";
    }

    private static String titles(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item item : items) sb.append(sb.length() == 0 ? "" : ", ").append(item.title);
        return sb.toString();
    }

    private static void write(Context context, JSONObject cache) throws IOException {
        File tmp = new File(context.getFilesDir(), CACHE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), CACHE))) throw new IOException("cache not saved");
    }
}

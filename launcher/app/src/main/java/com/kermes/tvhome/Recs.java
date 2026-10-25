package com.kermes.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * - Film: released in the last year. With a critic score, Rotten Tomatoes 80% or Metacritic 70, and an audience
 *   score of IMDb 6.8 (5,000 votes) or TMDB 7.0 (300 votes). Without one, the audience bar rises to 7.5.
 * - Series: new in the last year, or a new season (5th at most) in the last 120 days; kids, news, reality, soap
 *   and talk shows left out. OMDb has no series critic score, so IMDb 7.6 (3,000 votes) or TMDB 7.6 (100 votes).
 *   The season cap is what keeps season 26 of a procedural out: their audience scores are as high as anything.
 */
final class Recs {
    static final String SERVICES_KEY = "recs_services";
    static final int MAX = 10;
    private static final long MAX_AGE_MS = 20L * 60 * 60 * 1000;
    private static final String CACHE = "recs.json";
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
        final boolean series;
        final String title, year, overview, backdrop, service;
        /** "Nueva temporada" and the like, or null. */
        final String note;
        final int rottenTomatoes, metacritic;
        final double imdb;

        Item(boolean series, String title, String year, String overview, String backdrop, String service, String note,
             int rottenTomatoes, int metacritic, double imdb) {
            this.series = series;
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
            return new JSONObject().put("series", series).put("title", title).put("year", year)
                    .put("overview", overview).put("backdrop", backdrop).put("service", service).put("note", note)
                    .put("rt", rottenTomatoes).put("mc", metacritic).put("imdb", imdb);
        }

        static Item fromJson(JSONObject o) {
            return new Item(o.optBoolean("series"), o.optString("title"), o.optString("year"), o.optString("overview"),
                    o.optString("backdrop"), o.optString("service"), o.isNull("note") ? null : o.optString("note", null),
                    o.optInt("rt", -1), o.optInt("mc", -1), o.optDouble("imdb", -1));
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

    static Service service(Context context, String key) {
        for (Service s : services(context)) if (s.key.equals(key)) return s;
        return null;
    }

    /** The service's app, at its TV start screen. TMDB gives no link into the app itself. */
    static Intent intentFor(Context context, Item item) {
        Service s = service(context, item.service);
        if (s == null) return null;
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(s.pkg);
        return launch != null ? launch : pm.getLaunchIntentForPackage(s.pkg);
    }

    /**
     * Hands back the cached list at once (when there is one), then, when it is older than 20 hours or was made for
     * other services, fetches a new one and hands that back too. Both on the main thread.
     */
    static void loadAsync(Context context, Consumer<List<Item>> done) {
        Context app = context.getApplicationContext();
        String region = app.getString(R.string.recs_region);
        Set<String> chosen = chosen(app);
        String signature = region + ":" + new TreeSet<>(chosen);
        LOADER.execute(() -> {
            JSONObject cache = readCache(app);
            List<Item> cached = items(cache);
            boolean sameServices = cache != null && signature.equals(cache.optString("signature"));
            if (sameServices && !cached.isEmpty()) MAIN.post(() -> done.accept(cached));
            boolean fresh = sameServices && System.currentTimeMillis() - cache.optLong("fetched") < MAX_AGE_MS;
            if (fresh || !configured()) return;
            try {
                List<Item> fetched = fetch(app, region, chosen);
                writeCache(app, signature, fetched);
                MAIN.post(() -> done.accept(fetched));
            } catch (IOException | JSONException | RuntimeException e) {
                // Offline or an API hiccup: the cached list stays, and the next return to Home tries again.
            }
        });
    }

    private static List<Item> fetch(Context context, String region, Set<String> chosen) throws IOException, JSONException {
        List<Service> mine = new ArrayList<>();
        for (Service s : services(context)) if (chosen.contains(s.key)) mine.add(s);
        if (mine.isEmpty()) return Collections.emptyList();
        Set<Integer> providerIds = new LinkedHashSet<>();
        for (Service s : mine) providerIds.addAll(s.providers);
        StringBuilder ids = new StringBuilder();
        for (int id : providerIds) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String language = Locale.getDefault().toLanguageTag();
        LocalDate today = LocalDate.now();
        String base = "watch_region=" + region + "&with_watch_providers=" + enc(ids.toString())
                + "&with_watch_monetization_types=" + enc("flatrate|free|ads") + "&language=" + enc(language)
                + "&sort_by=popularity.desc";

        // One title that fails (a timeout, odd data) is skipped; only a fetch where every title failed is an error,
        // so a dead network never replaces a good cached list with an empty one.
        int tried = 0, failed = 0;
        List<Item> films = new ArrayList<>();
        for (int page = 1; page <= 2 && films.size() < MAX; page++) {
            JSONArray results = tmdb("/discover/movie?" + base + "&page=" + page + "&vote_count.gte=50"
                    + "&primary_release_date.gte=" + today.minusDays(365)).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                tried++;
                try {
                    Item item = film(results.getJSONObject(i), region, language, mine);
                    if (item != null) films.add(item);
                } catch (IOException | JSONException e) {
                    failed++;
                }
            }
        }
        List<Item> series = new ArrayList<>();
        for (int page = 1; page <= 3 && series.size() < MAX; page++) {
            JSONArray results = tmdb("/discover/tv?" + base + "&page=" + page + "&vote_count.gte=20"
                    + "&without_genres=" + enc(TV_GENRES_LEFT_OUT) + "&air_date.gte=" + today.minusDays(120))
                    .optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                tried++;
                try {
                    Item item = series(context, results.getJSONObject(i), region, language, mine, today);
                    if (item != null) series.add(item);
                } catch (IOException | JSONException e) {
                    failed++;
                }
            }
        }
        if (tried > 0 && failed == tried) throw new IOException("every title failed");
        // Film, series, film...: TMDB's popularity order within each.
        List<Item> out = new ArrayList<>();
        for (int i = 0; out.size() < MAX && (i < films.size() || i < series.size()); i++) {
            if (i < films.size()) out.add(films.get(i));
            if (i < series.size() && out.size() < MAX) out.add(series.get(i));
        }
        return out;
    }

    private static Item film(JSONObject m, String region, String language, List<Service> mine)
            throws IOException, JSONException {
        JSONObject d = tmdb("/movie/" + m.getInt("id") + "?language=" + enc(language)
                + "&append_to_response=" + enc("external_ids,watch/providers"));
        String service = serviceFor(d, region, mine);
        String backdrop = d.optString("backdrop_path", "");
        if (service == null || backdrop.isEmpty() || "null".equals(backdrop)) return null;
        JSONObject o = omdb(d.optJSONObject("external_ids"));
        int rt = rating(o, "Rotten Tomatoes"), mc = intOf(o.optString("Metascore"));
        double imdb = doubleOf(o.optString("imdbRating"));
        int imdbVotes = intOf(o.optString("imdbVotes").replace(",", ""));
        double tmdb = m.optDouble("vote_average");
        int tmdbVotes = m.optInt("vote_count");
        boolean critics = rt >= 80 || mc >= 70;
        boolean audience = imdb >= 6.8 && imdbVotes >= 5000 || tmdb >= 7.0 && tmdbVotes >= 300;
        boolean strongAudience = imdb >= 7.5 && imdbVotes >= 5000 || tmdb >= 7.6 && tmdbVotes >= 300;
        boolean pass = rt >= 0 || mc >= 0 ? critics && audience : strongAudience;
        if (!pass) return null;
        return new Item(false, d.optString("title"), year(d.optString("release_date")), d.optString("overview"),
                IMAGES + backdrop, service, null, rt, mc, imdb);
    }

    private static Item series(Context context, JSONObject t, String region, String language, List<Service> mine,
                               LocalDate today)
            throws IOException, JSONException {
        JSONObject d = tmdb("/tv/" + t.getInt("id") + "?language=" + enc(language)
                + "&append_to_response=" + enc("external_ids,watch/providers"));
        String service = serviceFor(d, region, mine);
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
        boolean newShow = d.optString("first_air_date", "").compareTo(today.minusDays(365).toString()) >= 0;
        boolean newSeason = latest >= 2 && latest <= 5 && latestDate.compareTo(today.minusDays(120).toString()) >= 0;
        if (!newShow && !newSeason) return null;
        JSONObject o = omdb(d.optJSONObject("external_ids"));
        int rt = rating(o, "Rotten Tomatoes");
        double imdb = doubleOf(o.optString("imdbRating"));
        int imdbVotes = intOf(o.optString("imdbVotes").replace(",", ""));
        boolean audience = imdb >= 7.6 && imdbVotes >= 3000
                || t.optDouble("vote_average") >= 7.6 && t.optInt("vote_count") >= 100;
        if (!audience || rt >= 0 && rt < 80) return null;
        String note = newSeason ? context.getString(R.string.recs_season, latest) : context.getString(R.string.recs_new_series);
        return new Item(true, d.optString("name"), year(d.optString("first_air_date")), d.optString("overview"),
                IMAGES + backdrop, service, note, rt, -1, imdb);
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

    private static void writeCache(Context context, String signature, List<Item> items) throws IOException, JSONException {
        JSONArray list = new JSONArray();
        for (Item item : items) list.put(item.toJson());
        JSONObject cache = new JSONObject().put("fetched", System.currentTimeMillis()).put("signature", signature)
                .put("items", list);
        File tmp = new File(context.getFilesDir(), CACHE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), CACHE))) throw new IOException("cache not saved");
    }
}

package com.kermes.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Continue watching (watch_next_row): the rows apps publish to the TV provider's Watch Next table, the same
 * source Google TV's own row starts from (docs/watch-next.md). Reading it needs READ_TV_LISTINGS, which
 * build-and-install grants. A sideloaded app cannot hold ACCESS_ALL_EPG_DATA, so the provider returns only
 * the rows their apps marked searchable and refuses any selection: the whole table is read and filtered here.
 * Netflix publishes nothing there, so it never shows; on a TCL 75Q7C Prime Video, HBO Max, SkyShowtime,
 * Jellyfin, Wholphin and Moonfin did.
 */
final class WatchNext {
    static final int MAX = 12;
    /** WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST: saved for later, never started, so not "continue". */
    private static final int TYPE_WATCHLIST = 3;
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final String[] PROJECTION = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
    };
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WatchNext() {
    }

    static final class Item {
        final String pkg;
        final String appLabel;
        final String title;
        /** The episode's title, or null for a film. */
        final String episode;
        /** Poster, else thumbnail: https:// for most streaming apps, content:// for Jellyfin and its clients. */
        final String image;
        final String intentUri;
        /** 0 to 1, or -1 when the app gives no position (Jellyfin's "next episode" rows). */
        final float progress;
        final long engaged;

        Item(String pkg, String appLabel, String title, String episode, String image, String intentUri,
             float progress, long engaged) {
            this.pkg = pkg;
            this.appLabel = appLabel;
            this.title = title;
            this.episode = episode;
            this.image = image;
            this.intentUri = intentUri;
            this.progress = progress;
            this.engaged = engaged;
        }

        /** Changes whenever anything the card shows changes, so the row is only rebuilt then. */
        String signature() {
            return pkg + '\t' + title + '\t' + episode + '\t' + image + '\t' + Math.round(progress * 50);
        }
    }

    /** Reads the table off the main thread and hands the newest MAX items, one per show, back on it. */
    static void loadAsync(Context context, Consumer<List<Item>> done) {
        Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            List<Item> items = load(app);
            MAIN.post(() -> done.accept(items));
        });
    }

    static List<Item> load(Context context) {
        if (context.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptyList();
        }
        List<Item> all = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        Map<String, String> labels = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                PROJECTION, null, null, null)) {
            if (c == null) return Collections.emptyList();
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String title = c.getString(1);
                if (pkg == null || title == null || c.getInt(3) == TYPE_WATCHLIST) continue;
                String label = labels.get(pkg);
                if (label == null) {
                    label = appLabel(pm, pkg);
                    if (label == null) continue;   // the app is gone or disabled: nothing could open the row
                    labels.put(pkg, label);
                }
                long position = c.getLong(5);
                long duration = c.getLong(6);
                float progress = position > 0 && duration > 0 ? Math.min(1f, position / (float) duration) : -1f;
                String image = c.getString(7) != null ? c.getString(7) : c.getString(8);
                all.add(new Item(pkg, label, title, emptyToNull(c.getString(2)), image, c.getString(9),
                        progress, c.getLong(4)));
            }
        } catch (RuntimeException e) {
            // SecurityException on firmware that guards the table differently, or a provider that is not there.
            return Collections.emptyList();
        }
        all.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        // Several episodes of one show: Google TV shows the newest once, and so does this row.
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : all) {
            if (seen.add(item.pkg + '\n' + item.title)) out.add(item);
            if (out.size() == MAX) break;
        }
        return out;
    }

    /** What the row opens: the app's own intent_uri (an intent: URI or a deep link), else the app's start screen. */
    static Intent intentFor(Context context, Item item) {
        if (item.intentUri != null) {
            try {
                // A plain deep link comes back as ACTION_VIEW on that URI; pin it to the app that published it so
                // another handler of the same scheme never answers.
                Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                intent.setSelector(null);
                if (intent.getComponent() == null && intent.getPackage() == null) intent.setPackage(item.pkg);
                return intent;
            } catch (URISyntaxException ignored) {
                // fall through to the app itself
            }
        }
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        return launch != null ? launch : pm.getLaunchIntentForPackage(item.pkg);
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.enabled ? pm.getApplicationLabel(info).toString() : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** True when an image URI needs the network; the rest are read through the content resolver. */
    static boolean isRemote(String uri) {
        String scheme = Uri.parse(uri).getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }
}

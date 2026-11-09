package com.kermes.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * recent_dock: apps by when they were last on screen, newest first, from Android's usage stats. Unlike LastOpened
 * this sees every way an app was started (a remote's app button, Continue watching, another app), not only Home's
 * tiles. Reading it needs the GET_USAGE_STATS app op, which build-and-install sets; without it the system returns
 * nothing and the dock stays on dock_apps.
 */
final class RecentApps {
    /** Apps not used in this long drop out; the dock then falls back on dock_apps. */
    private static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    /** How often the whole window is read again; in between, each return to Home asks only since the last one. */
    private static final long FULL_EVERY_MS = 24L * 60 * 60 * 1000;
    /** When each app was last on screen. Main thread only, like load. */
    private static final Map<String, Long> SEEN = new HashMap<>();
    private static long fullAt, queriedAt;

    private RecentApps() {
    }

    static List<String> load(Context context) {
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        if (usage == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        // 30 days of usage files on every return to Home is slow on a TV's storage; since the last return is
        // today's stats, which Android keeps in memory.
        boolean full = now - fullAt > FULL_EVERY_MS || queriedAt > now;
        Map<String, UsageStats> stats;
        try {
            stats = usage.queryAndAggregateUsageStats(full ? now - WINDOW_MS : queriedAt, now);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (full) {
            SEEN.clear();
            fullAt = now;
        }
        queriedAt = now;
        for (UsageStats s : stats.values()) {
            long at = shownAt(s);
            if (at > 0 && !s.getPackageName().equals(context.getPackageName())) SEEN.merge(s.getPackageName(), at, Math::max);
        }
        SEEN.values().removeIf(at -> at < now - WINDOW_MS);
        List<String> out = new ArrayList<>(SEEN.keySet());
        out.sort((a, b) -> Long.compare(SEEN.get(b), SEEN.get(a)));
        return out;
    }

    /**
     * When the app was last visible. Android 10 added that time on its own; before it, the last-used time also
     * moves for a service or a broadcast, so it counts only for apps that were in the foreground at all.
     */
    private static long shownAt(UsageStats s) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return s.getLastTimeVisible();
        return s.getTotalTimeInForeground() > 0 ? s.getLastTimeUsed() : 0;
    }
}

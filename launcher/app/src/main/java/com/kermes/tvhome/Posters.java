package com.kermes.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Images from the network: the https:// posters streaming apps put in their Watch Next rows (Continue watching), and
 * the recommendations hero's backdrops. Each one is decoded straight down to about the width it is shown at (on a
 * 1080p TV a 1280-wide poster becomes a 640-wide RGB_565 bitmap, ~230 KB; a backdrop 960 wide, ~1 MB) and kept in a
 * 16 MB memory cache, so coming back to Home does not fetch them again. The downloads themselves (the compressed
 * files, 100-400 KB each) are also kept in the app's cache folder, at most PosterFiles.DISK_MAX and the least recently used
 * removed first, so a launcher restarted after the TV reclaimed its memory does not fetch them again either. Android
 * may empty that folder whenever storage runs low.
 */
final class Posters {
    /** The largest download read; anything bigger is not a poster. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT_MS = 8000;
    private static final String DISK_DIR = "posters";
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    private Posters() {
    }

    static Bitmap cached(String uri, int width) {
        return CACHE.get(key(uri, width));
    }

    /** Decodes uri at about width pixels wide off the main thread; done gets null when it cannot be read. */
    static void load(Context context, String uri, int width, Consumer<Bitmap> done) {
        Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = read(app, uri);
                if (bytes != null) bitmap = decode(bytes, width);
            } catch (IOException | RuntimeException ignored) {
                // Offline, a dead link or an app's provider that refuses us: the card keeps its title instead.
            }
            if (bitmap != null) CACHE.put(key(uri, width), bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> done.accept(result));
        });
    }

    static void trim() {
        CACHE.evictAll();
    }

    private static String key(String uri, int width) {
        return width + "|" + uri;
    }

    private static byte[] read(Context context, String uri) throws IOException {
        if (WatchNext.isRemote(uri)) {
            File dir = new File(context.getCacheDir(), DISK_DIR);
            File file = new File(dir, PosterFiles.name(uri));
            if (file.isFile()) {
                // Touched on every use: the least recently used go first when the folder is trimmed.
                file.setLastModified(System.currentTimeMillis());
                return Files.readAllBytes(file.toPath());
            }
            byte[] bytes = download(uri);
            if (bytes != null) PosterFiles.save(dir, file, bytes);
            return bytes;
        }
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
            return in == null ? null : readAll(in);
        }
    }

    private static byte[] download(String uri) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        try {
            if (conn.getResponseCode() / 100 != 2) return null;
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }

    private static Bitmap decode(byte[] bytes, int width) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0) return null;
        int sample = 1;
        while (options.outWidth / (sample * 2) >= width) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        // No alpha in a poster: half the memory of ARGB_8888.
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null || decoded.getWidth() <= width * 3 / 2) return decoded;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width,
                Math.max(1, Math.round(decoded.getHeight() * (width / (float) decoded.getWidth()))), true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }
}

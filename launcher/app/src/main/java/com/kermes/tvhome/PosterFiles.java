package com.kermes.tvhome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Posters' folder of downloads in the app's cache: at most DISK_MAX, the least recently used deleted first. */
final class PosterFiles {
    static final long DISK_MAX = 24L * 1024 * 1024;

    private PosterFiles() {
    }

    /** Writes the download beside the others, then trims the folder; a failed write only costs the next download. */
    static void save(File dir, File file, byte[] bytes) {
        File tmp = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            // Its own temporary file: both loader threads may fetch the same poster at once.
            tmp = File.createTempFile(file.getName(), ".tmp", dir);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (IOException e) {
            if (tmp != null) tmp.delete();
            return;
        }
        trim(dir, DISK_MAX);
    }

    /** Deletes the least recently used files until the folder is under three quarters of max. */
    static synchronized void trim(File dir, long max) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= max) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length && total > max * 3 / 4; i++) {
            long size = files[i].length();
            if (files[i].delete()) total -= size;
        }
    }

    /** The file name for a download: the SHA-1 of its URI, so any URI maps to a safe, fixed-length name. */
    static String name(String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(uri.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(uri.hashCode());
        }
    }
}

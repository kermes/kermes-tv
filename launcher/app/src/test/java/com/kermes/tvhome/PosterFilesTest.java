package com.kermes.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Test;

public class PosterFilesTest {

    private static File file(File dir, String name, int bytes, long modified) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        assertTrue(f.setLastModified(modified));
        return f;
    }

    @Test
    public void trimDeletesLeastRecentlyUsedUntilThreeQuartersOfMax() throws IOException {
        File dir = Files.createTempDirectory("posters").toFile();
        File oldest = file(dir, "a", 400, 1_000_000L);
        File older = file(dir, "b", 400, 2_000_000L);
        File newest = file(dir, "c", 400, 3_000_000L);
        PosterFiles.trim(dir, 1000);
        assertFalse(oldest.exists());
        assertFalse(older.exists());
        assertTrue(newest.exists());
    }

    @Test
    public void trimLeavesAFolderUnderMaxAlone() throws IOException {
        File dir = Files.createTempDirectory("posters").toFile();
        File a = file(dir, "a", 400, 1_000_000L);
        File b = file(dir, "b", 400, 2_000_000L);
        PosterFiles.trim(dir, 1000);
        assertTrue(a.exists());
        assertTrue(b.exists());
    }

    @Test
    public void namesAreFixedLengthAndDistinct() {
        String a = PosterFiles.name("https://image.tmdb.org/t/p/w1280/abc.jpg");
        String b = PosterFiles.name("https://image.tmdb.org/t/p/w1280/abd.jpg");
        assertEquals(40, a.length());
        assertNotEquals(a, b);
        assertEquals(a, PosterFiles.name("https://image.tmdb.org/t/p/w1280/abc.jpg"));
    }
}

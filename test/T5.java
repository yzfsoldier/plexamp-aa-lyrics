/*
 * Plexamp AA Lyrics – synced lyrics for Plexamp on Android Auto
 * Copyright (C) 2026 yzfsoldier
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import tv.plex.labs.plexamp.lyrics.LyricsCache;
import tv.plex.labs.plexamp.lyrics.LyricsCore;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/** Offline lyrics cache: round trip, escaping, negative entries, LRU pruning, corrupt files. */
public class T5 {
    static void ok(boolean c, String m) { if (!c) throw new RuntimeException("FAIL " + m); System.out.println("ok " + m); }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("lyrcache").toFile();
        LyricsCache c = new LyricsCache(new File(dir, "lyrics_cache"), 5);
        long now = 1_700_000_000_000L;

        String k = LyricsCache.key("My Song!", "The Band", "Album");
        ok(k.equals(LyricsCache.key("my song", "the  band", "ALBUM")), "key ignores case/punctuation");
        ok(!k.equals(LyricsCache.key("My Song", "The Band", "Other Album")), "key depends on album");
        ok(c.get(k, now).state == LyricsCache.State.MISS, "empty cache -> miss");

        LyricsCore.Lyrics l = LyricsCore.parseLrc("[00:01.00]Tab\there\n[00:02.00]Back\\slash\n[00:03.00]\n[00:04.00]Ümlaut ♪");
        c.put(k, l);
        LyricsCache.Entry e = c.get(k, now);
        ok(e.state == LyricsCache.State.HIT, "hit after put");
        ok(e.lyrics.start.length == 4 && e.lyrics.start[1] == 2000, "timestamps survive");
        ok(e.lyrics.text[0].equals("Tab\there") && e.lyrics.text[1].equals("Back\\slash")
                && e.lyrics.text[2].equals("") && e.lyrics.text[3].equals("Ümlaut ♪"), "text survives escaping");

        String none = LyricsCache.key("Instrumental", "X", "Y");
        c.putNoLyrics(none, now);
        ok(c.get(none, now + 1000).state == LyricsCache.State.NO_LYRICS, "negative entry is used");
        ok(c.get(none, now + LyricsCache.NEGATIVE_TTL_MS + 1).state == LyricsCache.State.MISS, "negative entry expires after 7 days");

        long[] st = c.stats();
        ok(st[0] == 1 && st[1] > 0, "stats count only songs with lyrics: " + st[0]);

        // corrupt file -> miss and removed
        String bad = LyricsCache.key("Bad", "B", "C");
        File bf = new File(new File(dir, "lyrics_cache"), bad + ".lrc");
        try (FileOutputStream o = new FileOutputStream(bf)) { o.write("garbage".getBytes("UTF-8")); }
        ok(c.get(bad, now).state == LyricsCache.State.MISS && !bf.exists(), "corrupt file is dropped");

        // LRU pruning: limit 5, write 30 songs, oldest go first, recently read song survives
        c.put(k, l);
        new File(new File(dir, "lyrics_cache"), k + ".lrc").setLastModified(now + 999_999); // recently used
        for (int i = 0; i < 30; i++) {
            String ki = LyricsCache.key("Song " + i, "A", "B");
            c.put(ki, l);
            new File(new File(dir, "lyrics_cache"), ki + ".lrc").setLastModified(now + i);
        }
        int files = new File(dir, "lyrics_cache").listFiles((d, n) -> n.endsWith(".lrc")).length;
        ok(files <= 5 + 20, "cache is pruned (" + files + " files)");
        ok(c.get(k, now + 1_000_000).state == LyricsCache.State.HIT, "recently used song survives pruning");

        c.clear();
        ok(c.stats()[0] == 0 && c.get(k, now).state == LyricsCache.State.MISS, "clear empties the cache");
    }
}

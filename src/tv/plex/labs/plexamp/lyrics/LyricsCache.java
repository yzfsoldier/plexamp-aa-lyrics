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
package tv.plex.labs.plexamp.lyrics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent lyrics cache in the app's private files directory, so lyrics work offline and the
 * server is not asked again for songs that were already loaded.
 *
 * One small text file per song, named after a hash of (title, artist, album). Songs without lyrics
 * are remembered too ("negative" entries), but only for a while, so lyrics added on the server later
 * are still found. The cache is limited in size; the least recently used songs are removed first.
 *
 * Plain Java (no Android classes) so it can be tested on a desktop JVM.
 */
public final class LyricsCache {

    private static final String MAGIC = "PLXLYR1";
    private static final String MAGIC_NONE = "PLXLYR1-NONE";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** How long a "this song has no lyrics" entry is trusted before the server is asked again. */
    public static final long NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Result of a cache lookup. */
    public enum State { MISS, HIT, NO_LYRICS }

    public static final class Entry {
        public final State state;
        public final LyricsCore.Lyrics lyrics; // only for HIT

        Entry(State state, LyricsCore.Lyrics lyrics) {
            this.state = state;
            this.lyrics = lyrics;
        }
    }

    private final File dir;
    private final int maxEntries;
    private int writesSincePrune;

    public LyricsCache(File dir, int maxEntries) {
        this.dir = dir;
        this.maxEntries = maxEntries;
    }

    /** Stable cache key for a song, independent of case, punctuation and whitespace. */
    public static String key(String title, String artist, String album) {
        String raw = LyricsCore.norm(title) + "\u0000" + LyricsCore.norm(artist) + "\u0000" + LyricsCore.norm(album);
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(UTF8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : h) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private File file(String key) {
        return new File(dir, key + ".lrc");
    }

    /** Looks a song up. Stale negative entries count as MISS. Touches hits for LRU. */
    public synchronized Entry get(String key, long nowMs) {
        File f = file(key);
        if (!f.isFile()) return new Entry(State.MISS, null);
        try {
            String s = new String(readAll(f), UTF8);
            if (s.startsWith(MAGIC_NONE + "\n")) {
                long at = Long.parseLong(s.substring(MAGIC_NONE.length() + 1).trim());
                if (nowMs - at < NEGATIVE_TTL_MS && nowMs >= at) return new Entry(State.NO_LYRICS, null);
                return new Entry(State.MISS, null);
            }
            LyricsCore.Lyrics l = decode(s);
            if (l == null) {
                f.delete(); // corrupt
                return new Entry(State.MISS, null);
            }
            f.setLastModified(nowMs);
            return new Entry(State.HIT, l);
        } catch (Exception e) {
            f.delete();
            return new Entry(State.MISS, null);
        }
    }

    public synchronized void put(String key, LyricsCore.Lyrics l) {
        if (l == null) return;
        write(key, encode(l));
    }

    public synchronized void putNoLyrics(String key, long nowMs) {
        write(key, MAGIC_NONE + "\n" + nowMs + "\n");
    }

    /** Number of songs with lyrics and total size in bytes (negative entries are not counted as songs). */
    public synchronized long[] stats() {
        File[] fs = list();
        long songs = 0, bytes = 0;
        for (File f : fs) {
            bytes += f.length();
            if (f.length() > MAGIC_NONE.length() + 24) songs++;
            else {
                try {
                    if (!new String(readAll(f), UTF8).startsWith(MAGIC_NONE)) songs++;
                } catch (Exception ignored) {
                }
            }
        }
        return new long[]{songs, bytes};
    }

    public synchronized void clear() {
        for (File f : list()) f.delete();
    }

    // ------------------------------------------------------------------ internals

    private void write(String key, String content) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = file(key);
            File tmp = new File(dir, key + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes(UTF8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            if (++writesSincePrune >= 20) {
                writesSincePrune = 0;
                prune();
            }
        } catch (Exception ignored) {
        }
    }

    /** Removes the least recently used entries above the size limit. */
    void prune() {
        File[] fs = list();
        if (fs.length <= maxEntries) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < fs.length - maxEntries; i++) fs[i].delete();
    }

    private File[] list() {
        File[] fs = dir.listFiles();
        if (fs == null) return new File[0];
        List<File> out = new ArrayList<File>();
        for (File f : fs) if (f.getName().endsWith(".lrc")) out.add(f);
        return out.toArray(new File[0]);
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static String encode(LyricsCore.Lyrics l) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        for (int i = 0; i < l.start.length; i++) {
            sb.append(l.start[i]).append('\t').append(escape(l.text[i])).append('\n');
        }
        return sb.toString();
    }

    static LyricsCore.Lyrics decode(String s) {
        if (!s.startsWith(MAGIC + "\n")) return null;
        String[] lines = s.substring(MAGIC.length() + 1).split("\n");
        List<Long> starts = new ArrayList<Long>();
        List<String> texts = new ArrayList<String>();
        for (String line : lines) {
            if (line.length() == 0) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) return null;
            try {
                starts.add(Long.parseLong(line.substring(0, tab)));
            } catch (NumberFormatException e) {
                return null;
            }
            texts.add(unescape(line.substring(tab + 1)));
        }
        if (starts.isEmpty()) return null;
        long[] st = new long[starts.size()];
        for (int i = 0; i < st.length; i++) st[i] = starts.get(i);
        return new LyricsCore.Lyrics(st, texts.toArray(new String[0]));
    }

    private static String escape(String t) {
        return t.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unescape(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length()) {
                char n = t.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

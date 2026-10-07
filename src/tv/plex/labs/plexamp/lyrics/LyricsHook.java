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

import android.os.Handler;
import android.os.Bundle;
import androidx.media3.session.CommandButton;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import tv.plex.labs.plexamp.media.CustomActions;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import tv.plex.labs.plexamp.media.AndroidCarBridge;
import tv.plex.labs.plexamp.media.PlayerStateAdapter;
import tv.plex.labs.plexamp.media.TreblePlayer;

/**
 * Called from TreblePlayer.updateFromState (patched). Replaces the now-playing title with the
 * current lyric line while Android Auto is connected, and the artist line with "Title – Artist".
 * All work happens on the player's (main) looper except network fetches.
 */
public final class LyricsHook {

    private static final String TAG = "PlexampLyricsMod";
    private static final long TICK_MS = 200;
    private static final long LEAD_MS = 250; // show a line slightly early to hide UI latency
    private static final long CAR_CHECK_MS = 2000;
    private static final String NOTE = "♪";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static TreblePlayer player;
    private static PlayerStateAdapter.PlayerStateUpdate lastRaw;
    private static long rawAt;
    private static PlayerStateAdapter.PlayerStateUpdate lastProduced;
    private static String trackKey;
    private static volatile LyricsCore.Lyrics lyrics;
    private static int shownIndex = -1;
    private static boolean decorated;
    private static int generation;
    private static boolean tickerRunning;
    private static volatile LyricsCore.Server lastServer;
    private static boolean serverLoaded;

    private static boolean carActive;
    private static long carCheckedAt = -CAR_CHECK_MS;

    private static final Map<String, LyricsCore.Lyrics> CACHE = new LinkedHashMap<String, LyricsCore.Lyrics>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LyricsCore.Lyrics> e) {
            return size() > 40;
        }
    };
    private static final LyricsCore.Lyrics NONE = new LyricsCore.Lyrics(new long[0], new String[0]);

    private LyricsHook() {}

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "tick failed", t);
            }
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    /** Entry point from the patched TreblePlayer.updateFromState. Never throws. */
    public static PlayerStateAdapter.PlayerStateUpdate transform(TreblePlayer p, PlayerStateAdapter.PlayerStateUpdate u) {
        try {
            if (u == null || u == lastProduced) return u;
            player = p;
            lastRaw = u;
            rawAt = SystemClock.elapsedRealtime();
            if (!tickerRunning) {
                tickerRunning = true;
                MAIN.postDelayed(TICK, TICK_MS);
            }

            if (lastServer == null) {
                lastServer = loadServer();
                if (lastServer != null && !serverLoaded) Log.i(TAG, "using configured server " + lastServer.base);
                serverLoaded = true;
            }

            // A web cover (if Plexamp provides one) also points at the album; only used for this track.
            LyricsCore.Server s = LyricsCore.serverFromThumb(u.getThumbUrl());

            String key = u.getTitle() + "\u0000" + u.getArtist() + "\u0000" + u.getAlbum();
            if (!key.equals(trackKey)) {
                trackKey = key;
                boolean hadLyrics = lyrics != null;
                lyrics = null;
                shownIndex = -1;
                generation++;
                if (hadLyrics) refreshLayout();
                if (!u.isLongFormAudio() && u.getTitle().length() > 0) {
                    LyricsCore.Lyrics cached;
                    synchronized (CACHE) {
                        cached = CACHE.get(key);
                    }
                    if (cached != null) {
                        lyrics = cached == NONE ? null : cached;
                        if (lyrics != null) refreshLayout();
                    } else {
                        LyricsCore.Server use = s != null ? s : LyricsCore.withoutKey(lastServer);
                        startFetch(use, u.getTitle(), u.getArtist(), key, generation);
                    }
                }
            }

            long pos = u.getPositionMs();
            int idx = currentIndex(pos);
            PlayerStateAdapter.PlayerStateUpdate out = decorate(u, pos, idx);
            shownIndex = idx;
            decorated = idx >= 0 && lyrics != null;
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "transform failed", t);
            return u;
        }
    }

    private static void startFetch(final LyricsCore.Server s, final String title, final String artist,
                                   final String key, final int gen) {
        if (s == null) {
            Log.i(TAG, "no server configured (long-press the Plexamp icon -> Songtexte AA / Lyrics AA), cannot load lyrics for " + title);
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                LyricsCore.Lyrics l = null;
                try {
                    l = LyricsCore.fetch(s, title, artist);
                } catch (Throwable e) {
                    Log.w(TAG, "lyrics fetch failed for " + title + ": " + e);
                }
                final LyricsCore.Lyrics result = l;
                synchronized (CACHE) {
                    CACHE.put(key, result == null ? NONE : result);
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation) return;
                        lyrics = result;
                        shownIndex = -2; // force refresh on next tick
                        refreshLayout(); // show/hide the "Mehr Text" button
                        Log.i(TAG, (result == null ? "no timed lyrics for " : result.start.length + " lyric lines for ") + title);
                    }
                });
            }
        }, "plexamp-lyrics");
        t.setDaemon(true);
        t.start();
    }

    private static long estimatePosition() {
        PlayerStateAdapter.PlayerStateUpdate u = lastRaw;
        long pos = u.getPositionMs();
        if (u.getPlaybackState() == PlayerStateAdapter.State.PLAYING) {
            pos += SystemClock.elapsedRealtime() - rawAt;
            if (u.getDurationMs() > 0 && pos > u.getDurationMs()) pos = u.getDurationMs();
        }
        return pos;
    }

    private static int currentIndex(long pos) {
        LyricsCore.Lyrics l = lyrics;
        if (l == null || mode() == MODE_OFF || !isCarActive()) return -1;
        return l.indexAt(pos + LEAD_MS);
    }

    private static void tick() {
        if (lastRaw == null || player == null) return;
        long pos = estimatePosition();
        int idx = currentIndex(pos);
        if (idx == shownIndex) return;
        boolean wouldDecorate = idx >= 0;
        if (!wouldDecorate && !decorated) {
            shownIndex = idx;
            return;
        }
        PlayerStateAdapter.PlayerStateUpdate out = decorate(lastRaw, pos, idx);
        shownIndex = idx;
        decorated = idx >= 0 && lyrics != null;
        lastProduced = out;
        player.updateFromState(out);
    }

    private static PlayerStateAdapter.PlayerStateUpdate decorate(PlayerStateAdapter.PlayerStateUpdate u, long pos, int idx) {
        LyricsCore.Lyrics l = lyrics;
        if (l == null || idx < 0 || idx >= l.text.length) {
            if (pos == u.getPositionMs()) return u;
            return copy(u, u.getTitle(), u.getArtist(), u.getAlbum(), pos);
        }
        String line = lineAt(l, idx);
        String songInfo = u.getTitle();
        if (u.getArtist().length() > 0) songInfo = songInfo + " \u2013 " + u.getArtist();
        if (mode() == MODE_EXPANDED) {
            // "Mehr Text": current line, then the next two lines in the artist and album fields.
            String next1 = idx + 1 < l.text.length ? lineAt(l, idx + 1) : songInfo;
            String next2 = idx + 2 < l.text.length ? lineAt(l, idx + 2) : songInfo;
            return copy(u, line, next1, next2, pos);
        }
        return copy(u, line, songInfo, u.getAlbum(), pos);
    }

    private static String lineAt(LyricsCore.Lyrics l, int i) {
        String t = l.text[i];
        return t.length() == 0 ? NOTE : t;
    }

    private static PlayerStateAdapter.PlayerStateUpdate copy(PlayerStateAdapter.PlayerStateUpdate u, String title, String artist, long pos) {
        return copy(u, title, artist, u.getAlbum(), pos);
    }

    private static PlayerStateAdapter.PlayerStateUpdate copy(PlayerStateAdapter.PlayerStateUpdate u, String title, String artist, String album, long pos) {
        List<PlayerStateAdapter.QueueItem> q = u.getQueue();
        return new PlayerStateAdapter.PlayerStateUpdate(
                u.getId(), title, artist, album, u.getDurationMs(), pos, u.getPlaybackState(),
                u.getThumbUrl(), u.getHasNextTrack(), u.isLongFormAudio(), u.getUseRemoteSkip(),
                u.getSkipForwardMs(), u.getSkipBackwardMs(), u.getPlayQueueIndex(), u.getPlayQueueCount(),
                u.getUserRating(), u.getRepeatMode(), u.getShuffleMode(), q, u.getQueueIndex());
    }

    // ------------------------------------------------------------------ "Mehr Text" button

    public static final String ACTION_TOGGLE = "tv.plex.labs.plexamp.lyrics.CYCLE_MODE";
    static final int MODE_OFF = 0, MODE_ON = 1, MODE_EXPANDED = 2;
    // Icons are looked up by name at runtime so the patch survives Plexamp updates (resource IDs change).
    private static final String[] ICON_NAMES = {
            "media3_icon_subtitles_off", // Aus
            "media3_icon_subtitles",     // An
            "media3_icon_feed",          // Erweitert
    };
    private static final int[] ICON_FALLBACK = {0x7f08020f, 0x7f08020e, 0x7f0801d9}; // IDs in Plexamp 4.50.19
    private static final int[] ICONS = {0, 0, 0};

    private static int icon(int m) {
        int id = ICONS[m];
        if (id != 0) return id;
        try {
            Field f = AndroidCarBridge.class.getDeclaredField("appContext");
            f.setAccessible(true);
            android.content.Context ctx = (android.content.Context) f.get(null);
            if (ctx != null) {
                id = ctx.getResources().getIdentifier(ICON_NAMES[m], "drawable", ctx.getPackageName());
                if (id == 0) id = ctx.getResources().getIdentifier("media3_icon_closed_captions", "drawable", ctx.getPackageName());
                if (id != 0) ICONS[m] = id;
            }
        } catch (Throwable ignored) {
        }
        return id != 0 ? id : ICON_FALLBACK[m];
    }
    private static final String[] LABELS_DE = {"Songtext: Aus", "Songtext: An", "Songtext: Erweitert"};
    private static final String[] LABELS_EN = {"Lyrics: Off", "Lyrics: On", "Lyrics: Expanded"};

    private static String label(int m) {
        return I18n.german() ? LABELS_DE[m] : LABELS_EN[m];
    }
    private static volatile int mode = -1; // -1 = not loaded yet

    /** Current mode, loaded once from the app's files dir (default: An). */
    static int mode() {
        int m = mode;
        if (m >= 0) return m;
        m = MODE_ON;
        File f = modeFile();
        if (f != null && f.exists()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                int c = in.read();
                if (c >= '0' && c <= '2') m = c - '0';
            } catch (Throwable ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) {}
            }
        }
        if (f != null) mode = m; // only cache once the files dir is known
        return m;
    }

    private static void setMode(int m) {
        mode = m;
        File f = modeFile();
        if (f == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write('0' + m);
        } catch (Throwable t) {
            Log.w(TAG, "could not save mode", t);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static File modeFile() {
        File sf = serverFile();
        return sf == null ? null : new File(sf.getParentFile(), "plexamp_lyrics_mode");
    }

    /** Hook on CustomActions.getALL_COMMANDS(): allow our command for every controller. */
    public static ImmutableList<SessionCommand> extendCommands(ImmutableList<SessionCommand> list) {
        try {
            ImmutableList.Builder<SessionCommand> b = ImmutableList.builder();
            if (list != null) b.addAll(list);
            b.add(new SessionCommand(ACTION_TOGGLE, new Bundle()));
            return b.build();
        } catch (Throwable t) {
            Log.w(TAG, "extendCommands failed", t);
            return list;
        }
    }

    /** Hook on CustomActions.buildLayout(): append the button while the song has timed lyrics. */
    public static ImmutableList<CommandButton> extendLayout(ImmutableList<CommandButton> list, PlayerStateAdapter.PlayerStateUpdate u) {
        try {
            if (lyrics == null || (u != null && u.isLongFormAudio())) return list;
            CommandButton btn = new CommandButton.Builder()
                    .setSessionCommand(new SessionCommand(ACTION_TOGGLE, new Bundle()))
                    .setDisplayName(label(mode()))
                    .setIconResId(icon(mode()))
                    .build();
            ImmutableList.Builder<CommandButton> b = ImmutableList.builder();
            if (list != null) b.addAll(list);
            b.add(btn);
            return b.build();
        } catch (Throwable t) {
            Log.w(TAG, "extendLayout failed", t);
            return list;
        }
    }

    /** Hook at the start of PlexampSessionCallback.onCustomCommand. Returns null if the command is not ours. */
    public static ListenableFuture<SessionResult> handleCommand(SessionCommand c) {
        try {
            if (c == null || !ACTION_TOGGLE.equals(c.customAction)) return null;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    // An -> Erweitert -> Aus -> An
                    int next = mode() == MODE_ON ? MODE_EXPANDED : mode() == MODE_EXPANDED ? MODE_OFF : MODE_ON;
                    setMode(next);
                    Log.i(TAG, "display mode: " + LABELS_EN[next]);
                    shownIndex = -3; // make tick() push the new layout of the text
                    refreshLayout();
                    if (player != null && lastRaw != null) {
                        long pos = estimatePosition();
                        int idx = currentIndex(pos);
                        PlayerStateAdapter.PlayerStateUpdate out = decorate(lastRaw, pos, idx);
                        shownIndex = idx;
                        decorated = idx >= 0 && lyrics != null;
                        lastProduced = out;
                        player.updateFromState(out);
                    }
                }
            });
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        } catch (Throwable t) {
            Log.w(TAG, "handleCommand failed", t);
            return null;
        }
    }

    /** Re-applies Plexamp's button layout (which now includes or omits our button). */
    private static void refreshLayout() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    PlayerStateAdapter.PlayerStateUpdate u = lastRaw;
                    if (u == null) return;
                    Field f = AndroidCarBridge.class.getDeclaredField("librarySession");
                    f.setAccessible(true);
                    Object session = f.get(null);
                    if (session == null) return;
                    Object layout = CustomActions.INSTANCE.buildLayout(u);
                    Method m = session.getClass().getMethod("setCustomLayout", List.class);
                    m.invoke(session, layout);
                } catch (Throwable t) {
                    Log.w(TAG, "refreshLayout failed", t);
                }
            }
        });
    }

    // Server address and token are entered by the user in the settings dialog (LyricsSettings)
    // and stored in a small file in the app's own files directory.

    static volatile File filesDir;

    static File serverFile() {
        File dir = filesDir;
        if (dir == null) {
            try {
                Field f = AndroidCarBridge.class.getDeclaredField("appContext");
                f.setAccessible(true);
                Object ctx = f.get(null);
                if (ctx != null) {
                    Method getFilesDir = ctx.getClass().getMethod("getFilesDir");
                    dir = (File) getFilesDir.invoke(ctx);
                    filesDir = dir;
                }
            } catch (Throwable ignored) {
            }
        }
        if (dir == null) return null;
        return new File(dir, "plexamp_lyrics_server.properties");
    }

    /** Called from the settings dialog after saving: use the new server right away. */
    static void onServerConfigured(final LyricsCore.Server s) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                lastServer = s;
                serverLoaded = true;
                synchronized (CACHE) {
                    CACHE.clear();
                }
                trackKey = null;
                if (player != null && lastRaw != null && s != null) {
                    // Re-run the current track through transform() so lyrics are fetched now.
                    PlayerStateAdapter.PlayerStateUpdate again =
                            copy(lastRaw, lastRaw.getTitle(), lastRaw.getArtist(), estimatePosition());
                    player.updateFromState(again);
                }
            }
        });
    }

    static LyricsCore.Server loadServer() {
        File f = serverFile();
        if (f == null || !f.exists()) return null;
        FileInputStream in = null;
        try {
            Properties p = new Properties();
            in = new FileInputStream(f);
            p.load(in);
            String base = p.getProperty("base");
            String token = p.getProperty("token");
            if (base == null || token == null || base.length() == 0 || token.length() == 0) return null;
            return new LyricsCore.Server(base, token, null);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    static boolean saveServer(LyricsCore.Server s) {
        File f = serverFile();
        if (f == null) return false;
        if (s == null) return f.delete() || !f.exists();
        FileOutputStream out = null;
        try {
            Properties p = new Properties();
            p.setProperty("base", s.base);
            p.setProperty("token", s.token);
            out = new FileOutputStream(f);
            p.store(out, "Plexamp lyrics mod - server for Android Auto lyrics");
            Log.i(TAG, "saved server " + s.base);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not save server", t);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /** True while Android Auto (or Android Automotive's media app) is connected to the media session. */
    private static boolean isCarActive() {
        long now = SystemClock.elapsedRealtime();
        if (now - carCheckedAt < CAR_CHECK_MS) return carActive;
        carCheckedAt = now;
        boolean active;
        try {
            Field f = AndroidCarBridge.class.getDeclaredField("librarySession");
            f.setAccessible(true);
            Object session = f.get(null);
            active = false;
            if (session != null) {
                Method gc = session.getClass().getMethod("getConnectedControllers");
                List<?> controllers = (List<?>) gc.invoke(session);
                if (controllers != null) {
                    for (Object c : controllers) {
                        Method pn = c.getClass().getMethod("getPackageName");
                        String pkg = String.valueOf(pn.invoke(c));
                        if (pkg.equals("com.google.android.projection.gearhead")
                                || pkg.equals("com.android.car.media")
                                || pkg.startsWith("com.google.android.apps.automotive")) {
                            active = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // If detection breaks, prefer showing lyrics over silently doing nothing.
            active = true;
        }
        if (active != carActive) Log.i(TAG, "Android Auto " + (active ? "connected" : "disconnected"));
        carActive = active;
        return active;
    }
}

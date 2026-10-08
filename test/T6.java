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
import tv.plex.labs.plexamp.lyrics.LyricsCore;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * "No lyrics" must be told apart from temporary failures: only the former may be cached.
 * fetch() returns null when the server definitely has nothing, and throws otherwise.
 */
public class T6 {
    static void ok(boolean c, String m) { if (!c) throw new RuntimeException("FAIL " + m); System.out.println("ok " + m); }

    static HttpServer server(int searchCode, String searchBody) throws Exception {
        HttpServer hs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hs.createContext("/", ex -> {
            byte[] b = searchBody == null ? new byte[0] : searchBody.getBytes("UTF-8");
            ex.sendResponseHeaders(searchCode, searchBody == null ? -1 : b.length);
            if (searchBody != null) ex.getResponseBody().write(b);
            ex.close();
        });
        hs.start();
        return hs;
    }

    public static void main(String[] a) throws Exception {
        // Server knows no such track -> definitive "none" (null)
        HttpServer hs = server(200, "<MediaContainer size=\"0\"></MediaContainer>");
        LyricsCore.Server s = LyricsCore.fromUserInput("http://127.0.0.1:" + hs.getAddress().getPort(), "t");
        ok(LyricsCore.fetch(s, "Unknown", "Nobody") == null, "track not on server -> null (cacheable)");
        hs.stop(0);

        // 404 -> definitive
        hs = server(404, null);
        s = LyricsCore.fromUserInput("http://127.0.0.1:" + hs.getAddress().getPort(), "t");
        ok(LyricsCore.fetch(s, "Song", "Band") == null, "404 -> null (cacheable)");
        hs.stop(0);

        // 500 -> temporary
        hs = server(500, null);
        s = LyricsCore.fromUserInput("http://127.0.0.1:" + hs.getAddress().getPort(), "t");
        boolean threw = false;
        try { LyricsCore.fetch(s, "Song", "Band"); } catch (Exception e) { threw = true; }
        ok(threw, "server error -> exception (not cached)");
        hs.stop(0);

        // 401 -> temporary (wrong/expired token must not mark songs as 'no lyrics')
        hs = server(401, null);
        s = LyricsCore.fromUserInput("http://127.0.0.1:" + hs.getAddress().getPort(), "t");
        threw = false;
        try { LyricsCore.fetch(s, "Song", "Band"); } catch (Exception e) { threw = true; }
        ok(threw, "401 -> exception (not cached)");
        hs.stop(0);

        // nothing listening -> temporary
        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
        s = LyricsCore.fromUserInput("http://127.0.0.1:" + port, "t");
        threw = false;
        try { LyricsCore.fetch(s, "Song", "Band"); } catch (Exception e) { threw = true; }
        ok(threw, "no network / connection refused -> exception (not cached)");
    }
}

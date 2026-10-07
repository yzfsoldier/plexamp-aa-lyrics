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
import com.sun.net.httpserver.*; import java.net.*;
public class T3 {
  static void ok(boolean c,String m){ if(!c) throw new RuntimeException("FAIL "+m); System.out.println("ok "+m);}
  public static void main(String[] a) throws Exception {
    LyricsCore.Server s=LyricsCore.fromUserInput("https://192-168-1-5.abc123.plex.direct:32400/library/metadata/77?X-Plex-Token=AbCdEf123","");
    ok(s.base.equals("https://192-168-1-5.abc123.plex.direct:32400")&&s.token.equals("AbCdEf123")&&s.metadataKey==null,"full XML url");
    s=LyricsCore.fromUserInput("192-168-1-5.abc123.plex.direct:32400"," tok ");
    ok(s.base.equals("https://192-168-1-5.abc123.plex.direct:32400")&&s.token.equals("tok"),"plain address + token");
    ok(LyricsCore.fromUserInput("https://host:32400","")==null,"missing token");
    ok(LyricsCore.fromUserInput("","x")==null,"missing address");
    s=LyricsCore.fromUserInput("https://web.example/path?X-Plex-Token=T1","T2");
    ok(s.token.equals("T2"),"explicit token wins");
    HttpServer hs=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    hs.createContext("/",ex->{int code=ex.getRequestURI().toString().contains("X-Plex-Token=good")?200:401; ex.sendResponseHeaders(code,-1); ex.close();});
    hs.start(); int p=hs.getAddress().getPort();
    ok(LyricsCore.testConnection(LyricsCore.fromUserInput("http://127.0.0.1:"+p,"good"))==null,"test ok");
    String e=LyricsCore.testConnection(LyricsCore.fromUserInput("http://127.0.0.1:"+p,"bad"));
    ok(e.contains("401"),"test 401 -> "+e);
    hs.stop(0);
  }
}

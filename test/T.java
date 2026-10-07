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
import com.sun.net.httpserver.*;
import java.net.*; import java.io.*;
public class T {
  static void ok(boolean c, String m){ if(!c) throw new RuntimeException("FAIL "+m); System.out.println("ok "+m); }
  public static void main(String[] a) throws Exception {
    LyricsCore.Lyrics l = LyricsCore.parseLrc("[ar:x]\n[00:01.50]Hello\n[00:03.00][00:10.00]World <00:03.50>you\n[00:05]\n");
    ok(l.start.length==4,"lrc count"); ok(l.start[0]==1500 && l.text[0].equals("Hello"),"lrc first");
    ok(l.text[1].equals("World you"),"lrc enhanced"); ok(l.indexAt(0)==-1 && l.indexAt(4000)==1 && l.indexAt(6000)==2 && l.indexAt(11000)==3,"indexAt");
    String xml="<MediaContainer size=\"1\"><Lyrics timed=\"1\" provider=\"lf\"><Line startOffset=\"1000\" endOffset=\"2000\"><Span text=\"Erste\"/><Span text=\"Zeile\"/></Line><Line startOffset=\"2500\"><Span text=\"\"/></Line><Line startOffset=\"4000\"><Span text=\"Ümlaut &amp; mehr\"/></Line></Lyrics></MediaContainer>";
    l = LyricsCore.parsePlexXml(xml.getBytes("UTF-8"));
    ok(l!=null && l.start.length==3 && l.text[0].equals("Erste Zeile") && l.text[1].equals("") && l.text[2].equals("Ümlaut & mehr"),"xml");
    LyricsCore.Server s = LyricsCore.serverFromThumb("https://1-2-3-4.abc.plex.direct:32400/photo/:/transcode?width=600&height=600&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F4711%2Fthumb%2F1690000&X-Plex-Token=TOK123");
    ok(s.base.equals("https://1-2-3-4.abc.plex.direct:32400") && s.token.equals("TOK123") && s.metadataKey.equals("4711"),"thumb parse");
    ok(LyricsCore.serverFromThumb("file:///data/x.jpg")==null,"file thumb");
    // fake Plex server
    HttpServer hs = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    hs.createContext("/", ex -> {
      String p = ex.getRequestURI().toString(); String body;
      if(!p.contains("X-Plex-Token=TOK")) body=null;
      else if(p.startsWith("/library/metadata/4711/children")) body="<MediaContainer><Track ratingKey=\"900\" title=\"Other\" grandparentTitle=\"Band\"/><Track ratingKey=\"901\" title=\"My Song!\" grandparentTitle=\"Band\"/></MediaContainer>";
      else if(p.startsWith("/library/metadata/901")) body="<MediaContainer><Track ratingKey=\"901\" title=\"My Song!\"><Media><Part><Stream id=\"1\" streamType=\"2\"/><Stream id=\"55\" streamType=\"4\" key=\"/library/streams/55\" codec=\"lrc\" timed=\"1\"/></Part></Media></Track></MediaContainer>";
      else if(p.startsWith("/library/streams/55?format=xml")) body=xml;
      else body=null;
      byte[] b = body==null? new byte[0] : body.getBytes("UTF-8");
      ex.sendResponseHeaders(body==null?404:200, body==null?-1:b.length); if(body!=null) ex.getResponseBody().write(b); ex.close();
    });
    hs.start();
    int port = hs.getAddress().getPort();
    s = LyricsCore.serverFromThumb("http://127.0.0.1:"+port+"/photo/:/transcode?url=%2Flibrary%2Fmetadata%2F4711%2Fthumb%2F1&X-Plex-Token=TOK");
    l = LyricsCore.fetch(s, "My Song!", "Band");
    ok(l!=null && l.text[0].equals("Erste Zeile"),"end-to-end fetch");
    hs.stop(0);
  }
}

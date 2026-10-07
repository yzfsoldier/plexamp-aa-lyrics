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
public class T2 { public static void main(String[] a){
  LyricsCore.Server s=LyricsCore.serverFromThumb("https://h:32400/photo/:/transcode?url=%2Flibrary%2Fmetadata%2F55%2Fthumb%2F1&X-Plex-Token=T");
  if(!s.metadataKey.equals("55")) throw new RuntimeException("key");
  LyricsCore.Server w=LyricsCore.withoutKey(s);
  if(w.metadataKey!=null||!w.base.equals(s.base)||!w.token.equals(s.token)) throw new RuntimeException("withoutKey");
  if(LyricsCore.withoutKey(null)!=null) throw new RuntimeException("null");
  System.out.println("ok withoutKey");
}}

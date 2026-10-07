import tv.plex.labs.plexamp.lyrics.LyricsCore;
public class T2 { public static void main(String[] a){
  LyricsCore.Server s=LyricsCore.serverFromThumb("https://h:32400/photo/:/transcode?url=%2Flibrary%2Fmetadata%2F55%2Fthumb%2F1&X-Plex-Token=T");
  if(!s.metadataKey.equals("55")) throw new RuntimeException("key");
  LyricsCore.Server w=LyricsCore.withoutKey(s);
  if(w.metadataKey!=null||!w.base.equals(s.base)||!w.token.equals(s.token)) throw new RuntimeException("withoutKey");
  if(LyricsCore.withoutKey(null)!=null) throw new RuntimeException("null");
  System.out.println("ok withoutKey");
}}

package tv.plex.labs.plexamp.lyrics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Platform-independent part of the lyrics mod: talks to the Plex Media Server
 * and parses timed lyrics. No Android classes in here so it can be tested on a JVM.
 */
public final class LyricsCore {

    private LyricsCore() {}

    /** Timed lyrics: sorted start offsets (ms) with the text shown from that point on. */
    public static final class Lyrics {
        public final long[] start;
        public final String[] text;

        Lyrics(long[] start, String[] text) {
            this.start = start;
            this.text = text;
        }

        /** Index of the line active at {@code posMs}, or -1 before the first line. */
        public int indexAt(long posMs) {
            int lo = 0, hi = start.length - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (start[mid] <= posMs) {
                    ans = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return ans;
        }
    }

    /** Server address and token learned from an artwork URL. */
    public static final class Server {
        public final String base;
        public final String token;
        public final String metadataKey; // ratingKey referenced by the artwork, may be null

        Server(String base, String token, String metadataKey) {
            this.base = base;
            this.token = token;
            this.metadataKey = metadataKey;
        }
    }

    /**
     * Turns what the user typed into a server. The address field may be a plain address
     * ("192-168-1-2.abc.plex.direct:32400", "https://host:32400") or a full Plex URL that already
     * contains X-Plex-Token (e.g. copied from "View XML" in Plex Web); then the token field may stay empty.
     * Returns null if the input is incomplete.
     */
    public static Server fromUserInput(String address, String token) {
        String a = address == null ? "" : address.trim();
        String t = token == null ? "" : token.trim();
        if (a.length() == 0) return null;
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "https://" + a;
        try {
            URI u = new URI(a);
            if (u.getRawAuthority() == null || u.getHost() == null) return null;
            String base = u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getRawAuthority();
            if (t.length() == 0) {
                String fromUrl = queryParam(u.getRawQuery(), "X-Plex-Token");
                if (fromUrl != null) t = fromUrl.trim();
            }
            if (t.length() == 0) return null;
            return new Server(base, t, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Checks address + token against the server. Returns null on success, else a short German error text. */
    public static String testConnection(Server s) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(withToken(s.base, "/library/sections", s.token)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/xml");
            try {
                int code = c.getResponseCode();
                if (code == 200) return null;
                if (code == 401 || code == 403) return "Token wird vom Server abgelehnt (HTTP " + code + ").";
                return "Server antwortet mit HTTP " + code + ".";
            } finally {
                c.disconnect();
            }
        } catch (java.net.UnknownHostException e) {
            return "Adresse nicht gefunden: " + e.getMessage();
        } catch (java.net.SocketTimeoutException e) {
            return "Zeitüberschreitung – Server nicht erreichbar.";
        } catch (javax.net.ssl.SSLException e) {
            return "HTTPS-Fehler: " + e.getMessage();
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m.toLowerCase(Locale.ROOT).contains("cleartext")) {
                return "Unverschlüsseltes HTTP ist gesperrt – bitte die https://…plex.direct:32400-Adresse verwenden.";
            }
            return e.getClass().getSimpleName() + ": " + m;
        }
    }

    /** Same server/token but with the per-track album key dropped, for reuse on a different track. */
    public static Server withoutKey(Server s) {
        if (s == null || s.metadataKey == null) return s;
        return new Server(s.base, s.token, null);
    }

    private static final Pattern METADATA_KEY = Pattern.compile("/library/metadata/(\\d+)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    // ---------------------------------------------------------------- URL helpers

    static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            if (k.equalsIgnoreCase(name)) {
                String v = eq >= 0 ? part.substring(eq + 1) : "";
                try {
                    return URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    return v;
                }
            }
        }
        return null;
    }

    /**
     * Plexamp sends artwork as a photo-transcoder URL on the server, e.g.
     * https://host:32400/photo/:/transcode?width=..&url=%2Flibrary%2Fmetadata%2F123%2Fthumb%2F1&X-Plex-Token=...
     * From that we learn where the server is and how to authenticate.
     */
    public static Server serverFromThumb(String thumb) {
        if (thumb == null) return null;
        String t = thumb.trim();
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null;
        try {
            URI outer = new URI(t);
            String q = outer.getRawQuery();
            String token = queryParam(q, "X-Plex-Token");
            String base = outer.getScheme() + "://" + outer.getRawAuthority();
            String inner = queryParam(q, "url");
            String keySource = outer.getRawPath();
            if (inner != null && inner.length() > 0) {
                keySource = inner;
                if (inner.startsWith("http://") || inner.startsWith("https://")) {
                    URI in = new URI(inner);
                    String innerToken = queryParam(in.getRawQuery(), "X-Plex-Token");
                    String host = in.getHost();
                    boolean loopback = host == null || host.equals("127.0.0.1") || host.equals("localhost");
                    if (!loopback) {
                        base = in.getScheme() + "://" + in.getRawAuthority();
                        if (innerToken != null) token = innerToken;
                    }
                }
            }
            if (token == null || token.length() == 0) return null;
            String key = null;
            Matcher m = METADATA_KEY.matcher(keySource == null ? "" : keySource);
            if (m.find()) key = m.group(1);
            return new Server(base, token, key);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- HTTP

    static String withToken(String base, String path, String token) {
        String sep = path.indexOf('?') >= 0 ? "&" : "?";
        return base + path + sep + "X-Plex-Token=" + enc(token);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/xml");
        c.setRequestProperty("X-Plex-Product", "Plexamp");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) break;
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Throwable ignored) {
        }
        try {
            f.setExpandEntityReferences(false);
        } catch (Throwable ignored) {
        }
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(data));
    }

    // ---------------------------------------------------------------- lookup

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** Picks the best matching track element; returns its ratingKey or null. */
    static String pickTrack(NodeList tracks, String title, String artist) {
        String nt = norm(title), na = norm(artist);
        String fallback = null;
        for (int i = 0; i < tracks.getLength(); i++) {
            Element e = (Element) tracks.item(i);
            if (!norm(e.getAttribute("title")).equals(nt)) continue;
            String rk = e.getAttribute("ratingKey");
            if (rk.length() == 0) continue;
            if (na.length() == 0
                    || norm(e.getAttribute("grandparentTitle")).equals(na)
                    || norm(e.getAttribute("originalTitle")).equals(na)) {
                return rk;
            }
            if (fallback == null) fallback = rk;
        }
        return fallback;
    }

    static String findTrackKey(Server s, String title, String artist) {
        if (s.metadataKey != null) {
            // Artwork usually points at the album: look through its tracks.
            try {
                Document d = parseXml(get(withToken(s.base, "/library/metadata/" + s.metadataKey + "/children", s.token)));
                String rk = pickTrack(d.getElementsByTagName("Track"), title, artist);
                if (rk != null) return rk;
            } catch (Exception ignored) {
            }
            // ...or directly at the track itself.
            try {
                Document d = parseXml(get(withToken(s.base, "/library/metadata/" + s.metadataKey, s.token)));
                String rk = pickTrack(d.getElementsByTagName("Track"), title, artist);
                if (rk != null) return rk;
            } catch (Exception ignored) {
            }
        }
        // Fallback: library search by title.
        try {
            Document d = parseXml(get(withToken(s.base, "/search?type=10&query=" + enc(title), s.token)));
            return pickTrack(d.getElementsByTagName("Track"), title, artist);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Returns the key (e.g. /library/streams/123) of the best lyric stream, or null. */
    static String findLyricStream(Server s, String trackKey) throws Exception {
        Document d = parseXml(get(withToken(s.base, "/library/metadata/" + trackKey, s.token)));
        NodeList streams = d.getElementsByTagName("Stream");
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < streams.getLength(); i++) {
            Element e = (Element) streams.item(i);
            if (!"4".equals(e.getAttribute("streamType"))) continue;
            String key = e.getAttribute("key");
            if (key.length() == 0) continue;
            String codec = (e.getAttribute("codec") + " " + e.getAttribute("format")).toLowerCase(Locale.ROOT);
            int score = 0;
            if ("1".equals(e.getAttribute("timed"))) score += 4;
            if (codec.contains("lrc")) score += 2;
            if (codec.contains("txt")) score -= 1;
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        return best;
    }

    /** Parses the Plex XML lyrics format: MediaContainer/Lyrics/Line[@startOffset]/Span[@text]. */
    public static Lyrics parsePlexXml(byte[] data) {
        try {
            Document d = parseXml(data);
            NodeList lyricsNodes = d.getElementsByTagName("Lyrics");
            if (lyricsNodes.getLength() == 0) return null;
            Element ly = (Element) lyricsNodes.item(0);
            String timed = ly.getAttribute("timed");
            if (timed.length() > 0 && !"1".equals(timed) && !"true".equalsIgnoreCase(timed)) return null;
            NodeList lines = ly.getElementsByTagName("Line");
            List<long[]> starts = new ArrayList<long[]>();
            List<String> texts = new ArrayList<String>();
            for (int i = 0; i < lines.getLength(); i++) {
                Element line = (Element) lines.item(i);
                String so = line.getAttribute("startOffset");
                if (so.length() == 0) continue;
                long start;
                try {
                    start = Long.parseLong(so.trim());
                } catch (NumberFormatException nfe) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                NodeList spans = line.getElementsByTagName("Span");
                for (int j = 0; j < spans.getLength(); j++) {
                    String t = ((Element) spans.item(j)).getAttribute("text");
                    if (t.length() == 0) continue;
                    if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))
                            && !Character.isWhitespace(t.charAt(0))) {
                        sb.append(' ');
                    }
                    sb.append(t);
                }
                if (spans.getLength() == 0) sb.append(line.getTextContent());
                starts.add(new long[]{start, i});
                texts.add(sb.toString().replaceAll("\\s+", " ").trim());
            }
            return build(starts, texts);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses classic LRC text ([mm:ss.xx] line). */
    public static Lyrics parseLrc(String body) {
        if (body == null) return null;
        List<long[]> starts = new ArrayList<long[]>();
        List<String> texts = new ArrayList<String>();
        int order = 0;
        for (String raw : body.split("\r?\n")) {
            Matcher m = LRC_TIME.matcher(raw);
            List<Long> times = new ArrayList<Long>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long frac = 0;
                String f = m.group(3);
                if (f != null) {
                    if (f.length() == 1) frac = Long.parseLong(f) * 100;
                    else if (f.length() == 2) frac = Long.parseLong(f) * 10;
                    else frac = Long.parseLong(f);
                }
                times.add(min * 60000 + sec * 1000 + frac);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "").trim();
            for (Long t : times) {
                starts.add(new long[]{t, order++});
                texts.add(text);
            }
        }
        return build(starts, texts);
    }

    private static Lyrics build(final List<long[]> starts, List<String> texts) {
        if (starts.isEmpty()) return null;
        Integer[] idx = new Integer[starts.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                long[] x = starts.get(a), y = starts.get(b);
                if (x[0] != y[0]) return x[0] < y[0] ? -1 : 1;
                return Long.compare(x[1], y[1]);
            }
        });
        long[] s = new long[idx.length];
        String[] t = new String[idx.length];
        for (int i = 0; i < idx.length; i++) {
            s[i] = starts.get(idx[i])[0];
            t[i] = texts.get(idx[i]);
        }
        boolean anyText = false;
        for (String x : t) if (x.length() > 0) anyText = true;
        if (!anyText) return null;
        // A single line at 0 ms means the lyrics are not really timed.
        if (s.length > 1 && s[s.length - 1] == 0) return null;
        return new Lyrics(s, t);
    }

    /** Full lookup: track -> lyric stream -> timed lines. Returns null when nothing usable exists. */
    public static Lyrics fetch(Server s, String title, String artist) throws Exception {
        String trackKey = findTrackKey(s, title, artist);
        if (trackKey == null) return null;
        String streamKey = findLyricStream(s, trackKey);
        if (streamKey == null) return null;
        String sep = streamKey.indexOf('?') >= 0 ? "&" : "?";
        try {
            byte[] xml = get(withToken(s.base, streamKey + sep + "format=xml&includeInlineAttribution=1", s.token));
            Lyrics l = parsePlexXml(xml);
            if (l != null) return l;
            Lyrics lrc = parseLrc(new String(xml, "UTF-8"));
            if (lrc != null) return lrc;
        } catch (Exception ignored) {
        }
        byte[] rawBody = get(withToken(s.base, streamKey, s.token));
        return parseLrc(new String(rawBody, "UTF-8"));
    }
}

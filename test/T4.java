import tv.plex.labs.plexamp.lyrics.LyricsCore;
import com.sun.net.httpserver.*;
import java.net.*;
import java.util.Locale;

/** Checks that user-facing messages follow the phone language (German / English). */
public class T4 {
    static void ok(boolean c, String m) { if (!c) throw new RuntimeException("FAIL " + m); System.out.println("ok " + m); }

    public static void main(String[] a) throws Exception {
        HttpServer hs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hs.createContext("/", ex -> { ex.sendResponseHeaders(401, -1); ex.close(); });
        hs.start();
        LyricsCore.Server s = LyricsCore.fromUserInput("http://127.0.0.1:" + hs.getAddress().getPort(), "bad");

        Locale.setDefault(Locale.GERMANY);
        String de = LyricsCore.testConnection(s);
        ok(de.startsWith("Token wird vom Server abgelehnt"), "German message: " + de);

        Locale.setDefault(Locale.US);
        String en = LyricsCore.testConnection(s);
        ok(en.startsWith("The server rejected the token"), "English message: " + en);

        Locale.setDefault(Locale.FRANCE);
        ok(LyricsCore.testConnection(s).startsWith("The server rejected"), "other languages fall back to English");
        hs.stop(0);
    }
}

package tv.plex.labs.plexamp.lyrics;

import java.util.Locale;

/** Minimal localization: German when the phone is set to German, English otherwise. */
final class I18n {

    private I18n() {}

    static boolean german() {
        return "de".equals(Locale.getDefault().getLanguage());
    }

    /** Picks the German or English variant depending on the current phone language. */
    static String t(String de, String en) {
        return german() ? de : en;
    }
}

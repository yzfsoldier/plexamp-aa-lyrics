package tv.plex.labs.plexamp.lyrics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;

/**
 * Settings dialog for the lyrics mod. Opened via a launcher shortcut
 * (long-press the Plexamp icon -> "Songtexte AA" / "Lyrics AA"). Texts follow the phone language (de/en). Hooked into MainActivity.onCreate/onNewIntent.
 */
public final class LyricsSettings {

    private static final String TAG = "PlexampLyricsMod";
    public static final String ACTION = "tv.plex.labs.plexamp.lyrics.SETTINGS";
    private static final String SHORTCUT_ID = "plexamp_lyrics_settings";

    private static boolean shortcutInstalled;

    private LyricsSettings() {}

    /** Hook from MainActivity.onCreate / onNewIntent. Never throws. */
    public static void onIntent(final Activity activity, Intent intent) {
        try {
            if (activity == null) return;
            if (LyricsHook.filesDir == null) LyricsHook.filesDir = activity.getApplicationContext().getFilesDir();
            installShortcut(activity);
            if (intent != null && ACTION.equals(intent.getAction())) {
                intent.setAction(Intent.ACTION_MAIN); // don't reopen on configuration changes
                activity.getWindow().getDecorView().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            show(activity);
                        } catch (Throwable t) {
                            Log.w(TAG, "settings dialog failed", t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "onIntent failed", t);
        }
    }

    private static void installShortcut(Activity a) {
        if (shortcutInstalled || Build.VERSION.SDK_INT < 25) return;
        shortcutInstalled = true;
        try {
            ShortcutManager sm = (ShortcutManager) a.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) return;
            Intent it = new Intent(ACTION);
            it.setClassName(a.getPackageName(), a.getClass().getName());
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int icon = a.getApplicationInfo().icon;
            ShortcutInfo.Builder b = new ShortcutInfo.Builder(a, SHORTCUT_ID)
                    .setShortLabel(I18n.t("Songtexte AA", "Lyrics AA"))
                    .setLongLabel(I18n.t("Songtexte für Android Auto", "Lyrics for Android Auto"))
                    .setIntent(it);
            if (icon != 0) b.setIcon(Icon.createWithResource(a, icon));
            sm.addDynamicShortcuts(Collections.singletonList(b.build()));
        } catch (Throwable t) {
            Log.w(TAG, "could not add shortcut", t);
        }
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static void show(final Activity a) {
        LyricsCore.Server current = LyricsHook.loadServer();

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        box.setPadding(pad, dp(a, 8), pad, 0);

        TextView info = new TextView(a);
        info.setText(I18n.t(
                "Damit in Android Auto Songtexte angezeigt werden, braucht der Patch die Adresse deines "
                        + "Plex-Servers und dein Plex-Token.\n\n"
                        + "Am einfachsten: In Plex Web bei einem Titel \u201eInformationen \u2192 XML anzeigen\u201c "
                        + "öffnen und die komplette Adresse aus der Adresszeile hier einfügen \u2013 das Token steckt darin, "
                        + "das zweite Feld kann dann leer bleiben.\n\n"
                        + "Bitte die https-Adresse (\u2026plex.direct:32400) verwenden.",
                "To show lyrics in Android Auto, the patch needs the address of your Plex server "
                        + "and your Plex token.\n\n"
                        + "Easiest way: in Plex Web, open \u201cGet Info \u2192 View XML\u201d for any track and paste "
                        + "the full address from the address bar here \u2013 it already contains the token, "
                        + "so the second field can stay empty.\n\n"
                        + "Please use the https address (\u2026plex.direct:32400)."));
        box.addView(info);

        final EditText address = new EditText(a);
        address.setHint(I18n.t("Server-Adresse oder komplette Plex-URL", "Server address or full Plex URL"));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        if (current != null) address.setText(current.base);
        box.addView(address);

        final EditText token = new EditText(a);
        token.setHint(I18n.t("X-Plex-Token (leer lassen, wenn in der URL enthalten)", "X-Plex-Token (leave empty if it is in the URL)"));
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSingleLine(true);
        if (current != null) token.setText(current.token);
        box.addView(token);

        final TextView status = new TextView(a);
        status.setPadding(0, dp(a, 8), 0, 0);
        status.setText(current != null ? I18n.t("Gespeichert: ", "Saved: ") + current.base
                : I18n.t("Noch kein Server eingetragen.", "No server set up yet."));
        box.addView(status);

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(I18n.t("Songtexte für Android Auto", "Lyrics for Android Auto"))
                .setView(box)
                .setPositiveButton(I18n.t("Speichern", "Save"), null)
                .setNeutralButton(I18n.t("Testen", "Test"), null)
                .setNegativeButton(I18n.t("Abbrechen", "Cancel"), null)
                .create();

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                Button save = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                Button test = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);

                save.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String addr = address.getText().toString().trim();
                        String tok = token.getText().toString().trim();
                        if (addr.length() == 0 && tok.length() == 0) {
                            LyricsHook.saveServer(null);
                            LyricsHook.onServerConfigured(null);
                            Toast.makeText(a, I18n.t("Server-Eintrag gelöscht", "Server entry removed"), Toast.LENGTH_SHORT).show();
                            dlg.dismiss();
                            return;
                        }
                        LyricsCore.Server s = LyricsCore.fromUserInput(addr, tok);
                        if (s == null) {
                            status.setText(I18n.t("Bitte Adresse und Token eintragen (oder eine URL mit X-Plex-Token einfügen).",
                                    "Please enter address and token (or paste a URL containing X-Plex-Token)."));
                            return;
                        }
                        if (!LyricsHook.saveServer(s)) {
                            status.setText(I18n.t("Konnte nicht speichern.", "Could not save."));
                            return;
                        }
                        LyricsHook.onServerConfigured(s);
                        Toast.makeText(a, I18n.t("Gespeichert \u2013 Songtexte werden geladen", "Saved \u2013 loading lyrics"), Toast.LENGTH_SHORT).show();
                        dlg.dismiss();
                    }
                });

                test.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final LyricsCore.Server s = LyricsCore.fromUserInput(
                                address.getText().toString(), token.getText().toString());
                        if (s == null) {
                            status.setText(I18n.t("Bitte Adresse und Token eintragen (oder eine URL mit X-Plex-Token einfügen).",
                                    "Please enter address and token (or paste a URL containing X-Plex-Token)."));
                            return;
                        }
                        status.setText(I18n.t("Teste Verbindung zu ", "Testing connection to ") + s.base + " \u2026");
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String err = LyricsCore.testConnection(s);
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText(err == null
                                                ? "\u2713 " + I18n.t("Verbindung klappt (", "Connection works (") + s.base
                                                        + I18n.t("). Jetzt speichern.", "). Now tap Save.")
                                                : "✗ " + err);
                                    }
                                });
                            }
                        }, "plexamp-lyrics-test");
                        t.setDaemon(true);
                        t.start();
                    }
                });
            }
        });
        dlg.show();
    }
}

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
 * (long-press the Plexamp icon -> "Songtexte AA"). Hooked into MainActivity.onCreate/onNewIntent.
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
                    .setShortLabel("Songtexte AA")
                    .setLongLabel("Songtexte für Android Auto")
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
        info.setText("Damit in Android Auto Songtexte angezeigt werden, braucht der Patch die Adresse deines "
                + "Plex-Servers und dein Plex-Token.\n\n"
                + "Am einfachsten: In Plex Web bei einem Titel „Informationen → XML anzeigen“ "
                + "öffnen und die komplette Adresse aus der Adresszeile hier einfügen – das Token steckt darin, "
                + "das zweite Feld kann dann leer bleiben.\n\n"
                + "Bitte die https-Adresse (…plex.direct:32400) verwenden.");
        box.addView(info);

        final EditText address = new EditText(a);
        address.setHint("Server-Adresse oder komplette Plex-URL");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        if (current != null) address.setText(current.base);
        box.addView(address);

        final EditText token = new EditText(a);
        token.setHint("X-Plex-Token (leer lassen, wenn in der URL enthalten)");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSingleLine(true);
        if (current != null) token.setText(current.token);
        box.addView(token);

        final TextView status = new TextView(a);
        status.setPadding(0, dp(a, 8), 0, 0);
        status.setText(current != null ? "Gespeichert: " + current.base : "Noch kein Server eingetragen.");
        box.addView(status);

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Songtexte für Android Auto")
                .setView(box)
                .setPositiveButton("Speichern", null)
                .setNeutralButton("Testen", null)
                .setNegativeButton("Abbrechen", null)
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
                            Toast.makeText(a, "Server-Eintrag gelöscht", Toast.LENGTH_SHORT).show();
                            dlg.dismiss();
                            return;
                        }
                        LyricsCore.Server s = LyricsCore.fromUserInput(addr, tok);
                        if (s == null) {
                            status.setText("Bitte Adresse und Token eintragen (oder eine URL mit X-Plex-Token einfügen).");
                            return;
                        }
                        if (!LyricsHook.saveServer(s)) {
                            status.setText("Konnte nicht speichern.");
                            return;
                        }
                        LyricsHook.onServerConfigured(s);
                        Toast.makeText(a, "Gespeichert – Songtexte werden geladen", Toast.LENGTH_SHORT).show();
                        dlg.dismiss();
                    }
                });

                test.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final LyricsCore.Server s = LyricsCore.fromUserInput(
                                address.getText().toString(), token.getText().toString());
                        if (s == null) {
                            status.setText("Bitte Adresse und Token eintragen (oder eine URL mit X-Plex-Token einfügen).");
                            return;
                        }
                        status.setText("Teste Verbindung zu " + s.base + " …");
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String err = LyricsCore.testConnection(s);
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText(err == null
                                                ? "✓ Verbindung klappt (" + s.base + "). Jetzt speichern."
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

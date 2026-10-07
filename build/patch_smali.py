#!/usr/bin/env python3
"""
Setzt die Haken des Lyrics-Patches in den von apktool dekompilierten Plexamp-Code (smali).

Aufruf:  python3 patch_smali.py <apktool-Ausgabeordner>

Jeder Patch sucht eine feste Ankerstelle. Passt die bei einer neuen Plexamp-Version
nicht mehr, bricht das Skript mit einer klaren Meldung ab, statt still eine kaputte
APK zu bauen. Bereits gepatchte Dateien werden erkannt und übersprungen.
"""
import glob
import os
import sys

HOOK = "Ltv/plex/labs/plexamp/lyrics/"


class PatchError(Exception):
    pass


def find_class(root, cls):
    """Findet die smali-Datei einer Klasse in smali/, smali_classes2/, ..."""
    hits = glob.glob(os.path.join(root, "smali*", *cls.split("/")) + ".smali")
    if not hits:
        raise PatchError(f"Klasse {cls} nicht gefunden")
    return hits[0]


def replace_once(text, old, new, what):
    n = text.count(old)
    if n != 1:
        raise PatchError(f"{what}: Ankerstelle {n}x gefunden (erwartet genau 1)")
    return text.replace(old, new)


# ---------------------------------------------------------------- einzelne Patches

def patch_treble_player(root):
    """TreblePlayer.updateFromState: jede Wiedergabe-Aktualisierung läuft durch LyricsHook.transform."""
    f = find_class(root, "tv/plex/labs/plexamp/media/TreblePlayer")
    s = open(f, encoding="utf-8").read()
    if HOOK + "LyricsHook;->transform" in s:
        return "bereits gepatcht"
    anchor = "    iput-object v1, v0, Ltv/plex/labs/plexamp/media/TreblePlayer;->lastUpdate:Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;\n"
    hook = ("    invoke-static {v0, v1}, Ltv/plex/labs/plexamp/lyrics/LyricsHook;->transform("
            "Ltv/plex/labs/plexamp/media/TreblePlayer;Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)"
            "Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;\n\n"
            "    move-result-object v1\n\n")
    s = replace_once(s, anchor, hook + anchor, "TreblePlayer.updateFromState")
    open(f, "w", encoding="utf-8").write(s)
    return "ok"


def patch_main_activity(root):
    """MainActivity.onCreate/onNewIntent: Einstellungsdialog + Kurzbefehl am App-Icon."""
    f = find_class(root, "tv/plex/labs/plexamp/MainActivity")
    s = open(f, encoding="utf-8").read()
    if HOOK + "LyricsSettings;->onIntent" in s:
        return "bereits gepatcht"
    call = ("    invoke-static {p0, p1}, Ltv/plex/labs/plexamp/lyrics/LyricsSettings;->onIntent("
            "Landroid/app/Activity;Landroid/content/Intent;)V\n\n")
    done = 0
    out = []
    for block in s.split(".end method"):
        header = block[block.rfind(".method"):] if ".method" in block else ""
        is_target = (".method protected onCreate(Landroid/os/Bundle;)V" in header
                     or ".method public onNewIntent(Landroid/content/Intent;)V" in header)
        if is_target:
            idx = block.rfind("    return-void")
            if idx < 0:
                raise PatchError("MainActivity: return-void nicht gefunden")
            # In beiden Methoden steht das Intent an dieser Stelle in p1.
            if "onCreate" in header and "getIntent()Landroid/content/Intent;\n\n    move-result-object p1" not in block:
                raise PatchError("MainActivity.onCreate: Intent liegt nicht in p1 (Code hat sich geändert)")
            block = block[:idx] + call + block[idx:]
            done += 1
        out.append(block)
    if done != 2:
        raise PatchError(f"MainActivity: {done} von 2 Methoden gefunden")
    open(f, "w", encoding="utf-8").write(".end method".join(out))
    return "ok"


def patch_custom_actions(root):
    """CustomActions: eigene Taste in der Android-Auto-Leiste und erlaubter Befehl."""
    f = find_class(root, "tv/plex/labs/plexamp/media/CustomActions")
    s = open(f, encoding="utf-8").read()
    if "lyricsOrig" in s:
        return "bereits gepatcht"
    s = replace_once(s,
                     ".method public final getALL_COMMANDS()Lcom/google/common/collect/ImmutableList;",
                     ".method public final getALL_COMMANDS$lyricsOrig()Lcom/google/common/collect/ImmutableList;",
                     "CustomActions.getALL_COMMANDS")
    s = replace_once(s,
                     ".method public final buildLayout(Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)Lcom/google/common/collect/ImmutableList;",
                     ".method public final buildLayout$lyricsOrig(Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)Lcom/google/common/collect/ImmutableList;",
                     "CustomActions.buildLayout")
    s += """
.method public final getALL_COMMANDS()Lcom/google/common/collect/ImmutableList;
    .locals 1

    invoke-virtual {p0}, Ltv/plex/labs/plexamp/media/CustomActions;->getALL_COMMANDS$lyricsOrig()Lcom/google/common/collect/ImmutableList;

    move-result-object v0

    invoke-static {v0}, Ltv/plex/labs/plexamp/lyrics/LyricsHook;->extendCommands(Lcom/google/common/collect/ImmutableList;)Lcom/google/common/collect/ImmutableList;

    move-result-object v0

    return-object v0
.end method

.method public final buildLayout(Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)Lcom/google/common/collect/ImmutableList;
    .locals 1

    invoke-virtual {p0, p1}, Ltv/plex/labs/plexamp/media/CustomActions;->buildLayout$lyricsOrig(Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)Lcom/google/common/collect/ImmutableList;

    move-result-object v0

    invoke-static {v0, p1}, Ltv/plex/labs/plexamp/lyrics/LyricsHook;->extendLayout(Lcom/google/common/collect/ImmutableList;Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)Lcom/google/common/collect/ImmutableList;

    move-result-object v0

    return-object v0
.end method
"""
    open(f, "w", encoding="utf-8").write(s)
    return "ok"


def patch_session_callback(root):
    """PlexampSessionCallback.onCustomCommand: Druck auf unsere Taste abfangen."""
    f = find_class(root, "tv/plex/labs/plexamp/media/PlexampSessionCallback")
    s = open(f, encoding="utf-8").read()
    if HOOK + "LyricsHook;->handleCommand" in s:
        return "bereits gepatcht"
    anchor = ('    const-string p1, "args"\n\n'
              '    invoke-static {p4, p1}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V\n')
    hook = """
    invoke-static {p3}, Ltv/plex/labs/plexamp/lyrics/LyricsHook;->handleCommand(Landroidx/media3/session/SessionCommand;)Lcom/google/common/util/concurrent/ListenableFuture;

    move-result-object v0

    if-eqz v0, :lyrics_not_ours

    return-object v0

    :lyrics_not_ours
"""
    s = replace_once(s, anchor, anchor + hook, "PlexampSessionCallback.onCustomCommand")
    open(f, "w", encoding="utf-8").write(s)
    return "ok"


def check_members(root):
    """Prüft, dass Felder, auf die der Patch per Reflection zugreift, noch existieren."""
    f = find_class(root, "tv/plex/labs/plexamp/media/AndroidCarBridge")
    s = open(f, encoding="utf-8").read()
    for field in ("appContext:Landroid/content/Context;",
                  "librarySession:Landroidx/media3/session/MediaLibraryService$MediaLibrarySession;"):
        if field not in s:
            raise PatchError(f"AndroidCarBridge: Feld {field.split(':')[0]} fehlt")
    return "ok"


PATCHES = [
    ("TreblePlayer (Songtext-Anzeige)", patch_treble_player),
    ("MainActivity (Einstellungen)", patch_main_activity),
    ("CustomActions (Taste)", patch_custom_actions),
    ("PlexampSessionCallback (Tastendruck)", patch_session_callback),
    ("AndroidCarBridge (Felder prüfen)", check_members),
]


def main():
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print(__doc__)
        sys.exit(2)
    root = sys.argv[1]
    failed = False
    for name, fn in PATCHES:
        try:
            print(f"  {name}: {fn(root)}")
        except PatchError as e:
            print(f"  {name}: FEHLER – {e}")
            failed = True
    if failed:
        print("\nMindestens ein Patch passt nicht zu dieser Plexamp-Version. Abbruch.")
        sys.exit(1)


if __name__ == "__main__":
    main()

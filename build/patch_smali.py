#!/usr/bin/env python3
"""
Inserts the lyrics patch hooks into Plexamp's code as decompiled by apktool (smali).

Usage:  python3 patch_smali.py <apktool output folder>

Every patch looks for a fixed anchor. If that anchor no longer matches in a new
Plexamp version, the script stops with a clear message instead of silently building
a broken APK. Files that are already patched are detected and skipped.
"""
import glob
import os
import sys

HOOK = "Ltv/plex/labs/plexamp/lyrics/"


class PatchError(Exception):
    pass


def find_class(root, cls):
    """Finds the smali file of a class in smali/, smali_classes2/, ..."""
    hits = glob.glob(os.path.join(root, "smali*", *cls.split("/")) + ".smali")
    if not hits:
        raise PatchError(f"class {cls} not found")
    return hits[0]


def replace_once(text, old, new, what):
    n = text.count(old)
    if n != 1:
        raise PatchError(f"{what}: anchor found {n} times (expected exactly 1)")
    return text.replace(old, new)


# ---------------------------------------------------------------- individual patches

def patch_treble_player(root):
    """TreblePlayer.updateFromState: every playback update goes through LyricsHook.transform."""
    f = find_class(root, "tv/plex/labs/plexamp/media/TreblePlayer")
    s = open(f, encoding="utf-8").read()
    if HOOK + "LyricsHook;->transform" in s:
        return "already patched"
    anchor = "    iput-object v1, v0, Ltv/plex/labs/plexamp/media/TreblePlayer;->lastUpdate:Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;\n"
    hook = ("    invoke-static {v0, v1}, Ltv/plex/labs/plexamp/lyrics/LyricsHook;->transform("
            "Ltv/plex/labs/plexamp/media/TreblePlayer;Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;)"
            "Ltv/plex/labs/plexamp/media/PlayerStateAdapter$PlayerStateUpdate;\n\n"
            "    move-result-object v1\n\n")
    s = replace_once(s, anchor, hook + anchor, "TreblePlayer.updateFromState")
    open(f, "w", encoding="utf-8").write(s)
    return "ok"


def patch_main_activity(root):
    """MainActivity.onCreate/onNewIntent: settings dialog + app-icon shortcut."""
    f = find_class(root, "tv/plex/labs/plexamp/MainActivity")
    s = open(f, encoding="utf-8").read()
    if HOOK + "LyricsSettings;->onIntent" in s:
        return "already patched"
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
                raise PatchError("MainActivity: return-void not found")
            # In both methods the intent is in p1 at this point.
            if "onCreate" in header and "getIntent()Landroid/content/Intent;\n\n    move-result-object p1" not in block:
                raise PatchError("MainActivity.onCreate: intent is not in p1 (code has changed)")
            block = block[:idx] + call + block[idx:]
            done += 1
        out.append(block)
    if done != 2:
        raise PatchError(f"MainActivity: found {done} of 2 methods")
    open(f, "w", encoding="utf-8").write(".end method".join(out))
    return "ok"


def patch_custom_actions(root):
    """CustomActions: our button in the Android Auto control bar and its allowed command."""
    f = find_class(root, "tv/plex/labs/plexamp/media/CustomActions")
    s = open(f, encoding="utf-8").read()
    if "lyricsOrig" in s:
        return "already patched"
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
    """PlexampSessionCallback.onCustomCommand: intercept presses of our button."""
    f = find_class(root, "tv/plex/labs/plexamp/media/PlexampSessionCallback")
    s = open(f, encoding="utf-8").read()
    if HOOK + "LyricsHook;->handleCommand" in s:
        return "already patched"
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
    """Checks that fields the patch accesses via reflection still exist."""
    f = find_class(root, "tv/plex/labs/plexamp/media/AndroidCarBridge")
    s = open(f, encoding="utf-8").read()
    for field in ("appContext:Landroid/content/Context;",
                  "librarySession:Landroidx/media3/session/MediaLibraryService$MediaLibrarySession;"):
        if field not in s:
            raise PatchError(f"AndroidCarBridge: field {field.split(':')[0]} is missing")
    return "ok"


PATCHES = [
    ("TreblePlayer (lyrics display)", patch_treble_player),
    ("MainActivity (settings)", patch_main_activity),
    ("CustomActions (button)", patch_custom_actions),
    ("PlexampSessionCallback (button press)", patch_session_callback),
    ("AndroidCarBridge (check fields)", check_members),
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
            print(f"  {name}: ERROR – {e}")
            failed = True
    if failed:
        print("\nAt least one patch does not match this Plexamp version. Aborting.")
        sys.exit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
# Plexamp AA Lyrics – synced lyrics for Plexamp on Android Auto
# Copyright (C) 2026 yzfsoldier
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under the GNU General Public License v3 or later – see LICENSE.
# Builds the Android Auto lyrics version from an original Plexamp APK.
#
#   ./build.sh <Plexamp.apk> [output.apk]
#
# Requirements: Java (JDK 11 or newer, with javac), python3, curl, unzip, zip.
# Runs on Linux, macOS and on Windows in WSL.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/build/tools"
WORK="$ROOT/build/work"

# Signing key – the same key allows updating without uninstalling.
KEYSTORE="${KEYSTORE:-$ROOT/ks.jks}"
KS_ALIAS="${KS_ALIAS:-plexamp-lyrics}"
KS_PASS="${KS_PASS:-plexlyrics}"

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "== $*"; }

[ $# -ge 1 ] || die "usage: $0 <Plexamp.apk> [output.apk]"
IN_APK="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
[ -f "$IN_APK" ] || die "APK not found: $1"
OUT_APK="${2:-$ROOT/$(basename "${IN_APK%.apk}")-AA-Lyrics.apk}"

for c in java javac python3 curl unzip zip; do
  command -v "$c" >/dev/null || die "'$c' is missing – please install it"
done
if [ ! -f "$KEYSTORE" ]; then
  command -v keytool >/dev/null || die "'keytool' is missing (it comes with the JDK)"
  echo "No signing key found – creating a new one: $KEYSTORE"
  echo "  (To update an already installed patched version, put its ks.jks here instead!)"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Plexamp Lyrics Mod" >/dev/null 2>&1 || die "could not create the signing key"
fi

step "1/7 Tools"
bash "$ROOT/build/fetch-tools.sh"
D2J="$TOOLS/dex-tools-v2.4"

step "2/7 Decompiling the APK (without resources)"
rm -rf "$WORK"; mkdir -p "$WORK"
java -jar "$TOOLS/apktool.jar" d -r -f -o "$WORK/dec" "$IN_APK" >/dev/null

step "3/7 Hooking into Plexamp's code"
python3 "$ROOT/build/patch_smali.py" "$WORK/dec"

step "4/7 Compiling the patch"
mkdir -p "$WORK/stubs" "$WORK/classes"
javac --release 8 -nowarn -Xlint:none -cp "$TOOLS/android.jar" -d "$WORK/stubs" \
  $(find "$ROOT/stubs" -name '*.java') 2>&1 | grep -v -i "warning\|obsolete" || true
javac --release 8 -nowarn -Xlint:none -cp "$TOOLS/android.jar:$WORK/stubs" -d "$WORK/classes" \
  $(find "$ROOT/src" -name '*.java') 2>&1 | grep -v -i "warning\|obsolete" || true
[ -f "$WORK/classes/tv/plex/labs/plexamp/lyrics/LyricsHook.class" ] || die "compilation failed"
(cd "$WORK/classes" && zip -qr "$WORK/mod.jar" tv)
"$D2J/d2j-jar2dex.sh" -f -o "$WORK/mod.dex" "$WORK/mod.jar" >/dev/null 2>&1
[ -s "$WORK/mod.dex" ] || die "dex conversion failed"

step "5/7 Running self-tests"
mkdir -p "$WORK/test"
javac --release 8 -nowarn -cp "$WORK/classes" -d "$WORK/test" "$ROOT"/test/*.java 2>&1 \
  | grep -v -i "warning\|obsolete" || true
for f in "$ROOT"/test/*.java; do
  t="$(basename "$f" .java)"
  java -cp "$WORK/classes:$WORK/test" "$t" >/dev/null || die "test $t failed"
done
echo "  all tests passed"

step "6/7 Rebuilding the APK"
java -jar "$TOOLS/apktool.jar" b "$WORK/dec" -o "$WORK/rebuilt.apk" >/dev/null
# apktool reassembles every dex from smali. Only the dex files that contain our hooks need that;
# all others are taken unchanged from the original APK, so Plexamp's own code stays byte-identical.
python3 - "$IN_APK" "$WORK/rebuilt.apk" "$WORK/mod.dex" "$WORK/dec" "$WORK/unsigned.apk" <<'EOF'
import os, re, sys, zipfile
orig_apk, rebuilt_apk, mod_dex, dec, out_apk = sys.argv[1:6]

def dex_name(smali_dir):            # smali -> classes.dex, smali_classes3 -> classes3.dex
    m = re.fullmatch(r"smali(?:_classes(\d+))?", smali_dir)
    return f"classes{m.group(1) or ''}.dex"

hooked = set()
for d in os.listdir(dec):
    if not re.fullmatch(r"smali(_classes\d+)?", d):
        continue
    for root, _, files in os.walk(os.path.join(dec, d)):
        for f in files:
            with open(os.path.join(root, f), encoding="utf-8", errors="ignore") as fh:
                if "Ltv/plex/labs/plexamp/lyrics/" in fh.read():
                    hooked.add(dex_name(d))
                    break
        if dex_name(d) in hooked:
            break

orig = zipfile.ZipFile(orig_apk)
with zipfile.ZipFile(rebuilt_apk) as src, zipfile.ZipFile(out_apk, "w") as dst:
    dex_files = []
    for info in src.infolist():
        data = src.read(info.filename)
        if re.fullmatch(r"classes\d*\.dex", info.filename):
            dex_files.append(info.filename)
            if info.filename not in hooked:
                data = orig.read(info.filename)   # keep Plexamp's original code
        dst.writestr(info, data)
    nums = [int(re.fullmatch(r"classes(\d*)\.dex", n).group(1) or 1) for n in dex_files]
    target = f"classes{max(nums) + 1}.dex"
    dst.write(mod_dex, target, compress_type=zipfile.ZIP_DEFLATED)

kept = sorted(set(dex_files) - hooked)
print(f"  original dex kept: {', '.join(kept)}")
print(f"  rebuilt with hooks: {', '.join(sorted(hooked))}")
print(f"  added the patch as {target}")
EOF

step "7/7 Aligning and signing"
rm -rf "$WORK/signed"
java -jar "$TOOLS/uber-apk-signer.jar" -a "$WORK/unsigned.apk" -o "$WORK/signed" \
  --ks "$KEYSTORE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" >"$WORK/sign.log" 2>&1 \
  || { cat "$WORK/sign.log"; die "signing failed"; }
mv "$WORK"/signed/*-aligned-signed.apk "$OUT_APK"

echo
echo "Done: $OUT_APK"
if command -v sha256sum >/dev/null; then sha256sum "$OUT_APK"; else shasum -a 256 "$OUT_APK"; fi

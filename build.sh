#!/usr/bin/env bash
# Baut aus einer originalen Plexamp-APK die Version mit Songtexten für Android Auto.
#
#   ./build.sh <Plexamp.apk> [Ausgabe.apk]
#
# Voraussetzungen: Java (JDK 11 oder neuer, mit javac), python3, curl, unzip, zip.
# Läuft unter Linux, macOS und unter Windows in WSL.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/build/tools"
WORK="$ROOT/build/work"

# Signierschlüssel – derselbe Schlüssel erlaubt Updates ohne Deinstallation.
KEYSTORE="${KEYSTORE:-$ROOT/ks.jks}"
KS_ALIAS="${KS_ALIAS:-plexamp-lyrics}"
KS_PASS="${KS_PASS:-plexlyrics}"

die() { echo "FEHLER: $*" >&2; exit 1; }
step() { echo; echo "== $*"; }

[ $# -ge 1 ] || die "Aufruf: $0 <Plexamp.apk> [Ausgabe.apk]"
IN_APK="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
[ -f "$IN_APK" ] || die "APK nicht gefunden: $1"
OUT_APK="${2:-$ROOT/$(basename "${IN_APK%.apk}")-AA-Lyrics.apk}"

for c in java javac python3 curl unzip zip; do
  command -v "$c" >/dev/null || die "'$c' fehlt – bitte installieren"
done
if [ ! -f "$KEYSTORE" ]; then
  command -v keytool >/dev/null || die "'keytool' fehlt (gehört zum JDK)"
  echo "Kein Signierschlüssel gefunden – erzeuge neuen: $KEYSTORE"
  echo "  (Für Updates einer schon installierten Version den alten ks.jks hierher legen!)"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Plexamp Lyrics Mod" >/dev/null 2>&1 || die "Schlüssel konnte nicht erzeugt werden"
fi

step "1/7 Werkzeuge"
bash "$ROOT/build/fetch-tools.sh"
D2J="$TOOLS/dex-tools-v2.4"

step "2/7 APK zerlegen (ohne Ressourcen)"
rm -rf "$WORK"; mkdir -p "$WORK"
java -jar "$TOOLS/apktool.jar" d -r -f -o "$WORK/dec" "$IN_APK" >/dev/null

step "3/7 Haken in den Plexamp-Code setzen"
python3 "$ROOT/build/patch_smali.py" "$WORK/dec"

step "4/7 Patch kompilieren"
mkdir -p "$WORK/stubs" "$WORK/classes"
javac --release 8 -nowarn -Xlint:none -cp "$TOOLS/android.jar" -d "$WORK/stubs" \
  $(find "$ROOT/stubs" -name '*.java') 2>&1 | grep -v -i "warning\|obsolete" || true
javac --release 8 -nowarn -Xlint:none -cp "$TOOLS/android.jar:$WORK/stubs" -d "$WORK/classes" \
  $(find "$ROOT/src" -name '*.java') 2>&1 | grep -v -i "warning\|obsolete" || true
[ -f "$WORK/classes/tv/plex/labs/plexamp/lyrics/LyricsHook.class" ] || die "Kompilieren fehlgeschlagen"
(cd "$WORK/classes" && zip -qr "$WORK/mod.jar" tv)
"$D2J/d2j-jar2dex.sh" -f -o "$WORK/mod.dex" "$WORK/mod.jar" >/dev/null 2>&1
[ -s "$WORK/mod.dex" ] || die "dex-Erzeugung fehlgeschlagen"

step "5/7 Selbsttest der Songtext-Logik"
mkdir -p "$WORK/test"
javac --release 8 -nowarn -cp "$WORK/classes" -d "$WORK/test" "$ROOT"/test/*.java 2>/dev/null \
  && for t in T T2 T3; do java -cp "$WORK/classes:$WORK/test" "$t" >/dev/null || die "Test $t fehlgeschlagen"; done \
  && echo "  alle Tests bestanden"

step "6/7 APK zusammenbauen"
java -jar "$TOOLS/apktool.jar" b "$WORK/dec" -o "$WORK/unsigned.apk" >/dev/null
python3 - "$WORK/unsigned.apk" "$WORK/mod.dex" <<'EOF'
import re, sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
names = zipfile.ZipFile(apk).namelist()
nums = [int(m.group(1) or 1) for n in names for m in [re.fullmatch(r"classes(\d*)\.dex", n)] if m]
target = f"classes{max(nums) + 1}.dex"
with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex, target)
print(f"  Patch als {target} eingefügt")
EOF

step "7/7 Ausrichten und signieren"
rm -rf "$WORK/signed"
java -jar "$TOOLS/uber-apk-signer.jar" -a "$WORK/unsigned.apk" -o "$WORK/signed" \
  --ks "$KEYSTORE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" >"$WORK/sign.log" 2>&1 \
  || { cat "$WORK/sign.log"; die "Signieren fehlgeschlagen"; }
mv "$WORK"/signed/*-aligned-signed.apk "$OUT_APK"

echo
echo "Fertig: $OUT_APK"
if command -v sha256sum >/dev/null; then sha256sum "$OUT_APK"; else shasum -a 256 "$OUT_APK"; fi

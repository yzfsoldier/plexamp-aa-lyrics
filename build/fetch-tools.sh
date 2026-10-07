#!/usr/bin/env bash
# Lädt die Build-Werkzeuge nach build/tools/ (einmalig, ca. 80 MB). Alles von GitHub.
set -euo pipefail

TOOLS="$(cd "$(dirname "$0")" && pwd)/tools"
mkdir -p "$TOOLS"
cd "$TOOLS"

get() { # url datei
  if [ -s "$2" ]; then echo "  vorhanden: $2"; return; fi
  echo "  lade $2 …"
  curl -fsSL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# apktool: APK in smali zerlegen und wieder zusammenbauen
get https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar apktool.jar

# dex-tools (dex2jar): enthält dx, um Java-Klassen in Android-dex umzuwandeln
get https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip dex-tools.zip
if [ ! -d dex-tools-v2.4 ]; then unzip -q dex-tools.zip; chmod +x dex-tools-v2.4/*.sh; fi

# uber-apk-signer: zipalign + Signieren
get https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar uber-apk-signer.jar

# Android-API (nur zum Kompilieren, wird nicht in die APK eingebaut)
get https://raw.githubusercontent.com/Sable/android-platforms/master/android-28/android.jar android.jar

echo "Werkzeuge bereit in $TOOLS"

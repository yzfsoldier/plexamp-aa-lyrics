#!/usr/bin/env bash
# Downloads the build tools into build/tools/ (once, about 90 MB). Everything comes from GitHub.
set -euo pipefail

TOOLS="$(cd "$(dirname "$0")" && pwd)/tools"
mkdir -p "$TOOLS"
cd "$TOOLS"

get() { # url file
  if [ -s "$2" ]; then echo "  present: $2"; return; fi
  echo "  downloading $2 …"
  curl -fsSL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# apktool: decompiles the APK to smali and rebuilds it
get https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar apktool.jar

# dex-tools (dex2jar): contains dx, which turns Java classes into Android dex
get https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip dex-tools.zip
if [ ! -d dex-tools-v2.4 ]; then unzip -q dex-tools.zip; chmod +x dex-tools-v2.4/*.sh; fi

# uber-apk-signer: zipalign + signing
get https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar uber-apk-signer.jar

# Android API (only used for compiling, not added to the APK)
get https://raw.githubusercontent.com/Sable/android-platforms/master/android-28/android.jar android.jar

echo "Tools ready in $TOOLS"

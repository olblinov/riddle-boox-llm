#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$ROOT/work/android-tools/sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$ROOT"/work/android-tools/jdk-*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
export PATH="$JAVA_HOME/bin:$PATH"
VARIANT="${1:-main}"
case "$VARIANT" in main|--test) ;; *) printf 'Usage: %s [--test]\n' "$0" >&2; exit 2 ;; esac
BUILD="$ROOT/android/build"
SOURCE_DIR="$ROOT/android/app/src/main/java/com/booxreview"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
OUTPUT="$ROOT/outputs/boox-review-debug.apk"
if [ "$VARIANT" = --test ]; then
  BUILD="$ROOT/android/build-test"
  rm -rf "$BUILD/source"
  mkdir -p "$BUILD/source"
  for source in "$SOURCE_DIR"/*.java; do
    sed 's/package com.booxreview;/package com.booxreview.test;/' "$source" > "$BUILD/source/$(basename "$source")"
  done
  sed -e 's/package="com.booxreview"/package="com.booxreview.test"/' -e 's/android:label="BOOX Review"/android:debuggable="true" android:label="BOOX Review Test"/' "$MANIFEST" > "$BUILD/source/AndroidManifest.xml"
  SOURCE_DIR="$BUILD/source"
  MANIFEST="$BUILD/source/AndroidManifest.xml"
  OUTPUT="$ROOT/work/boox-review-test.apk"
fi
TOOLS="$SDK/build-tools/35.0.0"
rm -rf "$BUILD/classes"
mkdir -p "$BUILD/classes" "$ROOT/outputs"
"$TOOLS/aapt2" link -o "$BUILD/base.apk" -I "$SDK/platforms/android-35/android.jar" --manifest "$MANIFEST" --min-sdk-version 28 --target-sdk-version 30 --version-code 8 --version-name 0.8.0
python3 "$ROOT/android/prepare-sdk.py"
SDK_CLASSPATH="$(printf '%s:' "$ROOT"/android/build-sdk/*.jar)"
javac --release 8 -classpath "$SDK/platforms/android-35/android.jar:$SDK_CLASSPATH" -d "$BUILD/classes" "$SOURCE_DIR"/*.java
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --lib "$SDK/platforms/android-35/android.jar" --min-api 28 --output "$BUILD" "$BUILD/classes.jar" "$ROOT"/android/build-sdk/*.jar
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD" && zip -q unsigned.apk classes*.dex)
(cd "$ROOT/android/build-sdk" && zip -qr "$BUILD/unsigned.apk" lib)
"$TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KEY="$ROOT/work/android-tools/debug.keystore"
if [ ! -f "$KEY" ]; then
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey -dname 'CN=BOOX Review Development' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out "$OUTPUT" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUTPUT"
printf '%s\n' "$OUTPUT"

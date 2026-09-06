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
SOURCE="$ROOT/android/app/src/main/java/com/booxreview/MainActivity.java"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
OUTPUT="$ROOT/outputs/boox-review-debug.apk"
if [ "$VARIANT" = --test ]; then
  BUILD="$ROOT/android/build-test"
  mkdir -p "$BUILD/source"
  sed 's/package com.booxreview;/package com.booxreview.test;/' "$SOURCE" > "$BUILD/source/MainActivity.java"
  sed -e 's/package="com.booxreview"/package="com.booxreview.test"/' -e 's/android:label="BOOX Review"/android:label="BOOX Review Test"/' "$MANIFEST" > "$BUILD/source/AndroidManifest.xml"
  SOURCE="$BUILD/source/MainActivity.java"
  MANIFEST="$BUILD/source/AndroidManifest.xml"
  OUTPUT="$ROOT/work/boox-review-test.apk"
fi
TOOLS="$SDK/build-tools/35.0.0"
rm -rf "$BUILD/classes"
mkdir -p "$BUILD/classes" "$ROOT/outputs"
"$TOOLS/aapt2" link -o "$BUILD/base.apk" -I "$SDK/platforms/android-35/android.jar" --manifest "$MANIFEST" --min-sdk-version 28 --target-sdk-version 30 --version-code 3 --version-name 0.3.0
javac --release 8 -classpath "$SDK/platforms/android-35/android.jar" -d "$BUILD/classes" "$SOURCE"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --lib "$SDK/platforms/android-35/android.jar" --min-api 28 --output "$BUILD" "$BUILD/classes.jar"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD" && zip -q unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KEY="$ROOT/work/android-tools/debug.keystore"
if [ ! -f "$KEY" ]; then
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey -dname 'CN=BOOX Review Development' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out "$OUTPUT" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUTPUT"
printf '%s\n' "$OUTPUT"

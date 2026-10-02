#!/usr/bin/env bash
set -euo pipefail
ROOT="$(dirname "$(realpath "$0")")"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-36/android.jar}"
test -f "$ROOT/build/watch/classes.jar"
mkdir -p "$ROOT/build/instrumentation-classes" "$ROOT/build/instrumentation-dex"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$ANDROID_JAR:$ROOT/build/watch/classes.jar" -d "$ROOT/build/instrumentation-classes" "$ROOT/tests/StoreInstrumentation.java"
jar cf "$ROOT/build/instrumentation.jar" -C "$ROOT/build/instrumentation-classes" .
d8 --lib "$ANDROID_JAR" --classpath "$ROOT/build/watch/classes.jar" --min-api 30 --output "$ROOT/build/instrumentation-dex" "$ROOT/build/instrumentation.jar"
aapt2 link -o "$ROOT/build/tests-unsigned.apk" -I "$ANDROID_JAR" --manifest "$ROOT/tests/AndroidManifest.xml"
jar uf "$ROOT/build/tests-unsigned.apk" -C "$ROOT/build/instrumentation-dex" classes.dex
zipalign -f 4 "$ROOT/build/tests-unsigned.apk" "$ROOT/build/tests-aligned.apk"
apksigner sign --ks "$ROOT/keys/development.jks" --ks-pass pass:android --key-pass pass:android --out "$ROOT/build/watch-notes-tests.apk" "$ROOT/build/tests-aligned.apk"
apksigner verify "$ROOT/build/watch-notes-tests.apk"

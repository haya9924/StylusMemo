#!/usr/bin/env bash
# Build on the remote machine (invoked by mainframer: mf -p <project> bash scripts/remote-build.sh)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
java -version
echo "sdk.dir=$ANDROID_HOME" > local.properties
free -h | head -2

./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug --console=plain

echo "=== APK ==="
ls -la app/build/outputs/apk/debug/

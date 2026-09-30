set -e
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties
adb devices
# The debug keystore is per-user; a stale APK signed with another user's key can make
# installation fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE, so always uninstall first.
adb uninstall com.stylusmemo.app >/dev/null 2>&1 || true
adb uninstall com.stylusmemo.app.test >/dev/null 2>&1 || true
./gradlew --no-daemon :app:connectedDebugAndroidTest --console=plain

#!/bin/sh
# build.sh [install]: builds RadBrowser.apk with the bare SDK tools (no Gradle) and, with "install",
# puts it on the Onn over adb. Your box's address goes in $TV (for example TV=192.168.1.50:5555); it is only needed for "install".
set -e
here=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
TV="${TV:-}"
out="$here/build"
rm -rf "$out"; mkdir -p "$out/classes" "$out/dex"

"$BT/aapt2" compile --dir "$here/res" -o "$out/res.zip"
"$BT/aapt2" link -o "$out/base.apk" -I "$JAR" --manifest "$here/AndroidManifest.xml" -A "$here/assets" "$out/res.zip"
javac --release 11 -classpath "$JAR" -nowarn \
  -d "$out/classes" $(find "$here/src" -name '*.java')
"$BT/d8" --min-api 31 --lib "$JAR" --output "$out/dex" $(find "$out/classes" -name '*.class')
cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"

ks="$here/debug.keystore"
[ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -storepass android -keypass android \
  -alias rad -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=RadBrowser" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android \
  --out "$here/RadBrowser.apk" "$out/aligned.apk"
echo "built $here/RadBrowser.apk"

if [ "$1" = "install" ]; then
  [ -n "$TV" ] || { echo "Set TV=<your box address>:5555 first, for example: TV=192.168.1.50:5555 sh build.sh install"; exit 1; }
  adb -s "$TV" install -r "$here/RadBrowser.apk"
fi

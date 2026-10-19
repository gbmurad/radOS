# Install radOS

## What you need

- A Mac with **JDK 17** and the **Android SDK** (build tools 34.0.0 and the Android 34 platform). Android Studio installs both, or use the command line tools. Linux should work too, but we have not tried it.
- `adb` on your path.
- An Android TV or Google TV box running **Android 12 or newer**.

The build scripts look for the JDK in `$JAVA_HOME` (default `/opt/homebrew/opt/openjdk@17`) and the SDK in `$ANDROID_HOME` (default `~/Library/Android/sdk`).

## 1. Let the computer talk to the box

1. On the box, open Settings, find the build number (usually under System, About) and press OK on it seven times. Developer options appear.
2. In Developer options, turn on USB debugging or network debugging.
3. Find the box's address in its network settings, then connect and accept the prompt on the TV:

```sh
adb connect 192.168.1.50:5555
```

## 2. Build and install

From the repo folder, with your own address:

```sh
TV=192.168.1.50:5555 sh home/build.sh install
TV=192.168.1.50:5555 sh browser/build.sh install
TV=192.168.1.50:5555 sh keys/build.sh install
```

Leave off `install` to only build. The first build makes a debug signing key in each folder (`debug.keystore`). Keep it: updates must be signed with the same key, or Android refuses them until you uninstall the old copy.

## 3. Turn on the Home screen helper

On the box: Settings, Accessibility, **Home screen helper**, on. Or from the computer:

```sh
adb shell settings put secure enabled_accessibility_services com.zoro.home/com.zoro.home.HomeGuard
adb shell settings put secure accessibility_enabled 1
```

Press Home. radOS opens.

## 4. Use Glass Keys as the keyboard

```sh
adb shell ime enable com.zoro.keys/.GlassKeys
adb shell ime set com.zoro.keys/.GlassKeys
```

## 5. Set your city and calendars (optional)

Write a `config.json` (see [Customize](customize.md)) and push it:

```sh
adb shell mkdir -p /sdcard/Android/data/com.zoro.home/files
adb push config.json /sdcard/Android/data/com.zoro.home/files/config.json
```

## Undo everything

```sh
adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
adb uninstall com.zoro.keys
adb uninstall com.zoro.rad
adb uninstall com.zoro.home
```

The first line puts back Google's keyboard (Gboard). If you removed Google's launcher, put it back first; see [Remove the bloat](debloat.md).

## Troubleshooting

- **Home opens Google's screen again.** The Home screen helper got switched off, usually after a force stop. Turn it back on (step 3).
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The installed copy was signed with a different key. Uninstall it, then install again.

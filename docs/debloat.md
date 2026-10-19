# Remove the bloat

This is optional, and done at your own risk. It is what we did on an onn 4K Pro (Google TV, Android 14). Your box may ship different apps.

`pm uninstall --user 0` removes an app for your user only. The app stays in the system image, nothing is deleted for good, and any app comes back with one command:

```sh
adb shell pm uninstall --user 0 <package>          # remove
adb shell cmd package install-existing <package>    # put it back
```

## The 16 apps we removed

| Package | App |
|---|---|
| `com.haystack.android` | Haystack News |
| `com.tubitv` | Tubi |
| `com.google.android.youtube.tvunplugged` | YouTube TV |
| `com.google.android.youtube.tvmusic` | YouTube Music |
| `com.disney.disneyplus` | Disney+ |
| `com.hulu.livingroomplus` | Hulu |
| `com.netflix.ninja` | Netflix (we later put it back) |
| `com.netflix.tokenmanager` | Netflix helper |
| `com.tcl.browser` | BrowseHere browser |
| `com.instagram.airwave` | Instagram |
| `com.google.android.play.games` | Google Play Games |
| `com.google.android.feedback` | Google Feedback |
| `com.android.tv.feedbackconsent` | Feedback consent screen |
| `com.onn.updatenotification` | onn update notices |
| `com.android.printspooler` | Print service |
| `com.google.android.marvin.talkback` | TalkBack screen reader |

Keep TalkBack if anyone in your home relies on a screen reader.

## Removing Google's launcher (advanced)

With radOS in place, Google's own home screen only runs in the background and holds memory. On our box this worked:

```sh
adb shell pm uninstall --user 0 com.google.android.apps.tv.launcherx
```

After that the box starts straight into radOS in about 26 seconds. A black screen flashes briefly at start, and the Home screen helper covers it.

- **Do not use `pm disable` on the launcher.** Google TV then loops its recovery screen and the TV goes black.
- **Never touch `com.google.android.tungsten.setupwraith`.**
- Put the launcher back with:

```sh
adb shell cmd package install-existing com.google.android.apps.tv.launcherx
```

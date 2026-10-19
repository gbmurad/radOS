# How radOS works

radOS is three small Android apps built with the plain Android SDK tools (aapt2, javac, d8, zipalign, apksigner). There is no Gradle and no third party library inside any of them.

| Folder | App | Package |
|---|---|---|
| `home/` | The boot screen, the home screen and the Home screen helper | `com.zoro.home` |
| `browser/` | Rad Browser, Rad Shield and the widgets | `com.zoro.rad` |
| `keys/` | Glass Keys, the keyboard | `com.zoro.keys` |

## Startup

1. While Android is still unlocking storage, it would normally show a blank screen. `BootSplash` is allowed to run that early, so it plays the radOS animation instead.
2. When Android starts the home screen, it carries the same animation on from the same moment, so there is no jump.
3. After about six and a half seconds the animation fades and the home screen is already filled in from what it saved last time.

## Making radOS the home screen on Google TV

Google TV will not let you switch its own launcher off. The Home screen helper (`HomeGuard`, an accessibility service) solves that:

- A short press of Home opens radOS directly, before Google's launcher starts.
- A long press still does what Google TV does with it (the quick settings panel).
- If Google's home screen shows up any other way, radOS is put on top of it.

The helper reads no screen content. It only watches the Home key. You can also remove Google's launcher entirely; see [Remove the bloat](debloat.md).

## The home screen

| Card | Source | How often |
|---|---|---|
| Weather | Open-Meteo, for the place in `config.json` | every 15 minutes |
| Scores | ESPN's public scoreboard feeds for NFL, NBA, MLB, NHL and college football | every minute |
| Up Next | Your calendar links, plus an optional `calendar.json` | every 15 minutes |
| Photo | Wikimedia Commons featured pictures of landscapes, mountains, lakes and beaches, or your own `photos/` folder | a new photo every 10 seconds |
| Apps | Every TV app installed on the box | when apps change |

On start, every card first paints what it saved last time, then updates from the network. The TV fetches everything itself, so no computer needs to be on.

The calendar reader handles one off events plus simple daily, weekly, monthly and yearly repeats over the next two weeks. Weekly repeats limited to certain weekdays are skipped. The same event from two sources shows once.

## Rad Browser

Rad Browser is an Android WebView with its own remote controls on top.

- **Pointer.** A small view that slides without redrawing the page. While an arrow is held it speeds up, and at the screen edge it scrolls the page with a short pretend finger drag that stops cleanly instead of flinging.
- **Back menu.** Back, or holding OK, opens a glass panel drawn over a blurred snapshot of the page.
- **Voice.** The mic button opens Google's speech screen. What you say is searched, or opened if it sounds like a site.
- **Start page.** A local page inside the app (`assets/start.html`) that talks to the app through a small bridge, so it can fetch your calendar and other data a web page could not read on its own.

### Rad Shield

`Shields.java` loads five public filter lists and answers three questions:

1. Should this request (a script, image, frame or tracker) be blocked on this page?
2. Should this new window be stopped? This catches popups and redirect hijacks.
3. Which element hiding rules apply to this page?

Plain blocked hosts go in a hash set. Every other rule is filed under one distinctive word from its pattern, so each request is only tested against a handful of rules instead of all of them. The first page after a cold start waits briefly for the lists, so no ad slips through at startup. Lists are kept on the box and refreshed every four days.

## Glass Keys

An Android input method, so it works in every app. The dictionary is about 30,000 English words with how common each one is. As you type, it offers completions and the most likely correction, and autocorrect keeps your capital letters. When it closes it tells Rad Browser, so the search bar drops back down at once.

## What it talks to

| Service | Used by | What for |
|---|---|---|
| `api.open-meteo.com`, `geocoding-api.open-meteo.com` | Home screen, start page, weather widget | Weather, and finding a city you type |
| `site.api.espn.com` | Home screen, scores widget | Scores |
| `commons.wikimedia.org`, `upload.wikimedia.org` | Home screen, start page | Scenic photos and wallpapers |
| Your calendar links | Home screen, start page | Up Next |
| `easylist.to`, `ublockorigin.github.io`, `pgl.yoyo.org` | Rad Shield | Filter lists |
| `www.google.com` | Rad Browser | Search, and site icons for your favorites (Google sees the names of those sites) |
| `speed.cloudflare.com` | Speed test widget | Ping and download speed |
| Google's speech service on the box | Rad Browser | Voice search, only when you press the mic |

Everything else is the web pages you choose to open.

## Measurements

All numbers come from one onn 4K Pro (Google TV, Android 14, 3 GB of memory) unless noted.

| Number | How it was measured | When |
|---|---|---|
| Google's launcher held about 295 MB | `adb shell dumpsys meminfo`, Total PSS by process | Oct 18, 2026 |
| The radOS home screen holds about 98 MB | `adb shell dumpsys meminfo com.zoro.home`, TOTAL PSS, two minutes after a restart | Oct 19, 2026 |
| About 26 seconds from restart to the radOS home | `adb reboot`, then polling until Android reported boot complete with the radOS home on top | Oct 18, 2026 |
| 145,896 rules, loaded in 185 ms | `sh browser/tests/run.sh` on a Mac, with lists downloaded Oct 18, 2026 | Oct 19, 2026 |
| About 0.19 ms per request, 14 of 14 cases right | Same test, 2,800 timed checks | Oct 19, 2026 |
| 100 out of 100 on AdBlock Tester (11 services, 22 checks) | adblock-tester.com opened in Rad Browser on the TV, score read from the page | Oct 19, 2026 |
| App sizes: home 2.9 MB (mostly the boot video and chime), browser 362 KB, keyboard 193 KB | Installed APK sizes on the box | Oct 19, 2026 |

## Known limits

- Tested on one box. Remote buttons differ between brands.
- On the onn remote, the mic and app buttons never reach apps, which is why Rad Browser has its own mic button.
- Never force stop `com.zoro.home` (for example `adb shell am force-stop com.zoro.home`). Android then switches the Home screen helper off and you have to turn it back on. Installing an update with `adb install -r` keeps it on.

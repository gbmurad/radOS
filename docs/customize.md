# Customize radOS

## Without touching code

### Your city and calendars

Push this file to `/sdcard/Android/data/com.zoro.home/files/config.json`:

```json
{
  "place": "Denver",
  "lat": 39.7392,
  "lon": -104.9903,
  "ics": [
    "https://example.com/my-calendar.ics",
    "webcal://example.com/shared.ics"
  ]
}
```

- `place` is the name shown with the weather; `lat` and `lon` set where the forecast is for.
- `ics` is any list of calendar links. Apple Calendar (a public calendar's link), Google Calendar (the secret address in iCal format) and Outlook all give one. `webcal://` links work too.
- Anyone with a calendar link can read that calendar, so keep the file private.

### Events from anywhere else

Push a list of events to `/sdcard/Android/data/com.zoro.home/files/calendar.json` and they appear in Up Next next to your calendar links:

```json
[
  {"title": "Game night", "start": "2026-10-20T19:00:00-07:00", "end": "2026-10-20T22:00:00-07:00", "allDay": false, "color": "#5AA9FF"}
]
```

### Your own photos

Push pictures to `/sdcard/Android/data/com.zoro.home/files/photos/` and they replace the scenic set.

### With the remote

- **Move an app:** hold OK on it, slide it with Left and Right, press OK to drop it or Back to cancel.
- **Move the scores or Up Next card:** hold OK on it and move it with the arrows.
- **Widgets in Rad Browser:** press the plus button on the start page to add one. Hold OK on a widget for its options: size, style, move and its own settings, such as the city for weather.
- **Shields off for one site:** open the Back menu on that site and choose Shields off.

### From a computer

Push a list of favorites to Rad Browser and it is merged in the next time Rad opens:

```sh
# favorites: [{"title": "Wikipedia", "url": "https://en.wikipedia.org"}]
adb push add-favorites.json /sdcard/Android/data/com.zoro.rad/files/add-favorites.json
```

## In the code

Change a value, rebuild with `sh build.sh install`, done.

| To change | Where |
|---|---|
| Which apps come first and last | `PINNED` and `TRAILING` in `home/src/com/zoro/home/HomeActivity.java` |
| Which leagues the home screen shows | `LEAGUES` in the same file |
| Photo, score and weather timing | `PHOTO_MS`, `SCORE_REFRESH_MS`, `SLOW_REFRESH_MS` in the same file |
| Colors | `TEXT`, `DIM`, `ACCENT`, `LIVE` at the top of the same file |
| Where the start page weather is for | `HOME` in `browser/assets/start.html` |
| Which filter lists Rad Shield uses | `LISTS` in `browser/src/com/zoro/rad/BrowserActivity.java` |
| How often the lists refresh | `LIST_MAX_AGE` in the same file |
| The widgets and their leagues | `KINDS` and `LEAGUES` in `browser/assets/widgets.js` |
| The keyboard's words | `keys/assets/words.txt`, one word and its count per line |
| The boot animation and chime | `home/assets/globe.mp4`, `home/assets/startup.wav`, and `home/tools/` for the scripts that made them |

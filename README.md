# Scroll Cast Radio

An Android app that plays internet radio as an endless vertical feed: swipe up for the next
station, like reels. It's minimal by design and fully usable with TalkBack.

## Features
- **Random feed** from [Radio Browser](https://www.radio-browser.info). The app discovers the live
  mirrors and fails over between them. Each batch is a weighted random mix, so popular stations
  come up a bit more often.
- **More sources** under Settings → *Stations from*: [SomaFM](https://somafm.com),
  [Radio Paradise](https://radioparadise.com) and [laut.fm](https://laut.fm), all public and
  key-free. Turn on several to mix them into one feed: each region/language share of a batch is
  split evenly between the sources that have matching stations, and a stream listed by two
  sources only plays once.
- **Your own sources**: Settings → *Stations from* → *Add a source* takes a link to an M3U / M3U8
  playlist (IPTV-style `#EXTINF` attributes give logo, country and categories), a PLS or JSON
  station list, a single stream, or a GitHub repository, which you browse a folder at a time to
  pick a playlist. The last download is kept, so a source still plays when its host is down.
  Dev builds add example buttons (iptv-org TV, junguler radio playlists); release builds don't.
  Each source has a Share button: `<pages site>/s/?source=<list url>&n=<name>` (or
  `scrollcast://source?url=…`) asks before adding that list on another phone. Stations from a
  source share as `?id=user-…&n=<name>&u=<stream>`, so they play without the list.
- **Video streams** (TV channels, HLS or DASH with video) play their sound only. Video starts
  switched off, so streams with separate audio download audio alone. HLS channels that pack the
  sound into each video variant fall back to their lowest variant. The only video renderer
  discards frames, so no video decoder runs.
- **Region detection** from the mobile network or SIM country, falling back to the device
  locale. No location permission is needed.
- **Settings** is a short menu of pages: *Stations from*, *Feed*, *Audio* and *App*.
- **Feed settings** with nothing to type. Region, Language and Genre each open a list of options that
  actually have stations, with station counts. Region and Language each have a mode: *Only*,
  *Mostly* (about 70%) or *Worldwide / Any*. The default is **Mostly [detected region]** in any
  language. Changes apply when you leave Settings.
- **Tabs**: Feed and Favorites. Favorites is a feed of its own: swipe (or use headset and
  lock-screen buttons) through your favorite stations. Going back to Feed resumes where you were.
- **Share** in two modes:
  - *App link*: `<pages site>/s/?id=<station id>` opens the station in the app, or shows it in
    the browser when the app isn't installed. `scrollcast://station/<station id>` also works.
    Radio Browser ids are uuids; other sources use `<source>:<id>`, e.g. `somafm:groovesalad`.
  - *Stream link*: the station's direct stream URL.
- **Dead streams** are dropped and skipped automatically.
- **Audio** (optional, in Settings): even out loudness between stations, bass boost, and an
  equalizer with a Voice clarity preset plus the phone's own presets.

## Accessibility
- One-finger swipe up/down changes station. Each station fills the whole screen, so TalkBack
  users get the same thing with a two-finger swipe.
- The station is a single TalkBack stop that reads the name, country, genres and play state.
  Double-tap plays or pauses. It also has custom actions: next, previous, favorite, share app
  link, share stream link.
- New stations and actions are announced through a polite live region, without moving focus.
- Headset, Bluetooth and lock-screen next/previous move through the feed, because the player's
  playlist *is* the feed.
- High-contrast black and white, large type, touch targets of 56dp and up.

## Build
```bash
./gradlew assembleDebug
```
App links point at `scrollcast.shareBaseUrl` in `gradle.properties`
(https://glowing-radiant.github.io/Scroll-cast-radio). That site is the `docs/` folder, published
with GitHub Pages from branch `main`, folder `/docs`.

## Mood
"Tell us your mood" at the top of the feed takes any keyword, e.g. `hindi`, `romantic`,
`hip hop` or `romantic hindi`. Words that name a language or country become strict filters; the
rest matches genres loosely. If nothing matches, the app searches station names worldwide.
Clearing the mood returns the feed to your Settings. The box always opens empty; the
current mood is shown under it.

## Station sources
Each source is a `StationProvider` in `data/providers/`. To add one:
1. Implement `sample(query, limit)` (random stations matching a `StationQuery`; return nothing
   for a country or language the source doesn't record) and `station(id)`. A source small enough
   to download whole can extend `ListedProvider`, which filters on the phone.
2. Give its stations ids of the form `<provider id>:<its own id>`, so favorites, share links and
   the player can route back to it.
3. Add it to `providers` in `ScrollCastApp.kt`, and to `docs/s/index.html` for share links.

The catalog (Settings lists) and the feed mixer work over whichever sources are turned on.

## Releases and updates
- **CI** (`.github/workflows/ci.yml`) runs the unit tests and builds a debug APK on every push.
- **Release** (`.github/workflows/release.yml`) runs when you push a version tag:
  ```bash
  git tag v0.3.0 && git push origin v0.3.0
  ```
  It tests the app, builds an APK signed with the release key, and publishes it as a GitHub
  Release. The version comes from the tag (`versionCode` = major·10000 + minor·100 + patch).
- **In-app updates**: release builds check GitHub Releases on launch and in
  Settings → App → Check for updates. A full-screen update page lists what's new in every
  version since the installed one (playback pauses while it's open), then downloads the APK and
  hands it to Android's installer.
- **Changelog**: every release needs a `## X.Y.Z` section in `CHANGELOG.md`, written for
  listeners. The release workflow publishes that section as the release notes (and fails without
  it), and the app shows it on the update page.
- **Signing**: the key lives outside the repo (`~/.scrollcast-signing/`) and in the repo's
  Actions secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
  `SIGNING_KEY_PASSWORD`. Every release must use the same key, or updates won't install.
- Debug builds install separately as `com.scrollcast.radio.debug` ("Scroll Cast Radio (dev)"),
  so they never conflict with the released app.

## Layout
- `data/`: Radio Browser client (mirror failover), feed builder, favorites, filters, share links
- `playback/PlaybackService.kt`: Media3 session and ExoPlayer; keeps the feed topped up
- `ui/`: Compose screens (feed, favorites, settings, update dialog) and `FeedViewModel`
- `update/`: GitHub Releases update check and installer
- `docs/`: GitHub Pages site with the "open in app" redirect page

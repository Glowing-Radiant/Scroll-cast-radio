# Scroll Cast Radio

An Android app that plays internet radio as an endless vertical feed: swipe up for the next
station, like reels. It's minimal by design and fully usable with TalkBack.

## Features
- **Random feed** from [Radio Browser](https://www.radio-browser.info). The app discovers the live
  mirrors and fails over between them. Each batch is a weighted random mix, so popular stations
  come up a bit more often.
- **Region detection** from the mobile network or SIM country, falling back to the device
  locale. No location permission is needed.
- **Settings** with nothing to type. Region, Language and Genre each open a list of options that
  actually have stations, with station counts. Region and Language each have a mode: *Only*,
  *Mostly* (about 70%) or *Worldwide / Any*. The default is **Mostly [detected region]** in any
  language. Changes apply when you leave Settings.
- **Tabs**: Feed and Favorites (stored on the device).
- **Share** in two modes:
  - *App link*: `<pages site>/s/?id=<uuid>` opens the station in the app, or shows it in the
    browser when the app isn't installed. `scrollcast://station/<uuid>` also works.
  - *Stream link*: the station's direct stream URL.
- **Dead streams** are dropped and skipped automatically.

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

## Layout
- `data/`: Radio Browser client (mirror failover), feed builder, favorites, filters, share links
- `playback/PlaybackService.kt`: Media3 session and ExoPlayer; keeps the feed topped up
- `ui/`: Compose screens (feed, favorites, filters) and `FeedViewModel`
- `docs/`: GitHub Pages site with the "open in app" redirect page

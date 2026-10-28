<p align="center"><img src="app/src/main/res/drawable-xhdpi/wordmark.png" width="280" alt="TVMate"></p>

<p align="center"><b>No-bullshit Android TV IPTV client.</b><br>Live TV only. Fast zapping, a clean guide, nothing else.</p>

<p align="center"><a href="../../releases/latest"><b>Download the latest APK</b></a></p>

![Player](docs/player.jpg)

## Why TVMate

- **Clean Xtream lists.** Add any number of providers. VOD, 24/7 loops and PPV placeholders are dropped, SD/HD/FHD/4K variants merge into one channel with automatic fallback, and everything is regrouped by genre and country.
- **Free channels built in.** Optionally include the Xiaomi TV+ catalogue from every region, deduplicated, with dead streams removed.
- **Made for the remote.** UP/DOWN zaps, LEFT opens the guide, holding OK opens settings. A short tour on first launch shows the rest.
- **Phone remote.** Turn it on, scan the QR code, and browse, play, pin channels or manage providers from your phone. Off by default.
- **Updates itself** from GitHub releases.

| Guide | Search |
|---|---|
| ![Guide](docs/guide.jpg) | ![Search](docs/search.jpg) |
| **Settings** | **First-run tour** |
| ![Settings](docs/settings.jpg) | ![Tour](docs/tour.jpg) |

<p align="center">
  <img src="docs/phone-watch.png" width="260" alt="Phone remote">&nbsp;&nbsp;
  <img src="docs/phone-pins.png" width="260" alt="Pinned sections">
</p>

## Install

Sideload the APK from [Releases](../../releases/latest), e.g. `adb install TVMate-x.y.z.apk`. Later versions install from inside the app.

| Key | Action |
|---|---|
| UP / DOWN | Next / previous channel |
| LEFT or OK | Guide |
| RIGHT | Programme info |
| Hold OK | Settings |
| BACK | Close, or twice to exit |

## Build

```
./gradlew assembleDebug   # JDK 17
```

Pushing a `v*` tag builds a signed APK and publishes a release.

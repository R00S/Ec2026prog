# Fantastika 2026 programme

Android programme guide for [Fantastika 2026](https://fantastika2026.com/) (Swecon 2026), 3–6 April 2026.

Browse the convention programme, mark what you want to attend, and hide the rest. The programme is bundled with the app and can be refreshed from the live guide at any time.

- Android 8.0 (API 26) or newer
- English and Swedish UI

## Install

1. Open the [latest release](https://github.com/R00S/Ec2026prog/releases/latest).
2. Download `Ec2026prog-<version>.apk`.
3. If Android blocks the install, allow **Install unknown apps** for your browser or file manager, then open the APK again and tap **Install**.

Existing installs can be updated in place — the app checks for new releases on launch and offers to download them (see [Updates](#updates)).

## Using the app

### Days and filters

- The tabs **All / Fri / Sat / Sun** switch between the full programme and a single day.
- The chips below the tabs decide which events are listed. They are independent, so any combination is possible:
  - **Going** (green) — events you plan to attend
  - **Interested** (blue) — events you might attend
  - **Default** — events you have not marked
  - **Hidden** — events you swiped away (off by default)
  - **Passed** — events that have already ended (off by default)
- `Passed` only applies to unmarked events; anything you marked keeps its colour after it ends.

### Marking events

- **Tap an event** to open its details: full description, location, time and participants.
- In the detail view, use **Going**, **Interested** and **Hide**. Tap the same button again to clear the mark and return the event to normal.
- **Swipe an event left or right** in the list to hide it. Swiping both ways does the same thing, and hiding is a toggle: enable the **Hidden** chip, swipe the event again to bring it back.
- Hidden events never appear unless the **Hidden** chip is on, so swiping is a quick way to clear talks you are not interested in.

Your marks are stored on the device and survive restarts and programme refreshes.

### Refreshing the programme

Tap **Refresh** in the toolbar to download the newest programme data. The app tries the data published by this repository's scraper first and falls back to the live guide site. A progress bar shows how many events are being loaded. Everything is cached in a local database, so the app also works offline once loaded.

### Updates

On launch (at most once every six hours) the app compares its version with the latest GitHub release and offers to download a newer APK. **Later** snoozes that version for a day, **Skip this version** stops the prompt for it. Use **⋮ → Check for updates** to check immediately; the **About** dialog shows the installed version, the latest release and where the programme data came from.

## Data

| Source | Purpose |
| --- | --- |
| [guide.fantastika2026.com](https://guide.fantastika2026.com/) | Live convention programme |
| [`scraper/scrape.py`](scraper/scrape.py) | Scrapes the guide into `app/src/main/assets/programme.json` |
| [Scrape Programme workflow](.github/workflows/scrape.yml) | Runs the scraper hourly and commits updated data |

The app ships with the committed `programme.json` and refreshes from
`raw.githubusercontent.com/R00S/Ec2026prog/main/app/src/main/assets/programme.json`.

## Building

Requires JDK 17 and the Android SDK (compile SDK 35).

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

Release builds are signed with `app/app-release.keystore` so that updates install over existing versions. Set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` to sign with a different key instead.

Pushing to `main` triggers the [Build and Release workflow](.github/workflows/build.yml), which tags `v<versionName>`, attaches `Ec2026prog-<version>.apk` and `Ec2026prog-latest.apk`, and publishes the release. Bump `versionCode` and `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts) before pushing so the new build is offered as an update.

## Layout

```
app/                    Android app (Kotlin, Room, OkHttp)
  src/main/assets/      Bundled programme.json
scraper/                Python scraper and data refresh scripts
.github/workflows/      Build/release and hourly scraping
```

## Support

If the app makes your convention easier, you can [buy me a coffee](https://buymeacoffee.com/r00s).

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-support-yellow?logo=buymeacoffee)](https://buymeacoffee.com/r00s)

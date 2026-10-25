# My TV: TCL 75Q7C

TCL 75Q7C, board `G08`, product `G08_4K_GB`, firmware `STT2.221228.001 AS36`, Google TV 12 (SDK 31), on Ethernet.
Set up 25 October 2026.

| File | What it is |
|---|---|
| `debloat.txt` | The 36 packages disabled on this TV, in the four batches they were applied in |
| `../launcher/app/src/main/res/values/config.xml` | Launcher config: dock, hidden apps, Live TV, remote buttons |

All commands run from the repo root. ADB needs USB debugging on (Settings → System → Developer options);
on this set that opens port 5555 by itself. One-time setup on a new computer:

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
mise install java@temurin-17.0.21+9          # JDK 17 for Gradle 8.9
# Android SDK in ~/Android/Sdk: platforms;android-34, build-tools;34.0.0 (sdkmanager)
export TV_HOST=<tv ip>
```

## Rebuild and reinstall the launcher

```bash
JAVA_HOME=$(mise where java@temurin-17.0.21+9) STOCK_LAUNCHER=none INPUT_KEY_SERVICE=1 HOME_KEY_SERVICE=1 \
  launcher/build-and-install.sh
```

- `STOCK_LAUNCHER=none`: Google TV's home (launcherx) is already disabled, so there is nothing to disable.
  Projectivy Launcher was uninstalled on 25 October 2026; Android's `FallbackHome` is the only other Home.
- Pass both `*_KEY_SERVICE=1` flags on **every** reinstall: a reinstall drops the helpers from the
  accessibility list, and the gear / YouTube / TCL buttons stop working.

## Change the dock, hidden apps, buttons

Edit `launcher/app/src/main/res/values/config.xml` and reinstall, or push a `config.json` overlay without
rebuilding: `tools/theme.sh <folder with config.json>` (keys in `themes/README.md`; `tools/theme.sh --reset`
removes it). On the TV itself: hold OK on the Ajustes pill for a menu with "Reordenar apps" and Launcher
settings. Holding OK on a tile opens its App info (`tile_menu` is off).

- `dock_apps`: Prime Video, HBO Max (`com.wbd.stream`), SkyShowtime, Wholphin.
- `live_tv_package`: `com.tcl.tv` (Live TV at the end of the Inputs menu).
- `columns`: 5 tiles per row, so the dock has 5 slots.
- `recent_dock`: the dock shows the 5 most recently used apps (however they were opened), newest first; the
  `dock_apps` that are not recent lead the grid, and fill the dock while there is little history. Reads usage
  stats (`GET_USAGE_STATS` app op, set by `build-and-install.sh`). Every app shows once: dock or grid.
- `hidden_apps` includes `com.android.tv.settings`: the Ajustes pill already opens it.
- `watch_next_row`: the Continue watching row above the dock (see below).
- Screensaver: Google's photo screensaver (dreamx, re-enabled), after the system's 10-minute `screen_off_timeout`.
  The launcher's clock is the alternative, set with
  `tools/tv.py "settings put secure screensaver_components com.kermes.tvhome/.ClockDream"`.

## Continue watching

`watch_next_row` (fork-only: `WatchNext.java`, `Posters.java`, `WatchNextRow.java`) shows the shows other apps put
in Android's Watch Next list, newest first, one card per show, in the space above the dock where the focused app's
name used to be. Up from the dock goes to the row, Up again to the top bar; OK opens the episode in its app.

- Source: the TV provider's `watch_next_program` table. On this TV Prime Video, HBO Max, SkyShowtime, Jellyfin,
  Wholphin and Moonfin publish rows; **Netflix does not** (it only feeds Google's own home), so it never shows.
- Needs `READ_TV_LISTINGS` (granted by `build-and-install.sh`) and **`INTERNET`**, for the https:// posters from
  Prime, HBO and SkyShowtime. This is the only network use and a deliberate break from upstream's no-network rule.
  Posters are held in memory only (6 MB cache), nothing is written to disk.
- Turn it off with `watch_next_row` false; the INTERNET permission stays in the manifest until removed by hand.

## Recommendations hero

`recs_hero` (fork-only: `Recs.java`, hero code in `MainActivity`) puts a recommended film or series over the top of
Home: its backdrop behind the clock, then the title and a line with the service, kind, year and scores. It rotates
every 10 s; Up from Continue watching focuses it, which shows the synopsis and a dot per title; Left/Right change
the title, OK opens the service's app (at its start screen: no deep link into the title yet).

- **Source:** TMDB for what is new and where it streams in Spain (JustWatch data), OMDb for IMDb, Rotten Tomatoes
  and Metacritic. Rotten Tomatoes itself has no affordable API. Fetched at most once every 20 h, cached on the TV.
- **Rules** (in `Recs.java`'s header): films from the last year need critics (RT ≥ 80% or MC ≥ 70) and audience
  (IMDb ≥ 6.8 or TMDB ≥ 7.0); series must be new or have a new season (5th at most) in the last 120 days, and
  IMDb/TMDB ≥ 7.6 (OMDb has no series critic score). Kids, news, reality, soap and talk shows are left out.
- **Services:** hold OK on Ajustes → Launcher settings → **Mis servicios** to tick them on and off. Mediaset
  Infinity is not in TMDB's Spanish provider list, so it cannot be a source.
- **Keys:** `work/recs.properties` (`tmdb_token=`, `omdb_key=`), read at build time into the APK; git ignores `work/`.
  Without them the hero is off. TMDB's free tier asks for its credit line, shown under Mis servicios.
- With the hero on, the dock's coloured glow is off, so the backdrop fades into plain black.

## Remote buttons

Found with `logcat -v time` while pressing the real buttons (`handleKeyBeforeQueueing ... keyCode=`).

| Button | Keycode | What it does |
|---|---|---|
| Home, Input, Mic, Netflix, Prime Video, Disney+ | | handled by the TCL firmware, no mapping needed |
| Gear (Settings) | 83 | TV Settings, via `settings_key_keycodes` + `HOME_KEY_SERVICE=1` |
| YouTube | 4065 | TizenTube, via `app_keys` + `INPUT_KEY_SERVICE=1` (not 190 as on the Q7C list) |
| TCL | 4020 | HBO Max, via `app_keys` |
| Media | 4057 | unmapped; add `4057:<package>` to `app_keys` to use it |

**The trade-off:** with the key helpers bound, holding Volume +/− moves one step per press instead of
ramping. The firmware applies the volume key before the accessibility filter, and the platform stops the
repeat once a key-filtering service is bound. `consume_volume_keys` was tried and did not help (the helpers
never see the volume key here). See `docs/devices/tcl-55p71b-g09-key-repeat.md`. To trade the buttons for the
ramp, reinstall without the two `*_KEY_SERVICE=1` flags.

## Re-enable one app

```bash
tools/tv.py 'pm enable com.google.android.apps.mediashell'    # e.g. Chromecast
```

## Undo everything

```bash
tools/restore.sh mytv/debloat.txt com.google.android.apps.tv.launcherx
```

This re-enables every package in the list (nothing was disabled before) and puts Google TV's home back.
The per-batch restore records are in `~/.cleanyourtv/` and copied to `backup/` (keyed to `work/batch*.txt`,
so `restore.sh` on `mytv/debloat.txt` warns that it has no record and enables the whole list, which is
what you want here). The launcher itself is a normal app: `tools/tv.py 'pm uninstall com.kermes.tvhome'`.

## Notes from setup

- `tools/disable.sh` with `FORCE_SETUPWRAITH=1` disabled launcherx in the same run as setupwraith; its Home
  guard did not hold launcherx back on this build. Projectivy Launcher took Home until the new launcher was
  installed. No saved Wi-Fi network (Ethernet), so disabling setupwraith was safe.
- After a reboot: Home came up as the new launcher, all 36 packages stayed disabled, both helpers bound.
  TCL's `TclAppBoot` blocked only HBO Max's channel-sync job, not the helpers.
- Packages kept on purpose: `com.tcl.suspension`, `com.tcl.interactive`, `com.tcl.ttvs` (Input button and
  source overlays), `com.tcl.autopair` (remote pairing), the TCL keyboard, Play Store/Services, Assistant
  (`katniss`), Netflix, Prime Video, YouTube, game bar and Play Games.

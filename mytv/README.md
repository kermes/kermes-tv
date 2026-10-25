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

- `STOCK_LAUNCHER=none`: Google TV's home (launcherx) is already disabled, and Projectivy Launcher is installed
  as a fallback Home; the script would otherwise stop because it does not recognise Projectivy.
- Pass both `*_KEY_SERVICE=1` flags on **every** reinstall: a reinstall drops the helpers from the
  accessibility list, and the gear / YouTube / TCL buttons stop working.

## Change the dock, hidden apps, buttons

Edit `launcher/app/src/main/res/values/config.xml` and reinstall, or push a `config.json` overlay without
rebuilding: `tools/theme.sh <folder with config.json>` (keys in `themes/README.md`; `tools/theme.sh --reset`
removes it). On the TV itself: hold OK on the Ajustes pill for Launcher settings, or hold OK on a tile to
move or remove it.

- `dock_apps`: Prime Video, HBO Max (`com.wbd.stream`), SkyShowtime, Wholphin.
- `live_tv_package`: `com.tcl.tv` (Live TV at the end of the Inputs menu).
- Screensaver: the launcher's clock, set with
  `tools/tv.py "settings put secure screensaver_components com.kermes.tvhome/.ClockDream"`.

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

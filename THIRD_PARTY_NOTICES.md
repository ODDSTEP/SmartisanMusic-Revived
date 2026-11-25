# Third-Party Notices

This file records third-party artwork added to the application. It supplements, and does not replace, the copyright notes in `README.md` and `README_EN.md`.

## realme UI 7.0 Music launcher artwork

The optional yellow vinyl launcher icon preserves artwork distributed in the default UXIcon resources of realme UI 7.0.

- Source product: realme RMX5200 firmware, realme UI `7.0` / Android 16, software build `RMX5200_16.0.8.301(CN01)`
- Source package mapping: `com.heytap.music`
- Source directory: `/my_product/media/theme/uxicons/hdpi/com.heytap.music/`
- Extracted for this project: 2026-07-19
- Upstream resource names: `recbg.png`, `recfg.png`, and `monochrome.png`
- Project resource names: `ic_launcher_modern_background_art.png`, `ic_launcher_modern_foreground_art.png`, and `ic_launcher_modern_monochrome_art.png`

SHA-256 checksums of the unmodified extracted files:

```text
9155887c12055d3c57a833c8ad2a214d539a0bc456ae023674da76d3170ebb51  recbg.png
951d6b7367baeb8b85f350c694882f44ce22959f255adad9c0949d8571ebcbb1  recfg.png
fd7c6f02a945d3dd45ad4c17c2032e8a6a7262f1ada31bc42ff40f56d5b42d62  monochrome.png
```

No separate open-source license or license text accompanied these three firmware resources. They are not claimed as original project artwork and are not relicensed under the project's software license. The artwork, product names, and trademarks remain the property of their respective rights holders. Downstream redistributors are responsible for determining whether they have the permissions required for their intended use.

## BASS audio library (un4seen developments)

`app/src/main/jniLibs/arm64-v8a/libbass.so`, `libbassmix.so`, `libbass_fx.so`, `libbassflac.so` and the `com.un4seen.bass` Java bindings are unmodified redistribution of the BASS audio library by Ian Luck / un4seen developments (<https://www.un4seen.com/>).

- BASS is free for non-commercial use; commercial or store distribution requires a license purchased from un4seen. Check the terms at the link above before shipping this app commercially.
- In this project BASS is only used as a decoding source and DSP (`BASS_STREAM_DECODE`); audio output always goes through Media3 / AudioTrack. `verifyBassIsolation` in `app/build.gradle.kts` enforces that only `BassEngine.kt` touches the native API.
- The BASS binaries are not covered by any license this repository could grant.

## FFmpeg (`libffmpegbin.so`)

`app/src/main/jniLibs/arm64-v8a/libffmpegbin.so` is a prebuilt FFmpeg executable packaged as a shared library so that it can be launched as a separate process on Android. Only the compiled artifact is stored here; FFmpeg source is not vendored in this repository.

FFmpeg is licensed under the LGPL-2.1-or-later by default, or GPL when built with GPL-only components. The applicable license depends on how that particular build was configured; whoever redistributes this APK is responsible for shipping the corresponding license text and, in the LGPL case, making the corresponding source available. See <https://ffmpeg.org/legal.html>.

## libusb

`app/src/main/cpp/truesample/third_party/libusb/` contains libusb, used for isochronous USB transfers to external DACs. libusb is licensed under the GNU Lesser General Public License v2.1 or later; the full text is in `app/src/main/cpp/truesample/third_party/libusb/COPYING`.

## libsamplerate

`app/src/main/cpp/truesample/third_party/libsamplerate/` contains libsamplerate (Secret Rabbit Code), used to resample PCM to the rate required by the USB DAC. libsamplerate is licensed under the BSD 2-Clause license; the full text is in `app/src/main/cpp/truesample/third_party/libsamplerate/COPYING`.

## Upstream project and Smartisan resources

This repository is a fork of [Mangi-11/SmartisanMusic-Revived](https://github.com/Mangi-11/SmartisanMusic-Revived), which re-implements Smartisan Music 8.1.0 using modern Android APIs and reuses the original bitmaps, nine-patch and state resources extracted from `Music_8.1.0.apk` (obtained through [People-11/SmartisanOS_APP_Port](https://github.com/People-11/SmartisanOS_APP_Port)). Smartisan OS, its trademarks and original artwork remain the property of their respective rights holders. No music content is bundled with the app.


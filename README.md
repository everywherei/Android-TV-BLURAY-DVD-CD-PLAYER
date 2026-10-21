# DiscMount (Phase 1)

Mounts USB DVD/Blu-ray drives on Android / Google TV **without kernel drivers**: communicates SCSI/MMC directly over USB (Bulk-Only Transport) and reads UDF / ISO9660 in user space, similar to MLUSB Mounter.

## What Phase 1 Does
- Detects the drive (Mass Storage class, subclass 2/6, BOT protocol), requests USB permission, and opens it.
- Waits for the disc to be ready, reads capacity and disc type (CD/DVD/BD).
- File systems: ISO9660 (+Joliet), UDF 1.02–2.60 including the Blu-ray **metadata partition**.
- Integrated player (libvlc) for video, audio, and images from the disc; explorer with D-pad, file copying to other storage, and a **DocumentsProvider**: the disc appears in Android's file picker so that VLC and other apps can open files (e.g., `.m2ts`, `.vob`).

## DVD-Video with CSS (Phase 2)
VOB files from a commercial DVD are read via **libdvdcss** (drive authentication, disc key, and title key), just like VLC does on PC. libdvdcss is not included in this repository: `tools/fetch_libdvdcss.sh` downloads it (Kodi fork, pinned commit) and applies a one-line patch; the custom code in `app/src/main/cpp` replaces `ioctl.c` with SCSI/MMC commands sent by your USB drive and connects everything via JNI. The **Play DVD movie** button picks the longest title set from `VIDEO_TS` and plays it without menus. Use only with discs you own; in some countries, CSS decryption is restricted. By linking libdvdcss (GPL), the resulting APK is GPL: do not redistribute it without complying with its license.
PC testing: the JNI bridge reads unencrypted discs bit-by-bit and emits the correct authentication command sequence; **actual decryption could not be tested** (no encrypted disc or drive present here).

## Blu-ray with AACS (KEYDB.cfg)
**Import KEYDB.cfg** button (file picker) or copy the file to `/sdcard/Android/data/dev.discmount/files/KEYDB.cfg`.
When mounting a Blu-ray, the disc hash is computed (SHA-1 of `AACS/Unit_Key_RO.inf`), looked up in your KEYDB.cfg, and using the entry's VUK (`V`) or drive keys (`U`), the `.m2ts` files are decrypted on-the-fly (`core/.../bd/Aacs.kt`). **Play Blu-ray movie** selects the longest playlist. Only works with discs whose entry has a VUK or drive keys; drive authentication (entries with MEK only do not work), BD+, and Ultra HD (AACS 2.0) are not supported. Use only with discs you own.
Testing: `AacsTest.kt` (synthetic encryption/decryption; not tested with a real disc).

## Structure
- `core/`: Pure Kotlin (no Android). Blocks, UDF/ISO, BOT, SCSI commands. CSS/AACS can be hooked here (`ScsiTransport.execute`).
- `app/`: Android app (Activity, USB transport, DocumentsProvider).
- `tools/`: Test image generator and test script.

## Building without Android Studio (GitHub Actions)
Upload the folder to your own GitHub repository; the `.github/workflows/build-apk.yml` workflow runs tests and generates `app-debug.apk` (*Actions* tab > latest run > *Artifacts* > `DiscMount-debug-apk`).

## Building with Android Studio
Open the folder in Android Studio (Ladybug or higher; generates the Gradle wrapper) and run `app`.
minSdk 26, JDK 17. Install it on the TV via ADB: `adb install app-debug.apk`.

## Core Tests (without Android)
`tools/run_core_tests.sh` (requires Java 17+, Python 3, and `pip install pycdlib`). Compiles the core, generates ISO/Joliet/UDF images, and reads them via a fake USB device, checking the SHA-256 hash of each file.

## Honest Status
- Verified: The core (UDF/ISO/BOT/SCSI) against real images generated with pycdlib and against a hand-crafted UDF 2.50 image with fragmented metadata.
- **Unverified**: The Android app could not be compiled or run in the development environment (no Android SDK) and has not been tested with a real drive. Expect minor compilation errors and tweaks needed for your hardware.
- Known limitations: No UAS (BOT only), no UDF virtual partitions (VAT), no Rock Ridge, no defective sector remapping (sparable). `AndroidUsbTransport.CHUNK` (16 KiB) can be increased for higher speed.

## Upcoming Phases
2. DVD-Video: CSS (`REPORT KEY`/`SEND KEY` MMC authentication over `ScsiTransport`) + libdvdread/libdvdnav + libvlc.
3. Blu-ray: libbluray + libaacs with import of your `KEYDB.cfg`, playback of the main playlist.

## v0.4 – Music and Disc Detection

- Detects upon inserting a disc whether it is a Compact Disc (audio/data), DVD (video/data), or Blu-ray (video/data) and displays it with a drawn disc and its badge (custom designs, not official logos).
- Original audio CD: TOC and CD-TEXT reading, tracks read with READ CD and played back as WAV on-the-fly.
- Audio files (mp3, flac, wav, ogg, m4a, aac, opus, ape, wv, dsf/dff, etc.) with folder playlist and next/previous controls.
- SACDs (discs) cannot be played (protected format); however, .dsf/.dff files that libvlc knows how to decode are supported.
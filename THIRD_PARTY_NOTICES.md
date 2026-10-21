# Third-party notices

DiscMount is licensed under the **GNU General Public License v3.0 or later** (see [`LICENSE`](LICENSE)).
It uses the third-party components listed below. Their licenses apply to those components only; none of them is
relicensed by DiscMount. All of them are compatible with distributing the combined work under GPL-3.0-or-later.

## Components linked into the APK

| Component | Use in DiscMount | License | Source |
|---|---|---|---|
| **libdvdcss** (Kodi fork `xbmc/libdvdcss`, pinned commit in `tools/fetch_libdvdcss.sh`, based on VideoLAN libdvdcss 1.4.3) | CSS handling for DVD-Video, compiled into `libdiscmountcss.so` through the NDK | GPL-2.0-or-later | https://github.com/xbmc/libdvdcss · https://code.videolan.org/videolan/libdvdcss |
| **LibVLC for Android** (`org.videolan.android:libvlc-all:3.6.0`), including the VLC engine and the codecs/libraries bundled in that AAR | Audio/video playback | LGPL-2.1-or-later (VLC/LibVLC). The AAR also bundles third-party libraries (FFmpeg, libass, libdvdnav/libdvdread, libbluray, etc.) under their own licenses, mostly LGPL/GPL/BSD/MIT-style | https://code.videolan.org/videolan/vlc-android · https://www.videolan.org/legal.html |
| **Kotlin standard library** | Runtime of the Kotlin code | Apache-2.0 | https://github.com/JetBrains/kotlin |
| **AndroidX / Android platform libraries** | Android APIs (activities, USB host, storage access) | Apache-2.0 | https://developer.android.com |

Notes:

* **libdvdcss is not stored in this repository.** `tools/fetch_libdvdcss.sh` downloads it at build time and applies
  `app/src/main/cpp/discmount-libdvdcss.patch` (a small patch, itself under GPL-2.0-or-later as a derivative of libdvdcss).
  `app/src/main/cpp/ioctl_scsi.c` re-implements libdvdcss's `ioctl_*()` functions on top of SCSI/MMC commands sent over USB, following the
  structure of libdvdcss's `ioctl.c` (GPL-2.0-or-later). Because libdvdcss is linked into the app, **the built APK is a GPL work**:
  if you distribute an APK you must also provide the corresponding source code (this repository at the matching tag/commit, plus the
  libdvdcss commit named in `tools/fetch_libdvdcss.sh`) under the GPL.
* **LibVLC** is LGPL: you may link to it from GPL code. If you distribute an APK you must keep LibVLC replaceable/relinkable as the LGPL
  requires and ship its license notices (the notices above and the VLC legal page are the reference).
* The AACS (Blu-ray) decryption in `core/src/main/kotlin/dev/discmount/core/bd/` is **original code** written for this project. It uses only
  the JDK/Android `javax.crypto` AES implementation and the publicly documented AACS format. It does **not** include or derive from
  libaacs, and **no keys, certificates or `KEYDB.cfg` files are distributed** with this project.

## Development-only tools (not shipped)

| Tool | Use | License |
|---|---|---|
| Gradle, Android Gradle Plugin, Kotlin Gradle plugin | Build | Apache-2.0 |
| Android NDK / CMake | Native build | Apache-2.0 / BSD-3-Clause |
| `pycdlib` (Python) | Generates test disc images in `tools/gen_test_images.py` | LGPL-2.1 |

## Trademarks

DVD, DVD-Video, Blu-ray Disc, Blu-ray, AACS, CSS, Android, Android TV, Google TV, Google Play and VLC are trademarks or registered
trademarks of their respective owners. They are used only to describe compatibility. DiscMount is an independent project and is not
affiliated with, endorsed by or sponsored by any of them. The disc artwork in the app is original and does not use the official logos.

## Full license texts

* GPL-3.0-or-later: [`LICENSE`](LICENSE)
* GPL-2.0-or-later, LGPL-2.1, Apache-2.0: https://www.gnu.org/licenses/ and https://www.apache.org/licenses/LICENSE-2.0
  (also reproduced in the source repositories linked above).

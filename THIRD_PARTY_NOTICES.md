# Third-party notices

The Tailcat Manager source code is licensed under the MIT License in `LICENSE`.

## Android build: bundled executables

The Android app (`android-shell/`) ships two prebuilt executables and their runtime
libraries inside the APK. They are extracted to the app's `nativeLibraryDir`, which is
the only place Android 10+ lets an app `execve()` from. Neither is modified in any way;
both are redistributed as published upstream.

### tailcat (aarch64)

`android-shell/src/main/jniLibs/arm64-v8a/libtailcat.so` is the official
`tailcat_0.7.0_linux_arm64.tar.gz` binary, renamed. It is statically linked, so it runs
on Android unchanged.

Source: https://github.com/tailscale/tailcat/releases/tag/v0.7.0
License: BSD 3-Clause (see below)

### adb (aarch64)

The Android build needs a real `adb` to talk to the device it is managing. Android ships
no adb and there is no official aarch64 build, so the app bundles one.

Source: Termux packages, https://packages.termux.dev/apt/termux-main/
Built by the Termux project with the Android NDK, so it links against bionic like any
other Android binary. (Debian/Ubuntu's arm64 `adb` links glibc and cannot run on Android
at all; AOSP publishes no arm64 adb.)

The single hard requirement is **16 KB page size alignment**. An earlier build of this app
bundled `nightmare-space/adb_kit` v1.5.4 (2024-10), whose libraries are all 4 KB aligned —
the system flags such an app with `PAGE_SIZE_APP_COMPAT_FLAG_ELF_NOT_ALIGNED` and shows a
compatibility warning, and on a device with 16 KB pages those libraries fail to load
outright. Termux's packages are built after the 16 KB migration and are all aligned to
16384.

Packages bundled, downloaded at build time and verified by SHA-256
(`android-shell/build.gradle.kts`):

| Package | Version | SHA-256 |
|---|---|---|
| `android-tools` | 37.0.0p1-1 | `bafdbb636f780b0b477a074832ae26dde0f1a934c4f2743dfef2b32e95a3c642` |
| `abseil-cpp` | 20260526.0 | `e489fac652cddc39d9436141e627285f1034a545a06fbb19c420514a419ad877` |
| `brotli` | 1.2.0 | `db1502601d40fb44e6085ad8bfd9311a8b472e98db831ceec9d404c5708bb52c` |
| `fmt` | 1:11.2.0-1 | `dad595afcb3b1096d725c6772c0c5531764caa5841affba3c533c6298a32ab08` |
| `libc++` | 30 | `53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca` |
| `liblz4` | 1.10.0-1 | `09b9449418d5c2dc4f5c1c140ba8138d56be3e9ae5fd3be3318825ec9f8a0499` |
| `libprotobuf` | 2:36.2 | `2f79e70af72127b6e9d8e8ea1a8bf261ea5f390914e667049b4f1dbc81f21fb0` |
| `zlib` | 1.3.2 | `75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9` |
| `zstd` | 1.5.7-1 | `e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3` |

Only adb's transitive dependency closure is taken — 58 libraries, computed with
`readelf -d`. `pcre2` is a declared dependency of `android-tools` but is not in the
closure, so it is not bundled.

| File | Upstream project | License |
|---|---|---|
| `libadb.so` | AOSP platform/packages/modules/adb, 1.0.41 / 37.0.0-android-tools | Apache-2.0 |
| `libabsl_*.so` | Abseil C++ (48 files) | Apache-2.0 |
| `libprotobuf.so`, `libutf8_validity.so` | Protocol Buffers | BSD 3-Clause |
| `libfmt.so` | {fmt} | MIT |
| `libc++_shared.so` | LLVM libc++ | Apache-2.0 with LLVM exception |
| `libbrotli{common,dec,enc}.so` | Brotli | MIT |
| `liblz4.so` | LZ4 | BSD 2-Clause |
| `libzstd.so.1` | Zstandard | BSD 3-Clause / GPL-2.0 dual |
| `libz.so.1` | zlib | zlib |

`libc.so`, `libdl.so`, `liblog.so` and `libm.so` are the only remaining dependencies of
the closure, and the app deliberately does **not** bundle them — the device's own copies
are used, because the platform's `libc` must match the running OS.

Every bundled library is redistributed unmodified.

#### How the naming works

PackageManager only extracts files named `lib*.so` from an APK, but the ELF dynamic linker
resolves `DT_NEEDED` against the library's real name. So each library is stored as
`<soname>.so` (e.g. `libz.so.1` → `libz.so.1.so`, `libabsl_base.so` →
`libabsl_base.so.so`) and, at runtime, `AdbRuntime.kt` creates a symlink from the real name
back to the extracted file in a directory that is put on `LD_LIBRARY_PATH`.

### AndroidX / Compose / MIUIX

Compose Multiplatform and AndroidX components are Apache-2.0. `top.yukonga.miuix.kmp:miuix`
is Apache-2.0 (https://github.com/YuKongA/miuix). ZXing core is Apache-2.0.

`androidx.camera:camera-{core,camera2,lifecycle,view}` is used for the "scan the other
phone's QR code" flow. It is Apache-2.0 and, unlike the alternative (ML Kit barcode
scanning), ships **no native libraries** — which matters here because this app has already
been bitten by the 16 KB page-size alignment requirement (see the adb section above).
QR decoding itself is done by the ZXing core that was already a dependency; CameraX only
supplies frames.

The application icon (`android-shell/src/main/res/`) is generated from the macOS app icon
by `android-shell/icons/GenerateIcon.java`. The artwork itself retains the BSD 3-Clause
license and attribution described under "Tailcat artwork" below.

### scrcpy-server

Screen mirroring is implemented by shipping scrcpy's own server to the managed device and
running it there with `app_process`; the app then speaks the scrcpy protocol over two
sockets. The server binary is **not** bundled in the source tree — it is downloaded at
build time by `android-shell/build.gradle.kts` into `android-shell/src/main/assets/` and
verified against the SHA-256 published in the release's `SHA256SUMS.txt`. It is
redistributed unmodified and pushed to `/data/local/tmp/scrcpy-server.jar` on the device
being managed.

| | |
|---|---|
| Version | 5.0.1 (the server rejects any client whose version string differs) |
| Source | https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1 (`scrcpy-server-v5.0.1`) |
| SHA-256 | `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536` |
| Size | 733930 bytes |
| License | Apache-2.0 |

### Android scrcpy client: decoder layer

`android-shell/.../screen/ScreenDecoder.kt` and `DecoderController.kt` are **derived from**
the corresponding files in the `ScrcpyForAndroid` Android client:

| | |
|---|---|
| Project | https://github.com/Miuzarte/ScrcpyForAndroid |
| Original files | `nativecore/MediaCodecVideoDecoder.kt`, `nativecore/VideoDecoderController.kt` |
| Copyright | the ScrcpyForAndroid contributors |
| License | Apache-2.0 |

**Changes made** (Apache-2.0 §4(b) requires modified files to carry prominent notices
stating that they were changed): the Android-side `MediaCodec` wrapper and its lifecycle
controller were reimplemented in this project's own structure and naming, translated to
this codebase's comment conventions, and stripped of everything this project does not
need — the intermediate `PersistentVideoRenderer` (upstream uses it to fan out to a
display surface, a recording surface and picture-in-picture at once; this app renders to
a single `SurfaceView`), the recording / audio / gamepad paths, and the coroutine-based
lifecycle API, which is a plain blocking API here because the caller is a dedicated
reader thread. A retry back-off on decoder-construction failure was added. The video
demuxing, socket handling, control-message encoding and adb orchestration are this
project's own and were not taken from that project.

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

## Tailcat artwork

`desktop/src/main/resources/icons/app-icon.png` is the application icon; it is the same
image the macOS build ships as `resources/AppIcon.png`. The Windows icon
`desktop/icons/TailcatManager.ico` is generated from it by
`desktop/icons/GenerateIcon.java` (resizing only, no redrawing).

`resources/TailcatOfficialReference.png` in the macOS sources reproduces the official
Tailcat cat artwork, from which the app icon was generated and adapted. These artwork
resources retain the upstream BSD 3-Clause license and attribution below; they are not
relicensed under the project's MIT License.

Source: https://github.com/tailscale/tailcat/blob/52fbad323e9d352f1febb623e0975368e9a8cbdd/tailcat.png
Upstream license: https://github.com/tailscale/tailcat/blob/52fbad323e9d352f1febb623e0975368e9a8cbdd/LICENSE

Tailcat Manager is an independent GUI project. The upstream names are not used
to claim endorsement by Tailscale or its contributors.

BSD 3-Clause License

Copyright (c) 2020 Tailscale Inc & contributors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

# 第三者ソフトウェアのライセンス / Third-Party Software Notices

Shonan_Lite-androidPhone-SSBFM は GNU General Public License v3(またはそれ以降のバージョン)の下で提供され、次の第三者ソフトウェアを
利用・同梱しています。各ソフトウェアはそれぞれのライセンスに従います。ライセンス本文は
[`third_party_licenses/`](third_party_licenses/) にあります(各ソフトウェアの配布元から取得したもの)。

Shonan_Lite-androidPhone-SSBFM is provided under the GNU General Public License v3 (or any later version) and uses and bundles the following
third-party software, each under its own license. The license texts (taken from each project's distribution) are in
[`third_party_licenses/`](third_party_licenses/).

## ソースコードの入手 / Obtaining the source code

- ネイティブライブラリは `android/third_party/` にビルド済みのものを同梱しています。表の版(またはコミット)を各配布元から
  取得し、`android/third_party/` のビルドスクリプト(`build_*.sh`)と変更用パッチ(`*.patch`)を当てると、同じものを作れます。
- LGPL のライブラリ(FFmpeg・libiio・VOLK・GMP)を静的または動的にリンクしています。本アプリのソースコード一式を
  このリポジトリで公開しているので、利用者は変更したライブラリと再リンクできます。

- The native libraries are bundled prebuilt in `android/third_party/`. They can be reproduced by taking the versions (or
  commits) listed below from each upstream and applying the build scripts (`build_*.sh`) and patches (`*.patch`) in
  `android/third_party/`.
- The LGPL libraries (FFmpeg, libiio, VOLK, GMP) are linked statically or dynamically. The complete source code of this
  app is published in this repository, so users can relink it with modified versions of those libraries.

## ネイティブライブラリ(C/C++) / Native libraries (C/C++)

| 名前<br>Name | 版<br>Version | ライセンス<br>License | 配布元<br>Source | 同梱の形<br>How it is included | 変更<br>Modifications | ライセンス本文<br>License text |
|---|---|---|---|---|---|---|
| GNU Radio | 3.10.12 | GPL-3.0-or-later | https://github.com/gnuradio/gnuradio | 共有ライブラリ `libgnuradio-*.so`(7個)<br>shared libraries `libgnuradio-*.so` (7) | なし<br>none | [`GNURadio-GPL-3.0.txt`](third_party_licenses/GNURadio-GPL-3.0.txt) |
| gr-dvbs2rx | commit 130c315 | GPL-3.0-or-later | https://github.com/igorauad/gr-dvbs2rx | 共有ライブラリ `libgnuradio-dvbs2rx.so`<br>shared library `libgnuradio-dvbs2rx.so` | `gr_dvbs2rx_android_port.patch` | [`GNURadio-GPL-3.0.txt`](third_party_licenses/GNURadio-GPL-3.0.txt) |
| FFTW | 3.3.10 | GPL-2.0-or-later | https://www.fftw.org/ | `libgnuradio-fft.so` に静的リンク<br>statically linked into `libgnuradio-fft.so` | なし<br>none | [`FFTW-COPYRIGHT.txt`](third_party_licenses/FFTW-COPYRIGHT.txt)<br>[`FFTW-GPL-2.0.txt`](third_party_licenses/FFTW-GPL-2.0.txt) |
| VOLK | 3.3 | LGPL-3.0-or-later | https://github.com/gnuradio/volk | 共有ライブラリ `libvolk.so`<br>shared library `libvolk.so` | なし<br>none | [`VOLK-LGPL-3.0.txt`](third_party_licenses/VOLK-LGPL-3.0.txt)<br>[`GNURadio-GPL-3.0.txt`](third_party_licenses/GNURadio-GPL-3.0.txt) |
| Boost | 1.84 | BSL-1.0 | https://www.boost.org/ | GNU Radio のライブラリに静的リンク<br>statically linked into the GNU Radio libraries | なし<br>none | [`Boost-BSL-1.0.txt`](third_party_licenses/Boost-BSL-1.0.txt) |
| GMP | 6.3.0 | LGPL-3.0-or-later または GPL-2.0-or-later / LGPL-3.0-or-later or GPL-2.0-or-later | https://gmplib.org/ | GNU Radio のライブラリに静的リンク<br>statically linked into the GNU Radio libraries | なし<br>none | [`VOLK-LGPL-3.0.txt`](third_party_licenses/VOLK-LGPL-3.0.txt)<br>[`GNURadio-GPL-3.0.txt`](third_party_licenses/GNURadio-GPL-3.0.txt) |
| spdlog(同梱の fmt を含む / including bundled fmt) | 1.14.1 | MIT | https://github.com/gabime/spdlog | GNU Radio のライブラリに静的リンク<br>statically linked into the GNU Radio libraries | なし<br>none | [`spdlog-MIT.txt`](third_party_licenses/spdlog-MIT.txt)<br>[`fmt-MIT.txt`](third_party_licenses/fmt-MIT.txt) |
| libiio | 1.0 (commit cd0049b) | LGPL-2.1-or-later | https://github.com/analogdevicesinc/libiio | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | `libiio_refresh_format_null_guard.patch` | [`libiio-LGPL-2.1.txt`](third_party_licenses/libiio-LGPL-2.1.txt) |
| libxml2 | 2.12.9 (commit 00301f0) | MIT | https://gitlab.gnome.org/GNOME/libxml2 | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | なし<br>none | [`libxml2-MIT.txt`](third_party_licenses/libxml2-MIT.txt) |
| Zstandard (zstd) | 1.5.7 (commit f8745da) | BSD-3-Clause(GPL-2.0 との二重ライセンスから BSD を選択 / BSD chosen from the dual BSD/GPL-2.0 license) | https://github.com/facebook/zstd | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | なし<br>none | [`zstd-BSD-3-Clause.txt`](third_party_licenses/zstd-BSD-3-Clause.txt) |
| dvbs2(aff3ct) | commit e029b09 | MIT | https://github.com/aff3ct/dvbs2 | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | `radio_user_binary_write2.patch`, `radio_user_binary_fifo_eof.patch` | [`dvbs2-MIT.txt`](third_party_licenses/dvbs2-MIT.txt) |
| AFF3CT | 4.0.0 | MIT | https://github.com/aff3ct/aff3ct | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | `aff3ct_bch_decoder_overalloc.patch` | [`aff3ct-MIT.txt`](third_party_licenses/aff3ct-MIT.txt) |
| StreamPU | AFF3CT 4.0.0 に付属 / bundled with AFF3CT 4.0.0 | MIT | https://github.com/aff3ct/streampu | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | なし<br>none | [`StreamPU-MIT.txt`](third_party_licenses/StreamPU-MIT.txt) |
| MIPP・aff3ct cli・date・cpptrace・nlohmann/json・rang | AFF3CT/StreamPU に付属 / bundled with AFF3CT/StreamPU | MIT(rang は Unlicense / rang is Unlicense) | https://github.com/aff3ct/MIPP ほか / and others | `libdvbs2_bridge.so` に静的リンク<br>statically linked into `libdvbs2_bridge.so` | なし<br>none | [`MIPP-MIT.txt`](third_party_licenses/MIPP-MIT.txt)<br>[`aff3ct-cli-MIT.txt`](third_party_licenses/aff3ct-cli-MIT.txt)<br>[`date-MIT.txt`](third_party_licenses/date-MIT.txt)<br>[`cpptrace-MIT.txt`](third_party_licenses/cpptrace-MIT.txt)<br>[`nlohmann-json-MIT.txt`](third_party_licenses/nlohmann-json-MIT.txt)<br>[`rang-Unlicense.txt`](third_party_licenses/rang-Unlicense.txt) |
| FFmpeg(libavformat・libavcodec・libavutil) | n7.1 | LGPL-2.1-or-later(GPL・nonfree 部品は無効 / GPL and nonfree parts disabled) | https://ffmpeg.org/ | `libts_bridge.so` に静的リンク<br>statically linked into `libts_bridge.so` | なし<br>none | [`FFmpeg-LICENSE.md`](third_party_licenses/FFmpeg-LICENSE.md)<br>[`FFmpeg-LGPL-2.1.txt`](third_party_licenses/FFmpeg-LGPL-2.1.txt) |
| LLVM libc++ | Android NDK r27d (27.3.13750724) | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project | 共有ライブラリ `libc++_shared.so`<br>shared library `libc++_shared.so` | なし<br>none | [`libcxx-Apache-2.0-with-LLVM-exception.txt`](third_party_licenses/libcxx-Apache-2.0-with-LLVM-exception.txt) |
| Langstone-V2(Colin Durbridge, G4EML) | — | GPL-3.0 | https://github.com/g4eml/Langstone-V2 | C++ へ移植して `libssbfm_bridge.so` に組み込み<br>ported to C++ and built into `libssbfm_bridge.so` | 移植・改変 / ported and modified<br>ported and modified | [`Langstone-V2-GPL-3.0.txt`](third_party_licenses/Langstone-V2-GPL-3.0.txt) |

## Java/Kotlin ライブラリ / Java/Kotlin libraries

| 名前<br>Name | ライセンス<br>License | 配布元<br>Source | ライセンス本文<br>License text |
|---|---|---|---|
| AndroidX(Jetpack Compose BOM 2024.10.01、Activity 1.9.3、Lifecycle 2.8.7、Navigation 2.8.4、Core 1.15.0、CameraX 1.4.1) | Apache-2.0 | https://developer.android.com/jetpack/androidx | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| Kotlin 標準ライブラリ / Kotlin standard library 2.0.21 | Apache-2.0 | https://github.com/JetBrains/kotlin | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| kotlinx.coroutines 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| kotlinx.serialization 1.6.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| SSHJ 0.40.0 | Apache-2.0 | https://github.com/hierynomus/sshj | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt)<br>[`sshj-NOTICE.txt`](third_party_licenses/sshj-NOTICE.txt) |
| asn-one 0.6.0 | Apache-2.0 | https://github.com/hierynomus/asn-one | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| Bouncy Castle(bcprov・bcpkix 1.80) | MIT | https://www.bouncycastle.org/ | [`BouncyCastle-MIT.md`](third_party_licenses/BouncyCastle-MIT.md) |
| SLF4J API 2.0.17 | MIT | https://www.slf4j.org/ | [`slf4j-MIT.txt`](third_party_licenses/slf4j-MIT.txt) |
| Guava ListenableFuture 1.0 | Apache-2.0 | https://github.com/google/guava | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| AutoValue Annotations 1.6.3 | Apache-2.0 | https://github.com/google/auto | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |
| JetBrains Annotations 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations | [`Apache-2.0.txt`](third_party_licenses/Apache-2.0.txt) |

# Shonan_Lite-androidPhone-SSBFM

[Shonan_Lite-androidPhone](https://github.com/kazushinjo/Shonan_Lite-androidPhone)(DVB-S2トランシーバー
「Shonan」のAndroidスマートフォン版)に、**SSB/FM送受信タブ**を追加したもの。SSB/FMの信号処理は
[Langstone-V2](https://github.com/g4eml/Langstone-V2)(G4EML Colin Durbridge氏、GPLv3)のPluto版
(`Lang_TRX_Pluto.py`・`LangstoneGUI_Pluto.c`)をC++へ移植した。

Adds an **SSB/FM transceiver tab** to Shonan_Lite-androidPhone. The SSB/FM signal processing is a C++ port of
the Pluto version of [Langstone-V2](https://github.com/g4eml/Langstone-V2) by G4EML (GPLv3).

## SSB/FMタブ

- **モード**: USB・FM(±5kHz偏移、75µsプリ/ディエンファシス)。
- **受信**: Plutoを528kspsで受け、周波数変換+1/11間引きで48kHzにし、複素帯域フィルタ(USB +300〜+3000Hz /
  FM ±7.5kHz)→復調(SSB: 実部+AGC、FM: 直交復調)→音声フィルタ→スピーカー。
  受信LOは100kHz単位に置き、希望周波数を+50〜+150kHz側で受ける(DC付近を避けるLangstoneと同じ方式)。
- **送信**: マイク48kHz→SSB: 複素帯域フィルタで片側波帯を生成 / FM: プリエンファシス+周波数変調→11倍補間→Pluto。
  PTTは押している間だけ送信(「押すたびに切り替え」も選べる)。送信中は受信LO、受信中は送信LOを止める。
- **画面**: 周波数表示(桁をタップでステップ選択、長押しで直接入力)、±ボタン、Sメーター(送信中はマイクレベル)、
  スペクトル・ウォーターフォール(48kHz幅。左右ドラッグで同調、タップでその周波数へ移動)、音量・スケルチ・
  マイク・RF利得(AGC)・送信減衰。
- **Plutoの扱い**: DATVと同じPluto(設定タブのPlutoアドレス)を送受信兼用で使う。開始時にSSHで
  `pluto_dvb`(Pluto標準UDP-TS送信経路)を止めてTX DMAを空け、libiioで直接IQを送受信する。528kspsは
  AD9361のFIRなしの下限を下回るため、libad9361-iio(Analog Devices、LGPL-2.1)の`ad9361_set_bb_rate()`と
  同じ手順でFIRを読み込む。停止時はサンプルレート・LO・利得を開始前の値へ戻す。DATVの送受信とは排他
  (どちらかを始めると他方は止まる)。DATV送信の開始時には従来どおり`udpts.sh`が再起動される。
- **実装**: `android/app/src/main/cpp/ssbfm_dsp.h`(DSP)、`ssbfm_bridge.cpp`(Pluto制御・AAudio・JNI)、
  `android/app/src/main/kotlin/.../ssbfm/`、`ui/SsbFmScreen.kt`。
  `android/app/src/main/cpp/test/ssbfm_dsp_test.cpp`は送信DSP→疑似RF→受信DSPの折り返し検証
  (実機で側波帯抑圧・SINAD・処理時間を確認する。ビルド方法はファイル先頭)。
- **アプリID**: `com.shinjo.shonanandroid.ssbfm`(アプリ名「Shonan SSB/FM」)。元の電話版と同じ端末に共存できる。

## ライセンス

SSB/FM機能はGPLv3のLangstone-V2に由来するため、このリポジトリは[GPLv3](LICENSE)で配布する。
FIR係数と設定手順はlibad9361-iio(LGPL-2.1)に由来する。

---

以下は元の電話版の説明。

# Shonan_Lite-androidPhone

Pluto直結DVB-S2トランシーバー「Shonan」のAndroidスマートフォン版(`android/`)。タブレット版
[Shonan_Lite-android](https://github.com/kazushinjo/Shonan_Lite-android)を元に、狭い画面幅に合わせて
画面上部のタブで機能を切り替える単一画面構成にしている。詳細な構成・アーキテクチャは
[`android/README.md`](android/README.md)を参照。

Android smartphone version of "Shonan", a DVB-S2 transceiver directly connected to a Pluto (`android/`).
Based on the tablet version [Shonan_Lite-android](https://github.com/kazushinjo/Shonan_Lite-android), it uses a
single screen whose functions are switched with tabs at the top, to fit a narrow phone screen. See
[`android/README.md`](android/README.md) for the detailed architecture and implementation.

## 主な機能

- **画面構成**: 上部のタブ(送信・受信・SSB/FM・周波数・設定２(受信感度・送信出力)・設定３(シンボルレート・誤り訂正・変調方式)・映像ソース・配信先・
  設定・ヘルプ)で切り替える。上部バーに「アプリ再起動」「終了」。
  タブレット版にあるRSSI測定・機器試験は搭載していない。
- **送信**: 周波数・シンボルレート(250k〜2 Msym/s)・誤り訂正(1/2・3/5・8/9)・変調方式(QPSK・8PSK)・
  出力減衰量(-70〜0 dB)を設定し、UDP-TSでPlutoへ送ってPluto内蔵の変調器で送信する(送信先ポートは8282固定)。
  送信映像はHD(1280x720)・30fps。
- **映像ソース**: 背面カメラ(既定)・前面カメラ・写真(写真フォルダーから選択)・テストパターン。
  写真にはコールサイン・備考を焼き込める。マイク音声の送信ON/OFF。
- **受信**: 外部復調機器からのUDP-TS受信(既定)、またはオンデバイス復調(GNU Radio、Pluto 1台でのRFループバック
  試験にも使える)。ロックすると映像を全画面表示し、タップで5秒間だけ状態を表示する。
- **その他**: Plutoの自動検出(端末の接続中ネットワークを探索し、見つからなければESP32ブリッジに問い合わせ。
  起動時にも1回実行)、起動時のPluto再起動、日本語/英語表示、アプリ内ヘルプ。

★アプリID(パッケージ名)`com.shinjo.shonanandroid`はタブレット版と同じ。同じ端末に両方を並べて
インストールすることはできない(後から入れた方が前の方を上書きしようとし、署名が異なるとインストールに失敗する)。

## インストール

### 前提条件

- macOS + [Android Studio](https://developer.android.com/studio)(Android SDKが同梱されます)
- Android Studio の SDK Manager > SDK Tools から **NDK (Side by side) 27.3.13750724** をインストール
- Android端末(minSdk 26以上、arm64-v8a)。端末の開発者向けオプションでUSBデバッグを有効化

### 未クローンのマシンで初めて使う場合

このリポジトリはPrivateなので匿名の`curl | bash`は使えない。`gh auth login`済みの
マシンであれば、`bootstrap.sh`だけ先に取得してから実行することでclone不要で始められる:

```sh
gh api repos/kazushinjo/Shonan_Lite-androidPhone/contents/bootstrap.sh \
  --jq '.content' | base64 -d > bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh                       # $HOME/Shonan_Lite-androidPhone へclone
./bootstrap.sh ~/path/to/dir         # clone先を指定する場合
./bootstrap.sh ~/path/to/dir --build-only  # install.shへの追加引数も渡せる
```

git-lfsが未インストールなら自動で`brew install git-lfs`を試みる。既に`~/Shonan_Lite-androidPhone`
等にcloneが存在する場合は`git pull --ff-only`で更新してから続行する。

### 既にクローン済みの場合の手順

```sh
./install.sh
```

- Android SDK/NDKの検出、`android/local.properties`の自動生成、`assembleDebug`ビルド、接続中の端末への`adb install`までを自動で行う
- 端末が1台も接続されていない場合は、生成されたAPK(`android/app/build/outputs/apk/debug/app-debug.apk`)を手動で端末へ転送してインストールしてください
- 複数端末が接続されている場合は、対象を指定した`adb install`コマンド例を表示するので、それに従ってください

### オプション

```sh
./install.sh --build-only   # ビルドのみ行い、adb installはしない
./install.sh --clean        # ビルド前に ./gradlew clean を実行する
./install.sh --release      # assembleReleaseでビルド
```

`--release`は、`android/keystore/keystore.properties`(と鍵ファイル。Git管理外)があれば署名して端末へインストール
まで行います。無い場合は未署名APKになり、そのままでは端末にインストールできません。

### Android Studioなしでビルドする場合(一般ユーザー向け)

Android Studioをインストールしていない場合でも、`cli-install.sh`を使えばコマンドラインのみで
ソースコードからビルド・インストールできます。Android SDK Command-line Toolsの取得、
必要なplatform/NDKのセットアップまで自動で行い、最後に`install.sh`へ処理を引き継ぎます。

```sh
brew install openjdk@17   # Javaが未インストールの場合のみ
./cli-install.sh
```

`install.sh`と同じオプション(`--build-only`、`--clean`、`--release`)がそのまま渡せます。

```sh
./cli-install.sh --build-only
```

## Installation

### Prerequisites

- macOS + [Android Studio](https://developer.android.com/studio) (bundles the Android SDK)
- Install **NDK (Side by side) 27.3.13750724** from Android Studio's SDK Manager > SDK Tools
- An Android device (minSdk 26+, arm64-v8a). Enable USB debugging under Developer Options

### First use on a machine with no clone yet

This repository is private, so an anonymous `curl | bash` won't work. On a machine
already signed in with `gh auth login`, you can fetch just `bootstrap.sh` first and
run it to get started without cloning by hand:

```sh
gh api repos/kazushinjo/Shonan_Lite-androidPhone/contents/bootstrap.sh \
  --jq '.content' | base64 -d > bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh                       # clones to $HOME/Shonan_Lite-androidPhone
./bootstrap.sh ~/path/to/dir         # clone to a specific directory
./bootstrap.sh ~/path/to/dir --build-only  # extra args are passed through to install.sh
```

If git-lfs isn't installed, it automatically tries `brew install git-lfs`. If a clone
already exists (e.g. under `~/Shonan_Lite-androidPhone`), it updates it with `git pull --ff-only`
before continuing.

### Steps if you already have a clone

```sh
./install.sh
```

- Automatically detects the Android SDK/NDK, generates `android/local.properties`, runs an `assembleDebug` build, and runs `adb install` on any connected device
- If no device is connected, manually transfer the built APK (`android/app/build/outputs/apk/debug/app-debug.apk`) to your device and install it
- If multiple devices are connected, it prints an `adb install` command for each device — follow the printed instructions to pick one

### Options

```sh
./install.sh --build-only   # build only, skip adb install
./install.sh --clean        # run ./gradlew clean before building
./install.sh --release      # build with assembleRelease
```

With `--release`, if `android/keystore/keystore.properties` (and the key file, both outside Git) exist, the APK is signed
and installed on the device. Otherwise the APK is unsigned and cannot be installed on a device as is.

### Building without Android Studio (for general users)

If you don't have Android Studio installed, `cli-install.sh` lets you build and install
from source using only the command line. It automatically downloads the Android SDK
Command-line Tools, sets up the required platform/NDK, and then hands off to `install.sh`.

```sh
brew install openjdk@17   # only if Java isn't installed yet
./cli-install.sh
```

It accepts the same options as `install.sh` (`--build-only`, `--clean`, `--release`).

```sh
./cli-install.sh --build-only
```

## 免責事項

1. **無保証・自己責任**  
   本ソフトウェアは現状のまま(AS IS)で提供され、動作、品質、特定の目的への適合性を含め、いかなる保証もありません。
   本ソフトウェアの使用または使用できないことによって生じた、機器の破損、データの消失、電波障害、その他一切の損害について、
   開発者は責任を負いません。ご自身の責任においてご利用ください。
2. **免許と法令の順守**  
   本ソフトウェアは、アマチュア無線のDATV(デジタルATV)実験のための送受信ソフトウェアです。電波を送信するには、運用する
   国・地域の法令に基づく免許が必要です(日本国内ではアマチュア局の免許)。周波数、空中線電力、電波の型式、運用できる範囲などの
   法令(日本国内では電波法および関係規則)を守ってください。免許のない送信や、免許の範囲を超えた送信は、法令違反となることが
   あります。本ソフトウェアは、設定された周波数・出力・変調方式が法令に適合していることを確認も保証もしません。
   送信の内容と結果は、すべて使用者の責任です。
3. **機器の取り扱い**  
   PlutoのTXとRXの接続、外部アンプ(PA)・アッテネータ・アンテナの接続、送信出力の設定を誤ると、機器を破損したり、
   他の無線局へ障害を与えたりするおそれがあります。機器の仕様を確認し、使用者の責任で行ってください。
   特に、オンデバイス復調でRFループバック試験を行うときは、TXをRXへ直接接続せず、40 dB以上の減衰器を介してください。
4. **第三者ソフトウェアとライセンス**  
   本ソフトウェアは、FFmpeg、aff3ct、StreamPU、libiio、GNU Radio、gr-dvbs2rx、VOLK、Boost、GMP、spdlog、libxml2、
   AndroidX(Jetpack Compose・CameraX)、SSHJ、Bouncy Castleなどの第三者ソフトウェアを利用・同梱します。
   それぞれのライセンスに従います。本ソフトウェア自体は GNU General Public License v3(またはそれ以降のバージョン)の下で
   提供されます(下記「ライセンス」参照)。
5. **動作について**  
   ご使用の端末・環境によって動作が異なる場合や、未発見の不具合が含まれる可能性があります。

## クレジット

- 受信部の方式考案・受信部原システム設計: 山崎慎慈氏(JE1BTA) — `rpi-dvbs2-receiver-gui`の設計に基づく
- 受信部安定化調査修正・再捕捉修正・本アプリ開発: 真城和一(JA6FUF/JH1XHX)
- 本アプリは、Dave Crump氏(G8GKQ)が開発したDATV送受信機プロジェクト「Portsdown」に啓発され、開発したものです。同氏の先駆的な取り組みに感謝いたします。

## ライセンス

本プログラムはフリーソフトウェアです。GNU General Public License v3(またはそれ以降のバージョン)の下で
再配布・改変することができます。

```
Copyright (C) 2026  Kazuichi Shinjo
Copyright of `rpi-dvbs2-receiver-gui` is held by Shinji Yamazaki.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

ライセンス全文は<https://www.gnu.org/licenses/gpl-3.0.html>を参照してください。

## Disclaimer

1. **No warranty; use at your own risk**  
   This software is provided "AS IS" without warranty of any kind, including any warranty of operation, quality or fitness for
   a particular purpose. The developers accept no liability for any damage arising from the use of, or inability to use, this
   software, including damage to equipment, loss of data and radio interference. Use it at your own risk.
2. **Licensing and compliance with the law**  
   This software is for amateur-radio DATV (digital ATV) experiments. Transmitting requires a license under the laws of the
   country or region where you operate (in Japan, an amateur station license). Observe the applicable laws on frequency,
   transmitter power, emission type and permitted operation (in Japan, the Radio Act and related regulations). Transmitting
   without a license, or beyond the scope of your license, may violate the law. This software neither checks nor guarantees
   that the configured frequency, power and modulation comply with the law. You are solely responsible for what you transmit
   and for the results.
3. **Handling of equipment**  
   Wrong connections between the Pluto's TX and RX, wrong external amplifier (PA), attenuator or antenna connections, or wrong
   transmit power settings may damage equipment or interfere with other stations. Check the specifications of your equipment
   and do this at your own responsibility. In particular, when running an RF loopback test with on-device demodulation, never
   connect TX directly to RX; use an attenuator of 40 dB or more.
4. **Third-party software and license**  
   This software uses and bundles third-party software such as FFmpeg, aff3ct, StreamPU, libiio, GNU Radio, gr-dvbs2rx, VOLK,
   Boost, GMP, spdlog, libxml2, AndroidX (Jetpack Compose, CameraX), SSHJ and Bouncy Castle, each under its own license.
   This software itself is provided under the GNU General Public License v3 (or any later version); see "License" below.
5. **About behavior**  
   Behavior may differ depending on your device and environment, and undiscovered defects may remain.

## Credits

- Reception method design and original receiver system design: Shinji Yamazaki (JE1BTA) — based on the design of `rpi-dvbs2-receiver-gui`
- Receiver stabilization investigation/fixes, re-acquisition fixes, and app development: Kazuichi Shinjo (JA6FUF/JH1XHX)
- This application was developed inspired by "Portsdown", the DATV transceiver project created by Dave Crump (G8GKQ). We extend our deep gratitude for his pioneering work.

## License

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License v3 (or any later version).

```
Copyright (C) 2026  Kazuichi Shinjo
Copyright of `rpi-dvbs2-receiver-gui` is held by Shinji Yamazaki.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

See <https://www.gnu.org/licenses/gpl-3.0.html> for the full license text.

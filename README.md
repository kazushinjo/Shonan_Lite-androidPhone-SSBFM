# Shonan_Lite-androidPhone-SSBFM

Pluto直結のアマチュア無線用トランシーバー「Shonan」のAndroidスマートフォン版です。DVB-S2によるDATV(デジタルATV)の
送受信に加えて、**SSB(USB)・FMの音声送受信**と**RSSI測定**ができます。SSB/FMの信号処理は
[Langstone-V2](https://github.com/g4eml/Langstone-V2)(Colin Durbridge氏 G4EML、GPLv3)のAdalm Pluto版をC++へ移植したものです。
元になったのは電話版[Shonan_Lite-androidPhone](https://github.com/kazushinjo/Shonan_Lite-androidPhone)で、
狭い画面幅に合わせて、画面上部のタブで機能を切り替える単一画面構成にしています。

An Android smartphone version of "Shonan", an amateur-radio transceiver directly connected to a Pluto. In addition to
DATV (digital ATV) transmit/receive with DVB-S2, it provides **SSB (USB) and FM voice transmit/receive** and
**RSSI measurement**. The SSB/FM signal processing is a C++ port of the Adalm Pluto version of
[Langstone-V2](https://github.com/g4eml/Langstone-V2) by Colin Durbridge, G4EML (GPLv3). It is based on the phone
version [Shonan_Lite-androidPhone](https://github.com/kazushinjo/Shonan_Lite-androidPhone) and uses a single screen
whose functions are switched with tabs at the top, to fit a narrow phone screen.

- アプリ名「Shonan SSB/FM」、アプリID `com.shinjo.shonanandroid.ssbfm`。元の電話版(`com.shinjo.shonanandroid`)と同じ端末に並べてインストールできます。
- App name "Shonan SSB/FM", app ID `com.shinjo.shonanandroid.ssbfm`. It can be installed side by side with the original phone version (`com.shinjo.shonanandroid`).
- 実装の詳細は[`android/README.md`](android/README.md)を参照 / See [`android/README.md`](android/README.md) for the implementation details.

## 主な機能

画面上部のタブで切り替えます(左から順に)。上部バーには「アプリ再起動」「終了」があります。

| タブ | 内容 |
|---|---|
| SSB/FM | PlutoでUSB・FMの音声送受信(Langstone-V2の移植)。バンド1.2G/2.4G/5.6G、スペクトル・ウォーターフォール、PA/PTTコントローラ連携 |
| (空白) | SSB/FMとDATV側のタブの区切り(押しても何も起きません) |
| 送信 | DATV送信(UDP-TSでPlutoへ送り、Pluto内蔵の変調器で送信。HD 1280x720・30fps) |
| 受信 | DATV受信(外部復調機器からのUDP-TS、またはオンデバイス復調)。ロックすると全画面表示 |
| RSSI測定 | 運用周波数の周辺を走査してRSSIを測り、最も強い周波数を探す |
| 周波数 | DATVのバンドと周波数 |
| 設定１ | 表示言語・オンデバイス復調(GNU Radio)・PA/PTTコントローラ |
| 設定２ | DATVの受信感度(AGC/手動 0〜73dB)・送信出力(出力減衰量 -70〜0dB) |
| 設定３ | DATVのシンボルレート・誤り訂正(1/2・3/5・8/9)・変調方式(QPSK・8PSK) |
| 設定４ | 映像ソース(カメラ/写真/テストパターン、マイク音声)・配信先(PlutoのIPアドレス、受信ポート) |
| ヘルプ | アプリ内の操作説明とクレジット |

そのほか、起動時のPluto自動検出(端末の接続中ネットワークを探し、見つからなければESP32ブリッジへ問い合わせ)と
Pluto再起動、日本語/英語表示に対応します。

## 操作説明

### SSB/FMの使い方

1. 初回のみ「設定４」タブの「配信先」でPlutoのIPアドレスを確認する(SSB/FMもDATVと同じPlutoを使います)。
2. PA/PTTコントローラ(ESP32+W5500)を使う場合は、「設定１」タブで「PA/PTTコントローラを使う」をONにしてIPアドレスを入れる
   (既定はON・192.168.0.100。使わない場合はOFFにします)。
3. 「SSB/FM」タブでバンド(1.2G・2.4G・5.6G)とモード(USB・FM)を選び、周波数を合わせる。
4. 「開始」を押して受信を始める(DATVの送受信・RSSI測定は自動で止まります)。
5. PTTを押している間だけ送信する。終わったら「停止」を押す。

### DATV送信の設定順序

1. 「設定４」タブの「映像ソース」で送信する映像(背面カメラ・前面カメラ・写真・テストパターン)を選ぶ。
2. 「周波数」タブでバンドと周波数を設定する(相手の受信周波数と一致させる)。
3. 「設定３」タブでシンボルレート・誤り訂正・変調方式を設定する(相手と一致させる)。
4. 「設定４」タブの「配信先」でPluto TxのIPアドレスを確認する(初回のみ。送信はPlutoのUDP-TSポート8282へ固定で送ります)。
5. 「設定２」タブで出力減衰量を設定する(0dBが最大出力、負の値ほど出力が下がる)。
6. 「送信」タブでプレビューを確認し「送信開始」を押す。

### DATV受信の設定順序

1. 「周波数」「設定３」の各タブを、送信側と同じ値に設定する。
2. 「設定１」タブで受信方式を選ぶ。オフ(既定)なら外部復調機器からのUDP-TSを「設定４」タブ「配信先」のTSポート/ステータスポートで
   待ち受けます。オンにするとPlutoのRF信号をAndroid自身が復調します(送信と同時に行う場合は必ずアッテネータを接続すること)。
3. 「設定２」タブでAGC(自動)のままにするか、オフにして手動でゲインを設定する。
4. 「受信」タブで「受信開始」を押す。ロックすると自動で映像が全画面表示になります。

### 各タブの説明

- **SSB/FM**: DATVの送受信・RSSI測定とは同時に使えず、「開始」を押すとそれらを止めてから受信を始めます。開始時にPlutoの
  DATV送信処理(pluto_dvb)をSSHで止め、「停止」でPlutoの設定(サンプルレート・周波数・ゲイン)を開始前の状態へ戻します。
  - 周波数表示: MHz単位の7桁(例 1295.100、最下位は1kHz)。桁をタップするとその桁が同調ステップになり、「−」「+」で1ステップずつ
    動きます。長押しで周波数を直接入力できます。
  - バンド: 周波数表示の右の1.2G・2.4G・5.6Gで切り替えます。バンドごとに最後の周波数とモードを覚えます
    (初回は1295.000/2427.000/5760.000 MHz・FM)。
  - モード: USB・FM(±5kHz偏移)。
  - スペクトル/ウォーターフォール(幅48kHz): 左右にドラッグすると同調、タップするとその周波数へ移動します。
  - Sメーター: 受信中は信号の強さ(相対値)、送信中はマイクレベルを表示します。
  - 音量・スケルチ(FMのみ有効。USBではグレー表示)・マイク・RF利得(AGCのチェックで自動)・送信減衰(0〜89dB)。
  - PTT: 押している間だけ送信します。「PTTを押すたびに送受信を切り替える」をチェックすると押すたびに切り替えます。
  - PA/PTTコントローラ連携: 「設定１」タブで「PA/PTTコントローラを使う」がONのときは、送信開始時にESP32+W5500
    (W5500_PA_PTT_Control)へ`GET /tx?state=on`を送って150ms待ってから電波を出し、送信終了時は電波を止めてから
    `GET /tx?state=off`を送ります。ONなのにESP32が応答しない場合は送信しません。
- **送信**: 「設定４」の映像ソースのプレビューを表示し、「送信開始」で現在の周波数・シンボルレート・変調方式・誤り訂正・出力減衰量で
  送信します。「設定１」ボタンで設定１タブへ、オンデバイス復調が有効なら「受信画面へ」ボタンで受信タブへ移動できます。
- **受信**: 「受信開始」で受信を始め、ロックすると映像を全画面表示にします(上部のタブも隠れます)。画面をタップすると5秒間だけ
  状態カードとボタンを表示します。ロックが1.5秒以上外れると全画面表示を解除します。
- **RSSI測定**: 「周波数」タブの運用周波数を中心に±5/10/20MHzをステップ(kHz)ごとに走査し、RSSIのグラフと「最も強い周波数」を
  表示します(RSSIは値が小さいほど強い信号)。検索方法は「連続」(「検索停止」まで繰り返す)と「1回」、RXゲインは「設定２」と共通です。
  「設定１」のオンデバイス復調がONのときはテスト用で、検索開始と同時に自局もテストパターンで送信して自分の電波を測ります
  (アッテネータを必ず接続すること)。OFFのときは送信せず、相手局の電波を測ります(相手局が送信していなければグラフが平らなのは正常)。
  受信中は使えません。SSB/FMは検索開始時に自動で止めます。タブを離れると検索は止まります。
- **周波数**: 左のバンド一覧から選ぶか、右のテンキーで周波数(kHz)を直接入力します。DATVの送信と受信で共通です
  (SSB/FMの周波数はSSB/FMタブで別に設定します)。
- **設定１**: 表示言語(ホーム画面を除く)、オンデバイス復調(ONにするとPluto1台でRFループバック試験ができる。有効化時に
  アッテネータの警告を表示)、PA/PTTコントローラ(使う/使わない、IPアドレス)。
- **設定２**: DATVの受信感度(「自動(AGC)」または手動0〜73dB。受信のロック状態も表示)と送信出力(出力減衰量-70〜0dB)。
- **設定３**: シンボルレート(プリセット250k〜2000k、または直接入力100〜5000 kS/s)・誤り訂正・変調方式(コンステレーション表示付き)。
  下段に組み合わせと帯域幅・ビットレートの目安を表示します。
- **設定４**: 左が映像ソース(背面カメラ・前面カメラ・写真・テストパターン。写真にはコールサインと備考を焼き込める。
  マイク音声の送信ON/OFF)、右が配信先(PlutoのIPアドレスと「自動検出」、UDP-TSポート8282(固定)、受信用のTSポート/ステータスポート)。
  ここのPlutoのIPアドレスはDATV・SSB/FM・RSSI測定のすべてで使います。
- **アプリ再起動・終了**: 「アプリ再起動」は送受信(DATV・SSB/FM)を止めてからPlutoへ再起動を要求し、復旧を待ちます
  (起動時にも同じ処理を行います)。「終了」は確認のあとアプリを終了します。

## Features

Switch with the tabs at the top (from left to right). The top bar has "App Restart" and "Quit".

| Tab | Contents |
|---|---|
| SSB/FM | USB/FM voice transmit/receive with Pluto (a port of Langstone-V2). Bands 1.2G/2.4G/5.6G, spectrum and waterfall, PA/PTT controller link |
| (blank) | A spacer between SSB/FM and the DATV tabs (tapping it does nothing) |
| Transmit | DATV transmit (UDP-TS to Pluto, modulated by Pluto's onboard modulator; HD 1280x720 at 30 fps) |
| Receive | DATV receive (UDP-TS from an external demodulator, or on-device demodulation); fullscreen when locked |
| RSSI | Sweeps around the operating frequency, measures RSSI, and finds the strongest frequency |
| Frequency | DATV band and frequency |
| Config 1 | Display language, on-device demodulation (GNU Radio), PA/PTT controller |
| Config 2 | DATV receive gain (AGC or manual 0–73 dB) and transmit power (attenuation -70–0 dB) |
| Config 3 | DATV symbol rate, FEC (1/2, 3/5, 8/9), and modulation (QPSK, 8PSK) |
| Config 4 | Video source (camera/photo/test pattern, mic audio) and stream output (Pluto IP address, receive ports) |
| Help | In-app instructions and credits |

It also auto-detects Pluto at startup (searching the network the device is connected to, then asking the ESP32
bridge), reboots Pluto at startup, and supports Japanese and English.

## Operating Instructions

### Using SSB/FM

1. First time only: check Pluto's IP address on the Config 4 tab (Stream Output). SSB/FM uses the same Pluto as DATV.
2. If you use the PA/PTT controller (ESP32+W5500), turn on "Use the PA/PTT controller" on the Config 1 tab and enter its
   IP address (default: on, 192.168.0.100). Turn it off if you do not use one.
3. On the SSB/FM tab, choose the band (1.2G/2.4G/5.6G) and mode (USB/FM), then tune the frequency.
4. Tap Start to begin receiving (DATV TX/RX and RSSI measurement are stopped automatically).
5. Hold PTT to transmit. Tap Stop when you are finished.

### Setup Order for DATV Transmit

1. On the Config 4 tab (Video Source), choose what to transmit (rear camera, front camera, a photo, or the test pattern).
2. On the Frequency tab, set the band and frequency (it must match the receiving station's frequency).
3. On the Config 3 tab, set the symbol rate, FEC, and modulation (they must match the receiving station).
4. On the Config 4 tab (Stream Output), check Pluto Tx's IP address (first time only). The stream always goes to Pluto's
   fixed UDP-TS port 8282.
5. On the Config 2 tab, set the attenuation (0 dB is maximum output; more negative values reduce the output).
6. On the Transmit tab, check the preview and tap Start.

### Setup Order for DATV Receive

1. Set the Frequency and Config 3 tabs to the same values as the transmitting station.
2. On the Config 1 tab, choose the receive path. Off (default) listens for UDP-TS from an external demodulator on the
   TS/Status ports set on the Config 4 tab (Stream Output); on makes Android demodulate Pluto's RF signal itself (always
   connect an attenuator if you transmit at the same time).
3. On the Config 2 tab, keep AGC (auto) on, or turn it off and set the gain manually.
4. On the Receive tab, tap Start. Once locked, the video switches to fullscreen automatically.

### Tab Reference

- **SSB/FM**: It cannot run at the same time as DATV TX/RX or RSSI measurement; tapping Start stops them first and then
  starts receiving. On start, the app stops Pluto's DATV transmitter process (pluto_dvb) over SSH, and Stop restores
  Pluto's settings (sample rate, frequencies, gains) to what they were before.
  - Frequency display: 7 digits in MHz (e.g. 1295.100; the last digit is 1 kHz). Tap a digit to make it the tuning step,
    and use − / + to move one step. Long-press to type a frequency.
  - Band: switch with the 1.2G/2.4G/5.6G buttons to the right of the frequency. Each band remembers its last frequency
    and mode (initially 1295.000/2427.000/5760.000 MHz, FM).
  - Mode: USB or FM (±5 kHz deviation).
  - Spectrum/waterfall (48 kHz wide): drag left/right to tune, or tap to jump to that frequency.
  - S-meter: shows the signal strength (relative) while receiving and the mic level while transmitting.
  - Volume, squelch (FM only; grayed out in USB), mic gain, RF gain (check AGC for automatic), and TX attenuation (0–89 dB).
  - PTT: transmits while held. Check "Latching PTT" to toggle TX/RX with each press.
  - PA/PTT controller link: when "Use the PA/PTT controller" is on (Config 1 tab), at TX start the app sends
    `GET /tx?state=on` to the ESP32+W5500 (W5500_PA_PTT_Control), waits 150 ms, and then transmits; at TX end it stops
    transmitting first and then sends `GET /tx?state=off`. If it is on but the ESP32 does not respond, the app does not transmit.
- **Transmit**: Shows a preview of the video source set on Config 4; Start transmits with the current frequency, symbol
  rate, modulation, FEC, and attenuation. The "Config 1" button opens the Config 1 tab, and when on-device demodulation is
  enabled, the "Receive Screen" button opens the Receive tab.
- **Receive**: Start begins receiving; once locked, the video goes fullscreen (the tabs at the top are hidden too). Tap the
  screen to show the status card and buttons for 5 seconds. Fullscreen ends if the lock is lost for 1.5 seconds or more.
- **RSSI**: Sweeps ±5/10/20 MHz around the operating frequency (Frequency tab) in kHz steps and shows an RSSI graph and the
  strongest frequency (a smaller RSSI value means a stronger signal). Search modes are Repeat (until Stop) and Once; the RX
  gain is shared with Config 2. When on-device demodulation (Config 1) is on, this is a test mode: the app also transmits the
  test pattern and measures its own signal (always connect an attenuator). When it is off, the app does not transmit and
  measures the other station (a flat graph is normal if nobody is transmitting). It cannot run while receiving; SSB/FM is
  stopped automatically when a search starts. Leaving the tab stops the search.
- **Frequency**: Choose a band from the list on the left, or type the frequency in kHz on the keypad on the right. DATV
  transmit and receive share it (the SSB/FM frequency is set separately on the SSB/FM tab).
- **Config 1**: Display language (except the Home screen), on-device demodulation (enables an RF loopback test with a single
  Pluto; an attenuator warning is shown when enabling it), and the PA/PTT controller (on/off, IP address).
- **Config 2**: DATV receive gain ("Auto (AGC)" or manual 0–73 dB; the receive lock state is also shown) and transmit power
  (attenuation -70–0 dB).
- **Config 3**: Symbol rate (presets 250k–2000k, or Custom 100–5000 kS/s), FEC, and modulation (with a constellation
  diagram). The bottom line shows the combination and the estimated bandwidth and bit rate.
- **Config 4**: Video source on the left (rear camera, front camera, photo, or test pattern; a callsign and note can be burned
  into the photo; mic audio on/off) and stream output on the right (Pluto's IP address with Auto-Detect, the fixed UDP-TS
  port 8282, and the TS/Status ports for receiving). This Pluto IP address is used for DATV, SSB/FM, and RSSI measurement.
- **App Restart and Quit**: "App Restart" stops TX/RX (DATV and SSB/FM), asks Pluto to reboot, and waits for it to come back
  (the same happens at startup). "Quit" closes the app after a confirmation.

## SSB/FM機能の技術メモ / Technical Notes on SSB/FM

- **受信**: Plutoを528kspsで受け、周波数変換+1/11間引きで48kHzにし、複素帯域フィルタ(USB +300〜+3000Hz / FM ±7.5kHz)→
  復調(USB: 実部+AGC、FM: 直交復調+75µsディエンファシス)→音声フィルタ→スピーカー(AAudio)。受信LOは100kHz単位に置き、
  希望周波数を+50〜+150kHz側で受けます(DC付近を避けるLangstoneと同じ方式)。
- **送信**: マイク48kHz→USB: 複素帯域フィルタで片側波帯を生成 / FM: 75µsプリエンファシス+周波数変調→11倍補間→Pluto。
  送信中は受信LO、受信中は送信LOを止めます。
- **Plutoの扱い**: 528kspsはAD9361のFIRなしの下限を下回るため、libAD9361-iioの`ad9361_set_bb_rate()`と同じ手順でFIR
  (fir_128_4)を読み込みます。GNU Radioの実行エンジンは使わず、libiio v1を直接使います。
- **実装**: `android/app/src/main/cpp/ssbfm_dsp.h`(DSP)、`ssbfm_bridge.cpp`(Pluto制御・AAudio・JNI)、
  `android/app/src/main/kotlin/.../ssbfm/`、`ui/SsbFmScreen.kt`。`android/app/src/main/cpp/test/ssbfm_dsp_test.cpp`は
  送信DSP→疑似RF→受信DSPの折り返し検証です(実機で側波帯抑圧・SINAD・処理時間を確認。ビルド方法はファイル先頭)。

- **Receive**: Pluto at 528 ksps → frequency shift and 1/11 decimation to 48 kHz → complex band-pass filter (USB +300 to
  +3000 Hz / FM ±7.5 kHz) → demodulation (USB: real part + AGC; FM: quadrature demodulation + 75 µs de-emphasis) → audio
  filter → speaker (AAudio). The RX LO is placed on a 100 kHz grid and the wanted frequency is received 50–150 kHz above it
  (the same approach as Langstone, which avoids the DC region).
- **Transmit**: mic at 48 kHz → USB: single sideband generated by a complex band-pass filter / FM: 75 µs pre-emphasis +
  frequency modulation → 11× interpolation → Pluto. The RX LO is powered down while transmitting and the TX LO while receiving.
- **Pluto handling**: 528 ksps is below the AD9361's minimum without its FIR, so the FIR (fir_128_4) is loaded with the same
  procedure as libAD9361-iio's `ad9361_set_bb_rate()`. The GNU Radio scheduler is not used; libiio v1 is called directly.
- **Implementation**: `android/app/src/main/cpp/ssbfm_dsp.h` (DSP), `ssbfm_bridge.cpp` (Pluto control, AAudio, JNI),
  `android/app/src/main/kotlin/.../ssbfm/`, `ui/SsbFmScreen.kt`. `android/app/src/main/cpp/test/ssbfm_dsp_test.cpp` is a
  loopback test (TX DSP → simulated RF → RX DSP) run on a real device to check sideband suppression, SINAD, and processing time
  (build instructions are at the top of the file).

## インストール

### 前提条件

- macOS + [Android Studio](https://developer.android.com/studio)(Android SDKが同梱されます)
- Android Studio の SDK Manager > SDK Tools から **NDK (Side by side) 27.3.13750724** をインストール
- Android端末(minSdk 26以上、arm64-v8a)。端末の開発者向けオプションでUSBデバッグを有効化

### 未クローンのマシンで初めて使う場合

このリポジトリはPrivateなので匿名の`curl | bash`は使えません。`gh auth login`済みのマシンであれば、`bootstrap.sh`だけ先に
取得してから実行することで、手動でcloneせずに始められます:

```sh
gh api repos/kazushinjo/Shonan_Lite-androidPhone-SSBFM/contents/bootstrap.sh \
  --jq '.content' | base64 -d > bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh                       # $HOME/Shonan_Lite-androidPhone-SSBFM へclone
./bootstrap.sh ~/path/to/dir         # clone先を指定する場合
./bootstrap.sh ~/path/to/dir --build-only  # install.shへの追加引数も渡せる
```

git-lfsが未インストールなら自動で`brew install git-lfs`を試みます。既に`~/Shonan_Lite-androidPhone-SSBFM`等に
cloneが存在する場合は`git pull --ff-only`で更新してから続行します。

### 既にクローン済みの場合の手順

```sh
./install.sh
```

- Android SDK/NDKの検出、`android/local.properties`の自動生成、`assembleDebug`ビルド、接続中の端末への`adb install`までを自動で行います
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

Android Studioをインストールしていない場合でも、`cli-install.sh`を使えばコマンドラインのみでソースコードからビルド・
インストールできます。Android SDK Command-line Toolsの取得、必要なplatform/NDKのセットアップまで自動で行い、最後に
`install.sh`へ処理を引き継ぎます。

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

This repository is private, so an anonymous `curl | bash` won't work. On a machine already signed in with
`gh auth login`, you can fetch just `bootstrap.sh` first and run it to get started without cloning by hand:

```sh
gh api repos/kazushinjo/Shonan_Lite-androidPhone-SSBFM/contents/bootstrap.sh \
  --jq '.content' | base64 -d > bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh                       # clones to $HOME/Shonan_Lite-androidPhone-SSBFM
./bootstrap.sh ~/path/to/dir         # clone to a specific directory
./bootstrap.sh ~/path/to/dir --build-only  # extra args are passed through to install.sh
```

If git-lfs isn't installed, it automatically tries `brew install git-lfs`. If a clone already exists (e.g. under
`~/Shonan_Lite-androidPhone-SSBFM`), it updates it with `git pull --ff-only` before continuing.

### Steps if you already have a clone

```sh
./install.sh
```

- Automatically detects the Android SDK/NDK, generates `android/local.properties`, runs an `assembleDebug` build, and runs `adb install` on any connected device
- If no device is connected, manually transfer the built APK (`android/app/build/outputs/apk/debug/app-debug.apk`) to your device and install it
- If multiple devices are connected, it prints an `adb install` command for each device; follow the printed instructions to pick one

### Options

```sh
./install.sh --build-only   # build only, skip adb install
./install.sh --clean        # run ./gradlew clean before building
./install.sh --release      # build with assembleRelease
```

With `--release`, if `android/keystore/keystore.properties` (and the key file, both outside Git) exist, the APK is signed
and installed on the device. Otherwise the APK is unsigned and cannot be installed on a device as is.

### Building without Android Studio (for general users)

If you don't have Android Studio installed, `cli-install.sh` lets you build and install from source using only the command
line. It automatically downloads the Android SDK Command-line Tools, sets up the required platform/NDK, and then hands off
to `install.sh`.

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
   本ソフトウェアは、アマチュア無線のDATV(デジタルATV)とSSB・FMによる音声通信の実験のための送受信ソフトウェアです。
   電波を送信するには、運用する国・地域の法令に基づく免許が必要です(日本国内ではアマチュア局の免許)。周波数、空中線電力、
   電波の型式、運用できる範囲などの法令(日本国内では電波法および関係規則)を守ってください。免許のない送信や、免許の範囲を
   超えた送信は、法令違反となることがあります。本ソフトウェアは、設定された周波数・出力・変調方式が法令に適合していることを
   確認も保証もしません。送信の内容と結果は、すべて使用者の責任です。
3. **機器の取り扱い**  
   PlutoのTXとRXの接続、外部アンプ(PA)・アッテネータ・アンテナ・PA/PTTコントローラの接続、送信出力の設定を誤ると、
   機器を破損したり、他の無線局へ障害を与えたりするおそれがあります。機器の仕様を確認し、使用者の責任で行ってください。
   特に、オンデバイス復調でRFループバック試験を行うときやRSSI測定のテストモードでは、TXをRXへ直接接続せず、
   40 dB以上の減衰器を介してください。
4. **第三者ソフトウェアとライセンス**  
   本ソフトウェアは、Langstone-V2(移植)、libAD9361-iio(FIR係数と設定手順)、FFmpeg、aff3ct、StreamPU、libiio、GNU Radio、
   gr-dvbs2rx、VOLK、Boost、GMP、spdlog、libxml2、AndroidX(Jetpack Compose・CameraX)、SSHJ、Bouncy Castleなどの
   第三者ソフトウェアを利用・同梱します。それぞれのライセンスに従います。本ソフトウェア自体は GNU General Public License v3
   (またはそれ以降のバージョン)の下で提供されます(下記「ライセンス」参照)。
5. **動作について**  
   ご使用の端末・環境によって動作が異なる場合や、未発見の不具合が含まれる可能性があります。

## クレジット

- 受信部の方式考案・受信部原システム設計: 山崎慎慈氏(JE1BTA) — `rpi-dvbs2-receiver-gui`の設計に基づく
- 受信部安定化調査修正・再捕捉修正・本アプリ開発: 真城和一(JA6FUF/JH1XHX)
- 本アプリは、Dave Crump氏(G8GKQ)が開発したDATV送受信機プロジェクト「Portsdown」に啓発され、開発したものです。同氏の先駆的な取り組みに感謝いたします。
- SSB/FM送受信機能: Colin Durbridge氏(G4EML)の「Langstone-V2」(https://github.com/g4eml/Langstone-V2、GPLv3)の
  Adalm Pluto版の信号処理(`Lang_TRX_Pluto.py`)とPluto制御(`LangstoneGUI_Pluto.c`)をC++へ移植して使用しています。
  Langstone-V2はGNU General Public License v3(GPLv3)のソフトウェアであるため、それを使用した本アプリもGPLv3で配布します。
- PlutoのFIR係数(fir_128_4)と`ad9361_set_bb_rate()`の設定手順: Analog Devices「libAD9361-iio」
  (https://github.com/analogdevicesinc/libad9361-iio、LGPL-2.1)

## ライセンス

本プログラムはフリーソフトウェアです。GNU General Public License v3(またはそれ以降のバージョン)の下で
再配布・改変することができます。全文は[LICENSE](LICENSE)を参照してください。

```
Copyright (C) 2026  Kazuichi Shinjo
Copyright of `rpi-dvbs2-receiver-gui` is held by Shinji Yamazaki.
The SSB/FM transceiver is derived from Langstone-V2, Copyright (C) Colin Durbridge (G4EML), GPLv3.

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

## Disclaimer

1. **No warranty; use at your own risk**  
   This software is provided "AS IS" without warranty of any kind, including any warranty of operation, quality or fitness for
   a particular purpose. The developers accept no liability for any damage arising from the use of, or inability to use, this
   software, including damage to equipment, loss of data and radio interference. Use it at your own risk.
2. **Licensing and compliance with the law**  
   This software is for amateur-radio experiments with DATV (digital ATV) and SSB/FM voice communication. Transmitting
   requires a license under the laws of the country or region where you operate (in Japan, an amateur station license).
   Observe the applicable laws on frequency, transmitter power, emission type and permitted operation (in Japan, the Radio Act
   and related regulations). Transmitting without a license, or beyond the scope of your license, may violate the law. This
   software neither checks nor guarantees that the configured frequency, power and modulation comply with the law. You are
   solely responsible for what you transmit and for the results.
3. **Handling of equipment**  
   Wrong connections between the Pluto's TX and RX, wrong external amplifier (PA), attenuator, antenna or PA/PTT controller
   connections, or wrong transmit power settings may damage equipment or interfere with other stations. Check the
   specifications of your equipment and do this at your own responsibility. In particular, when running an RF loopback test
   with on-device demodulation or the RSSI test mode, never connect TX directly to RX; use an attenuator of 40 dB or more.
4. **Third-party software and license**  
   This software uses and bundles third-party software such as Langstone-V2 (ported), libAD9361-iio (FIR coefficients and
   procedure), FFmpeg, aff3ct, StreamPU, libiio, GNU Radio, gr-dvbs2rx, VOLK, Boost, GMP, spdlog, libxml2, AndroidX (Jetpack
   Compose, CameraX), SSHJ and Bouncy Castle, each under its own license. This software itself is provided under the GNU
   General Public License v3 (or any later version); see "License" below.
5. **About behavior**  
   Behavior may differ depending on your device and environment, and undiscovered defects may remain.

## Credits

- Reception method design and original receiver system design: Shinji Yamazaki (JE1BTA), based on the design of `rpi-dvbs2-receiver-gui`
- Receiver stabilization investigation/fixes, re-acquisition fixes, and app development: Kazuichi Shinjo (JA6FUF/JH1XHX)
- This application was developed inspired by "Portsdown", the DATV transceiver project created by Dave Crump (G8GKQ). We extend our deep gratitude for his pioneering work.
- SSB/FM transceiver: a C++ port of the Adalm Pluto signal processing (`Lang_TRX_Pluto.py`) and Pluto control
  (`LangstoneGUI_Pluto.c`) of "Langstone-V2" by Colin Durbridge (G4EML) (https://github.com/g4eml/Langstone-V2, GPLv3).
  Because Langstone-V2 is licensed under the GNU General Public License v3 (GPLv3), this app, which uses it, is also
  distributed under GPLv3.
- Pluto FIR coefficients (fir_128_4) and the `ad9361_set_bb_rate()` procedure: Analog Devices "libAD9361-iio"
  (https://github.com/analogdevicesinc/libad9361-iio, LGPL-2.1)

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
v3 (or any later version). See [LICENSE](LICENSE) for the full text.

```
Copyright (C) 2026  Kazuichi Shinjo
Copyright of `rpi-dvbs2-receiver-gui` is held by Shinji Yamazaki.
The SSB/FM transceiver is derived from Langstone-V2, Copyright (C) Colin Durbridge (G4EML), GPLv3.

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

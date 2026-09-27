# Shonan Android

`ios/`にあるShonan(Swift/SwiftUI + Obj-C++、Pluto直結DVB-S2トランシーバー)のKotlin/Jetpack Compose移植。パッケージ名`com.shinjo.shonanandroid`。

Kotlin/Jetpack Compose port of Shonan (Swift/SwiftUI + Obj-C++, a DVB-S2 transceiver directly connected to a Pluto) found in `ios/`. Package name `com.shinjo.shonanandroid`.

## 参照元

- `ios/`: 移植元のiOSソース一式(ビルド成果物の`.a`静的ライブラリは除外済み。再ビルド手順は`ios/Shonan/DVBS2Vendor`および`ios/Shonan/LibiioVendor`配下、`ios/Scripts/build_ffmpeg_xcframework.sh`参照)
- `DVBS2_iOS_Port_PoC.md`: aff3ct/dvbs2のiOSクロスビルドPoC記録。Android NDK移植時のCMakeツールチェーン選定・つまずきポイント(libiio NULLポインタクラッシュ等)の元ネタ
- `DATV_iPad_spec.md`: 元設計仕様

## 構成

本番のTx/Rx経路(UDP TS方式、送信は常にPlutoオンボード変調・受信は外部復調機/オンデバイス復調の2択)と、
機器試験の経路(Pluto直接IQ送受信、aff3ct/libiio)は完全に独立している。

### 本番Tx

- `net/PlutoSettingsWriter.kt`: PlutoのWeb UI(`settings.txt`/`save.php`)へHTTPで変調パラメータ(周波数・シンボルレート・FEC・pilots等)を書き込み、Pluto自身のオンボードハードウェア変調器にDVB-S2変調をさせる。Android側では変調を一切行わない
- `third_party/build_ffmpeg_mpegts.sh`: FFmpegをmpegts構成(libavformat/libavcodec/libavutil、エンコーダ/デコーダなし)でNDK arm64-v8a向けにクロスビルド。成果物`ffmpeg-mpegts-install-arm64-v8a/`はgit管理下
- `app/src/main/cpp/ts_bridge.cpp`: 上記のJNIブリッジ(TS mux/demux)
- `tx/`: `CameraCapture`または`ColorBarSource`(テストパターン) → `H264Encoder`(MediaCodec) → `TsMuxerNative` → `TsPacketPacker` → `UdpSender` → `TxController`。生成したMPEG-TSをPlutoへUDPで送出し、Pluto側のオンボード変調器がDVB-S2変調・RF送信する

### 本番Rx

`AppSettings.useOnDeviceGRDVBS2Rx`(設定画面の「オンデバイス復調(GNU Radio)」トグル)で以下の2方式を切り替える。
どちらの経路もTS出力は共通の`TsPacketAligner`(後述)を通してから`TsDemuxerNative`/`H264DisplaySurface`へ渡す。

- **外部復調機器方式(既定)**: `UdpReceiver`が外部復調機器からのMPEG-TSをUDPで直接受信する
- **オンデバイス復調方式**: `dvbs2rx/Dvbs2rxPipeline.kt` + `app/src/main/cpp/dvbs2rx_bridge.cpp`が、PlutoのRF IQをgr-iioの`fmcomms2_source`経由で取得し、GNU Radio本体+gr-dvbs2rx(igorauad氏、GNU Radio OOTモジュール)でAndroid端末上でDVB-S2復調する。機器試験のaff3ct版(`dvbs2_bridge.cpp`)が既知のDVB-S2標準信号すら復調できないことが実機検証で判明したため、実績のある独立実装へ切り替えた経緯がある。ヘッダは`third_party/gr-dvbs2rx-install-arm64-v8a/`、実体の`.so`は`app/src/main/jniLibs/arm64-v8a/`にNDK向けクロスビルド済みのものを配置済み(ビルドスクリプトは未整備で、`.so`自体をgit管理下に置いている)

#### 受信TSの再同期

受信TSは`TsPacketAligner`で188バイトTS境界を連続検証する。これは
`rpi-dvbs2-receiver-gui/udp_relay.py`の再同期方式を移植したもので、FIFO/read
境界のずれや途中の1バイト欠損が発生しても、次の3パケットの同期バイト
(`0x47`)を探索して復帰し、7パケット(1316バイト)単位でTS demuxerへ渡す。

### 機器試験(Pluto直接IQ送受信、aff3ct/libiio)

- `third_party/build_dvbs2_deps.sh`: zstd/libxml2/libiio/aff3ct-dvbs2をNDK arm64-v8a向けにクロスビルド。成果物`dvbs2-deps-install-arm64-v8a/`はgit管理下(最大ファイルはstrip後93MB)。実機で踏んだクラッシュの修正パッチを同梱・自動適用:
  - `libiio_refresh_format_null_guard.patch`: libiioのNULLポインタクラッシュ対策(後述)
  - `radio_user_binary_fifo_eof.patch`: Radio_user_binaryのFIFO非対応対策(後述)
  - `radio_user_binary_write2.patch`: `Radio_user_binary`の出力を`std::ofstream`からPOSIXの`open`/`write`/`close`に置き換え、FIFOへの書き込みでC++ iostreamが起こす問題を回避
  - `aff3ct_bch_decoder_overalloc.patch`: aff3ct本家のBCHデコーダが作業配列を`(N_p2_1+2)×N_p2_1`(DVB-S2 QPSK 3/5で約1.07GB)確保してしまいメモリ暴走・SIGKILLする不具合を、実際に使うインデックス範囲(`t2+4`、t2は訂正可能誤り数の2倍)まで縮小して回避
- `app/src/main/cpp/dvbs2_bridge.cpp` + `dvbs2_tx_lib.cpp`/`dvbs2_rx_lib.cpp`: 上記のJNIブリッジ。KotlinからCライブラリを直接呼べないiOS(Swift bridging header)との違いを吸収するため、FIFO管理・libiio操作・送受信ループをすべてC++側に集約し、Kotlinには薄いstart/stop/write/診断値取得のみを公開
- `dvbs2/`: `Dvbs2TxPipeline`/`Dvbs2RxPipeline`(JNIラッパー)、`Dvbs2TestRunner`(TX→RXの順次自己診断、iOS版`DVBS2TestRunner.runDiag`相当)、`Dvbs2Native`(assetsの`conf/`展開・作業ディレクトリ管理)

### UI

`ui/`にHome/Tx/Rx/Frequency/SymbolRate/FEC/Modulation/VideoSource/StreamOutput/RxGain/TxPower/Settings/Help画面を実装済み。電話版はHome画面上部のタブでこれらを切り替える単一画面構成で、タブレット版にあるRSSI測定(旧称: 相手局検索/AFC)・機器試験の画面は搭載していない(RSSI測定用のJNIセッション`RssiNativeSession`と機器試験の`Dvbs2TestRunner`はコードとして残っているがUIからは使わない)。設定は`SettingsStore`でSharedPreferencesへ永続化。

## 実機で踏んだ主な不具合と対策

- **送信停止時の「UDP送出に失敗しました」**: `TxController.stop()`がCompose UIのクリックハンドラ(メインスレッド)から呼ばれ、`TsPacketPacker.flush()`が同期的に`UdpSender.send()`(ブロッキングI/O)を呼ぶため`NetworkOnMainThreadException`。`UdpSender`に専用送信スレッドを持たせて解決(iOS版`UDPSender.swift`にも同種の問題があり同じ考え方で修正済み)
- **libiioのNULLポインタクラッシュ**: `iiod_client_refresh_format`が`iiod_client_uses_binary_interface`チェックなしに`client->responder`を参照。iOS版が過去に踏んだのと同じ既知バグ
- **cpptraceのAndroid非対応**: 例外発生時にビルドマシンの`addr2line`パスを実行しようとしてクラッシュ。`SPU_STACKTRACE=OFF`でスタックトレース機能自体を無効化
- **Radio_user_binaryのFIFO非対応**: dvbs2_rx側がEOF時に`seekg(0)`を試みるが、FIFO(named pipe)ではシーク不可能なため失敗し例外化。意図した停止(FIFOのwriter側close)として静かに終了するようパッチ
- **オンデバイス復調で`locked=1`なのに映像が出ない**: `PlutoSettingsWriter`がPlutoへ`pilots=Off`を固定送信していたため。gr-dvbs2rxの`plsync_cc`はパイロットなし信号だと周波数/位相の再推定がフレーム先頭(SOF)のみに落ち、Normal Frame(64800シンボル)につき1回まで更新頻度が低下し、PlutoSDR(AD9361)のTCXOドリフトに追従できずフレーム内でロックを失う(PL同期自体は`locked=1`のまま)。`pilots=On`をデフォルト送信するよう修正

## 既知の未対応事項

- 本番Txは常にPluto実機側のオンボード変調器(F5OEO plutosdr-fw相当)に依存するため、そのモデムソフトが無い/起動していない環境では送信できない
- 本番Rxを「外部復調機器方式」で使う場合も同様に、外部復調機器(またはPluto側の対応モデムソフト)が無い環境ではUDP-TSが届かず受信できない。ただし「オンデバイス復調方式」を有効にすれば、PlutoのRFフロントエンド機能のみに依存してAndroid端末側で復調するため、この構成でも受信は可能(機器試験と同じ理由)

## Reference Sources

- `ios/`: The full iOS source this was ported from (built `.a` static libraries are excluded; see `ios/Shonan/DVBS2Vendor` and `ios/Shonan/LibiioVendor`, and `ios/Scripts/build_ffmpeg_xcframework.sh`, for rebuild steps)
- `DVBS2_iOS_Port_PoC.md`: Record of the aff3ct/dvbs2 iOS cross-build PoC. Source of the CMake toolchain choices and pitfalls (e.g. the libiio NULL-pointer crash) hit during the Android NDK port
- `DATV_iPad_spec.md`: Original design spec

## Architecture

The production Tx/Rx path (UDP-TS; Tx always uses Pluto's onboard modulator, Rx chooses between an external
demodulator or on-device demodulation) and the device-test path (direct Pluto IQ Tx/Rx via aff3ct/libiio) are
fully independent.

### Production Tx

- `net/PlutoSettingsWriter.kt`: Writes modulation parameters (frequency, symbol rate, FEC, pilots, etc.) to Pluto's web UI (`settings.txt`/`save.php`) over HTTP, so Pluto's own onboard hardware modulator performs the DVB-S2 modulation. The Android side never modulates anything itself
- `third_party/build_ffmpeg_mpegts.sh`: Cross-builds FFmpeg for NDK arm64-v8a in an mpegts-only configuration (libavformat/libavcodec/libavutil, no encoders/decoders). The output (`ffmpeg-mpegts-install-arm64-v8a/`) is tracked in git
- `app/src/main/cpp/ts_bridge.cpp`: JNI bridge for the above (TS mux/demux)
- `tx/`: `CameraCapture` or `ColorBarSource` (test pattern) → `H264Encoder` (MediaCodec) → `TsMuxerNative` → `TsPacketPacker` → `UdpSender` → `TxController`. The resulting MPEG-TS is sent to Pluto over UDP, and Pluto's onboard modulator performs the DVB-S2 modulation and RF transmission

### Production Rx

`AppSettings.useOnDeviceGRDVBS2Rx` (the "On-device demodulation (GNU Radio)" toggle on the Settings screen) switches
between the two modes below. Either path's TS output goes through the shared `TsPacketAligner` (described below)
before reaching `TsDemuxerNative`/`H264DisplaySurface`.

- **External demodulator (default)**: `UdpReceiver` directly receives MPEG-TS over UDP from an external demodulator
- **On-device demodulation**: `dvbs2rx/Dvbs2rxPipeline.kt` + `app/src/main/cpp/dvbs2rx_bridge.cpp` pull Pluto's RF IQ via gr-iio's `fmcomms2_source`, and demodulate DVB-S2 on the Android device itself using GNU Radio plus gr-dvbs2rx (by igorauad, a GNU Radio OOT module). This replaced an earlier aff3ct-based attempt (`dvbs2_bridge.cpp`, still used for Device Test) after real-hardware testing found it couldn't even demodulate gr-dvbs2rx's own standard DVB-S2 test signals, so the switch was made to the proven independent implementation. Headers live under `third_party/gr-dvbs2rx-install-arm64-v8a/`; the actual `.so` files, already cross-built for the NDK, are placed under `app/src/main/jniLibs/arm64-v8a/` (there is no build script for this dependency yet — the `.so` files themselves are tracked in git)

#### Receive TS Resynchronization

The received TS is continuously validated against 188-byte TS packet boundaries by `TsPacketAligner`. This is a port of the resynchronization approach from `rpi-dvbs2-receiver-gui/udp_relay.py`: even when a FIFO/read boundary shift or a single mid-stream byte drop occurs, it searches the next 3 packets for the sync byte (`0x47`) to recover, then hands data to the TS demuxer in 7-packet (1316-byte) units.

### Device Test (direct Pluto IQ Tx/Rx, aff3ct/libiio)

- `third_party/build_dvbs2_deps.sh`: Cross-builds zstd/libxml2/libiio/aff3ct-dvbs2 for NDK arm64-v8a. The output (`dvbs2-deps-install-arm64-v8a/`) is tracked in git (the largest file is 93MB after stripping). Bundles and auto-applies patches for crashes hit on real hardware:
  - `libiio_refresh_format_null_guard.patch`: fixes the libiio NULL-pointer crash (see below)
  - `radio_user_binary_fifo_eof.patch`: fixes `Radio_user_binary`'s lack of FIFO support (see below)
  - `radio_user_binary_write2.patch`: replaces `Radio_user_binary`'s output from `std::ofstream` with POSIX `open`/`write`/`close`, avoiding problems C++ iostreams have writing to a FIFO
  - `aff3ct_bch_decoder_overalloc.patch`: upstream aff3ct's BCH decoder allocated its work arrays at `(N_p2_1+2)×N_p2_1` size (about 1.07GB for DVB-S2 QPSK 3/5), causing memory runaway and a SIGKILL. Shrunk to the range actually used (`t2+4`, where t2 is twice the correctable-error count) to fix it
- `app/src/main/cpp/dvbs2_bridge.cpp` + `dvbs2_tx_lib.cpp`/`dvbs2_rx_lib.cpp`: JNI bridge for the above. To absorb the difference from iOS (where Kotlin, unlike Swift with a bridging header, cannot call a C library directly), all FIFO management, libiio operations, and the Tx/Rx loop are consolidated on the C++ side, exposing only a thin start/stop/write/diagnostics API to Kotlin
- `dvbs2/`: `Dvbs2TxPipeline`/`Dvbs2RxPipeline` (JNI wrappers), `Dvbs2TestRunner` (sequential Tx→Rx self-diagnostic, equivalent to the iOS version's `DVBS2TestRunner.runDiag`), `Dvbs2Native` (extracts `conf/` from assets, manages the working directory)

### UI

`ui/` implements the Home/Tx/Rx/Frequency/SymbolRate/FEC/Modulation/VideoSource/StreamOutput/RxGain/TxPower/Settings/Help screens. The phone version is a single screen that switches between them with tabs at the top of Home; it does not include the tablet version's RSSI Measurement (formerly Find Station/AFC) or Diagnostic screens (the RSSI JNI session `RssiNativeSession` and the diagnostic `Dvbs2TestRunner` remain in the code but are not used from the UI). Settings are persisted to SharedPreferences via `SettingsStore`.

## Major Issues Hit on Real Hardware, and Their Fixes

- **"Failed to send UDP" when stopping transmission**: `TxController.stop()` was called from a Compose UI click handler (main thread), and `TsPacketPacker.flush()` synchronously called `UdpSender.send()` (blocking I/O), causing a `NetworkOnMainThreadException`. Fixed by giving `UdpSender` a dedicated send thread (the iOS version's `UDPSender.swift` had the same class of problem and was fixed the same way)
- **libiio NULL-pointer crash**: `iiod_client_refresh_format` dereferenced `client->responder` without checking `iiod_client_uses_binary_interface` first. The same known bug the iOS version had previously hit
- **cpptrace doesn't support Android**: On an exception, it tried to run the build machine's `addr2line` path, causing a crash. Disabled the stack-trace feature entirely with `SPU_STACKTRACE=OFF`
- **Radio_user_binary doesn't support FIFOs**: On EOF, the dvbs2_rx side tried `seekg(0)`, which fails and throws on a FIFO (named pipe) since it isn't seekable. Patched to exit quietly, treating it as an intentional stop (the FIFO's writer side closing)
- **On-device demodulation shows `locked=1` but no video appears**: Caused by `PlutoSettingsWriter` always sending `pilots=Off` to Pluto. Without pilots, gr-dvbs2rx's `plsync_cc` can only re-estimate frequency/phase at the frame's start-of-frame (SOF), dropping its update rate to once per Normal Frame (64,800 symbols) — too infrequent to track a PlutoSDR's (AD9361) TCXO drift, so it loses lock within the frame even though PL sync itself stays reported as `locked=1`. Fixed by defaulting to `pilots=On`

## Known Gaps

- Production Tx always depends on Pluto's own onboard modulator (equivalent to F5OEO's plutosdr-fw); transmission is not possible in an environment where that modem software is missing or not running
- Likewise, when Production Rx is used in "external demodulator" mode, reception fails wherever there is no external demodulator (or no corresponding modem software on Pluto) to deliver UDP-TS. Enabling "on-device demodulation" mode instead depends only on Pluto's RF front-end functionality and demodulates on the Android device itself, so reception is still possible in that configuration (the same reason Device Test still works)

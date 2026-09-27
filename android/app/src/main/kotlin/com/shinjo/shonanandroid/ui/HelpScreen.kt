package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel

private val SectionTitle = Color(0xFF0D6E8C)
private val StepAccent = Color(0xFF1677FF)
private val BodyText = Color.Black
private val DividerColor = Color(0xFFCCCCCC)

private data class HelpSection(val titleJA: String, val titleEN: String, val bodyJA: String, val bodyEN: String)
private data class HelpStep(val ja: String, val en: String)

// ★送信時に設定すべき項目の推奨順序(タブの並び順とは異なり、実際に必要な操作順)。
private val txSteps = listOf(
    HelpStep(
        "「映像ソース」タブで送信する映像(背面カメラ・前面カメラ・写真・カラーバー)を選ぶ",
        "On the Video Source tab, choose what to transmit (back camera, front camera, a photo, or a color bar).",
    ),
    HelpStep(
        "「周波数」タブでバンドと周波数を設定する(相手の受信周波数と一致させる)",
        "On the Frequency tab, set the band and frequency (must match the receiver's frequency).",
    ),
    HelpStep(
        "「設定３」タブでシンボルレート・誤り訂正(1/2・3/5・8/9)・変調方式(QPSK・8PSK)を設定する(相手と一致させる)",
        "On the Config 3 tab, set the symbol rate, FEC (1/2, 3/5, or 8/9), and modulation (QPSK or 8PSK); they must match the receiver.",
    ),
    HelpStep(
        "「配信先」タブでPluto TxのIPアドレスを確認する(初回のみ。実機宛て送信時は送信先ポートの"
            + "指定に関わらずPluto側のUDP-TS受信ポート(8282)へ固定で送られます)",
        "On the Destination tab, confirm Pluto Tx's IP address (first time only). When targeting real "
            + "Pluto hardware, data always goes to its fixed UDP-TS port (8282) regardless of the port field.",
    ),
    HelpStep(
        "「設定２」タブで出力減衰量を設定する(0dBが最大出力、負の値ほど出力が下がる)",
        "On the Config 2 tab, set the attenuation (0 dB is maximum output; more negative values reduce output).",
    ),
    HelpStep(
        "「送信」タブでプレビューを確認し「送信開始」を押す",
        "On the Transmit tab, check the preview and tap Start.",
    ),
)

// ★受信時に設定すべき項目の推奨順序。オンデバイス復調(設定タブ)の有無で受信経路が変わる点に注意。
private val rxSteps = listOf(
    HelpStep(
        "「周波数」「設定３」の各タブを、送信側と同じ値に設定する",
        "Set the Frequency and Config 3 tabs to match the transmitting side.",
    ),
    HelpStep(
        "「設定」タブで受信方式を選ぶ: オフ(既定)のままなら外部復調機器からのUDP-TSを「配信先」の"
            + "TSポート/ステータスポートで待ち受ける。オンにするとPlutoのRF信号をAndroid自身が復調する"
            + "(送信と同時に行う場合はPluto保護のため必ずアッテネータを接続すること)",
        "On the Settings tab, choose the receive path: leave On-device demodulation off (default) to "
            + "listen for UDP-TS from an external demodulator on the TS/Status ports set under Destination; "
            + "turn it on to have Android demodulate Pluto's RF directly (an attenuator is required if "
            + "transmitting at the same time, to protect the Pluto).",
    ),
    HelpStep(
        "「設定２」タブでAGC(自動)のままにするか、オフにして手動でゲインを設定する",
        "On the Config 2 tab, keep AGC on, or turn it off and set the gain manually.",
    ),
    HelpStep(
        "「受信」タブで「受信開始」を押す。ロックすると自動で映像が全画面表示になる",
        "On the Receive tab, tap Start. Once locked, the video switches to fullscreen automatically.",
    ),
)

// ★タブの並び順(ホーム画面のタブ順)に沿った各画面の説明。
private val helpSections = listOf(
    HelpSection(
        "SSB/FM", "SSB/FM",
        "Plutoを使ってUSB・FMで送受信します(DATVの送受信とは同時に使えません)。「開始」で受信を始め、"
            + "PTTを押している間だけ送信します(「PTTを押すたびに送受信を切り替える」も選べます)。"
            + "周波数表示の桁をタップすると同調ステップ、長押しで直接入力、右の1.2G・2.4G・5.6Gでバンドを"
            + "切り替えます(バンドごとに最後の周波数とモードを覚えます)。スペクトル/ウォーターフォールを"
            + "左右にドラッグすると同調、タップするとその周波数へ移動します。スケルチはFMのみ有効です。"
            + "「設定」タブで「PA/PTTコントローラを使う」をONにしてESP32+W5500のIPアドレスを入れておくと、PTTに連動して"
            + "送信開始/終了を通知します(応答がない場合は送信しません)。",
        "Transmit and receive USB and FM with Pluto (not at the same time as DATV). Tap Start to begin "
            + "receiving; the radio transmits while PTT is held (or toggles with latching PTT). Tap a digit "
            + "of the frequency to choose the tuning step, long-press to type a frequency, and use the "
            + "1.2G/2.4G/5.6G buttons to change band (each band remembers its last frequency and mode). "
            + "Drag the spectrum/waterfall to tune, or tap it to jump to that frequency. Squelch works in FM "
            + "only. If \"Use the PA/PTT controller\" is on (Settings tab) with the ESP32+W5500 IP address, PTT "
            + "notifies it of TX start/stop (the app does not transmit if it does not respond).",
    ),
    HelpSection(
        "送信", "Transmit",
        "「映像ソース」タブで選んだ映像のプレビューを表示します。「送信開始」を押すと、現在の"
            + "周波数・シンボルレート・変調方式・誤り訂正・出力減衰量の設定でPlutoから送信します"
            + "(映像ソースの選択自体はこの画面では行いません)。オンデバイス復調が有効な場合は"
            + "「受信画面へ」ボタンで受信タブに移動できます。",
        "Shows a preview of the source selected on the Video Source tab. Tap Start to transmit via "
            + "Pluto using the current frequency, symbol rate, modulation, FEC, and attenuation settings "
            + "(the source itself is not selected here). When on-device demodulation is enabled, a "
            + "\"Receive Screen\" button lets you jump to the Receive tab.",
    ),
    HelpSection(
        "受信", "Receive",
        "「受信開始」を押すとPlutoからの信号(またはUDP-TS)の受信を開始します。ロックすると"
            + "自動的に映像が全画面表示になり(上部のタブも隠れます)、画面をタップすると5秒間だけ"
            + "状態カードとボタンが表示されます(ロックが続いていれば5秒後に自動で全画面表示に戻ります)。"
            + "ロックが1.5秒以上外れると全画面表示を解除します。",
        "Tap Start to begin receiving from Pluto (or UDP-TS). Once locked, video switches to "
            + "fullscreen automatically (the tabs at the top are hidden too); tap the screen to reveal "
            + "the status card and buttons for 5 seconds (it returns to fullscreen automatically if "
            + "still locked). Fullscreen ends if the lock is lost for 1.5 seconds or more.",
    ),
    HelpSection(
        "周波数", "Frequency",
        "左のバンド一覧からボタンで選ぶか、右のテンキーで周波数(kHz)を直接入力します。"
            + "送信と受信は同じ設定を共有します。",
        "Choose a band from the list on the left, or type the frequency in kHz directly on the keypad "
            + "on the right. Transmit and Receive share the same setting.",
    ),
    HelpSection(
        "設定２", "Config 2",
        "DATVの受信感度と送信出力を設定します。受信感度は「自動(AGC)」をONにするとAGCがゲインを"
            + "自動調整し、OFFにすると0〜73dBの範囲で手動調整できます。送信出力は出力減衰量を-70〜0dBの"
            + "範囲で設定します(0dBが最大出力で、Plutoの送信出力減衰値としてそのまま適用されます)。",
        "Set the DATV receive gain and transmit power. For receive gain, turn on \"Auto (AGC)\" to let "
            + "AGC adjust the gain, or turn it off to set 0 to 73 dB manually. For transmit power, set the "
            + "attenuation from -70 to 0 dB (0 dB is maximum output, applied directly as Pluto's TX attenuation).",
    ),
    HelpSection(
        "設定３", "Config 3",
        "シンボルレート・誤り訂正(FEC)・変調方式を1画面で設定します。シンボルレートはプリセット"
            + "(250k〜2Msym/s)から選ぶか「直接入力」で100〜5000 kS/sを入力します。誤り訂正は1/2・3/5・8/9、"
            + "変調方式はQPSK・8PSKから選び、コンステレーション(信号点配置)が表示されます。下段に組み合わせと"
            + "帯域幅・ビットレートの目安を表示します。送信と受信は同じ設定を共有します。",
        "Set the symbol rate, FEC, and modulation on one screen. Choose a symbol-rate preset "
            + "(250k to 2 Msym/s) or tap Custom to enter 100–5000 kS/s. Choose FEC 1/2, 3/5, or 8/9 and "
            + "QPSK or 8PSK; the constellation is shown. The bottom line shows the combination and the "
            + "estimated bandwidth and bit rate. Transmit and Receive share the same setting.",
    ),
    HelpSection(
        "映像ソース", "Video Source",
        "背面カメラ(既定)・前面カメラ・写真(写真フォルダーから選択)・テストパターン(カラーバー)の"
            + "いずれかを選びます。ここで選んだ映像が「送信」タブでのプレビューと送信対象になります。"
            + "「写真」を選ぶと、写真に焼き込む「コールサイン」「備考」を入力できます。"
            + "「マイク音声を送信」をONにすると、端末のマイク音声も一緒に送信します。"
            + "送信映像はHD(1280x720)・30fpsです。",
        "Choose the rear camera (default), the front camera, a photo (picked from your photo folder) or "
            + "the test pattern (color bars). The selection here becomes the preview and the source "
            + "transmitted on the Transmit tab. When \"Photo\" is chosen, you can enter a \"Callsign\" "
            + "and \"Note\" that are burned into the photo. Turn on \"Transmit Mic Audio\" to send the "
            + "device's microphone audio as well. The transmitted video is HD (1280x720) at 30 fps.",
    ),
    HelpSection(
        "配信先", "Destination",
        "「送信先(Pluto Tx)」でPlutoのIPアドレスを設定します。ポート欄は確認用の表示で、"
            + "送信は常にPluto側の固定ポート8282へ行います。「自動検出」を押すと、まず端末が接続中の"
            + "ネットワーク(同じサブネット)からPlutoを探し、見つからなければESP32ブリッジに問い合わせます。"
            + "見つかればIPアドレス欄へ自動反映します(検索には最大で数十秒かかります)。"
            + "アプリの起動時にも一度だけ自動検出を行います。"
            + "「受信設定」のTSポート/ステータスポートは、オンデバイス復調がオフの時に外部復調機器からの"
            + "UDP-TSを待ち受けるポートです。",
        "Under TX Destination, set Pluto's IP address. The port field is for confirmation only; "
            + "data always goes to Pluto's fixed port 8282. Tap Auto-Detect to look for Pluto first on "
            + "the network the device is connected to (same subnet) and, if it is not found there, by "
            + "asking the ESP32 bridge; the IP address field is filled in automatically if found (the "
            + "search can take up to several tens of seconds). Auto-detection also runs once when the "
            + "app starts. The TS/Status ports under Receive Settings are used to listen for "
            + "UDP-TS from an external demodulator when on-device demodulation is off.",
    ),
    HelpSection(
        "設定", "Settings",
        "表示言語(ホーム画面を除く全画面)と、オンデバイス復調(GNU Radio)の有効/無効を設定します。"
            + "オンデバイス復調をONにすると、Pluto1台でRFのループバック試験ができます"
            + "(画像を送信しながら同時にその画像を受信します)。外部アッテネータなしで行うと"
            + "Plutoを破損する恐れがあるため、有効化時に必ず警告が表示されます。",
        "Set the display language (all screens except Home) and whether on-device demodulation "
            + "(GNU Radio) is enabled. Enabling it lets you run an RF loopback test with a single "
            + "Pluto: you transmit an image while simultaneously receiving that same image. Doing so "
            + "without an external attenuator can damage the Pluto, so a warning is always shown "
            + "before enabling it.",
    ),
    HelpSection(
        "アプリ再起動・終了", "App Restart and Quit",
        "画面上部の「アプリ再起動」はPlutoの再起動要求を送り、Web UI/iiodの復旧を待ちます"
            + "(アプリの起動時にも同じ処理を行います)。通信がおかしい時にお試しください。"
            + "「終了」を押すと確認のあとアプリを終了します。送信・受信中の場合は先に停止してください。",
        "\"App Restart\" at the top sends a reboot request to Pluto and waits for its Web UI/iiod "
            + "to come back online (the same happens when the app starts). Try this if communication "
            + "seems stuck. \"Quit\" closes the app after a confirmation; stop TX/RX first if they are running.",
    ),
    HelpSection(
        "クレジット", "Credits",
        "SSB/FM送受信機能は、Colin Durbridge氏(G4EML)のLangstone-V2(https://github.com/g4eml/Langstone-V2、"
            + "GPLv3)のAdalm Pluto版の信号処理とPluto制御を元にC++へ移植したものです。Plutoを528kspsで"
            + "動かすためのFIR係数と設定手順は、Analog DevicesのlibAD9361-iio(LGPL-2.1)に由来します。"
            + "Langstone-V2はGNU General Public License v3(GPLv3)で公開されているソフトウェアのため、"
            + "それを使用した本アプリもGPLv3に従って配布します。",
        "The SSB/FM transceiver is a C++ port of the signal processing and Pluto control of the "
            + "Adalm Pluto version of Langstone-V2 by Colin Durbridge, G4EML "
            + "(https://github.com/g4eml/Langstone-V2, GPLv3). The FIR coefficients and procedure used to "
            + "run Pluto at 528 ksps come from Analog Devices' libAD9361-iio (LGPL-2.1). Because "
            + "Langstone-V2 is licensed under the GNU General Public License v3 (GPLv3), this app, which "
            + "uses it, is also distributed under GPLv3.",
    ),
)

@Composable
private fun HelpStepList(steps: List<HelpStep>, settings: com.shinjo.shonanandroid.core.AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${index + 1}.",
                    color = StepAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp),
                )
                Text(
                    settings.t(step.ja, step.en),
                    color = BodyText,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** ヘルプ画面 -- 全タブの使い方と、送受信それぞれの推奨設定順序を説明する(電話版で新規追加)。 */
@Composable
fun HelpScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings

    SettingsSubScreen(
        title = settings.t("ヘルプ", "Help"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        // ★ヘルプは他画面よりも縦に長い文章主体のため、既定の余白(top16dp/bottom32dp)を
        // 詰めてスクロール可能な表示エリアを広く取る。
        contentPadding = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    settings.t("クレジット", "Credits"),
                    color = SectionTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    settings.t(
                        "受信部の方式考案・受信部原システム設計: 山崎慎慈氏(JE1BTA) "
                            + "rpi-dvbs2-receiver-guiの設計に基づきます",
                        "Receiver method and original receiver system design: Shinji Yamazaki (JE1BTA), "
                            + "based on rpi-dvbs2-receiver-gui.",
                    ),
                    color = BodyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    settings.t(
                        "受信部安定化調査修正・再捕捉修正・本アプリ開発: 真城和一(JA6FUF/JH1XHX)",
                        "Receiver stabilization, reacquisition fixes, and application development: "
                            + "Kazuichi Shinjo (JA6FUF/JH1XHX).",
                    ),
                    color = BodyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    settings.t(
                        "本アプリは、Dave Crump氏(G8GKQ)が開発したDATV送受信機プロジェクト「Portsdown」に"
                            + "啓発され、開発したものです。同氏の先駆的な取り組みに感謝いたします。",
                        "This application was developed inspired by \"Portsdown\", the DATV transceiver "
                            + "project created by Dave Crump (G8GKQ). We extend our deep gratitude for his "
                            + "pioneering work.",
                    ),
                    color = BodyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    settings.t(
                        "本プログラムを使用して生じたいかなる損害についても、開発者は一切の責任を負いません。"
                            + "ご自身の責任においてご利用ください。",
                        "The developers accept no liability whatsoever for any damage arising from the use "
                            + "of this program. Use it at your own risk.",
                    ),
                    color = BodyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor)) {}

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    settings.t("送信の設定順序", "Setup Order for Transmit"),
                    color = SectionTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                HelpStepList(txSteps, settings)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    settings.t("受信の設定順序", "Setup Order for Receive"),
                    color = SectionTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                HelpStepList(rxSteps, settings)
            }

            Column(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor)) {}

            Text(
                settings.t("各タブの説明", "Tab Reference"),
                color = SectionTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )

            helpSections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        settings.t(section.titleJA, section.titleEN),
                        color = SectionTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        settings.t(section.bodyJA, section.bodyEN),
                        color = BodyText,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

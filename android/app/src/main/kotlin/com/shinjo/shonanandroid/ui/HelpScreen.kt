package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.R

private val SectionTitle = Color(0xFF0D6E8C)
private val StepAccent = Color(0xFF1677FF)
private val BodyText = Color.Black
private val DividerColor = Color(0xFFCCCCCC)

private data class HelpSection(val titleJA: String, val titleEN: String, val bodyJA: String, val bodyEN: String)
private data class HelpStep(val ja: String, val en: String)

/** 各タブの説明に添える実機の画面(docs/images/と同じ画像。日本語表示で撮影)。キーは節の日本語見出し。 */
private val sectionImages = mapOf(
    "SSB/FM" to listOf(R.drawable.help_ssbfm),
    "送信" to listOf(R.drawable.help_tx),
    "受信" to listOf(R.drawable.help_rx),
    "RSSI測定" to listOf(R.drawable.help_rssi),
    "周波数" to listOf(R.drawable.help_frequency),
    "設定１" to listOf(R.drawable.help_config1_top, R.drawable.help_config1_bottom),
    "設定２" to listOf(R.drawable.help_config2),
    "設定３" to listOf(R.drawable.help_config3),
    "設定４(映像ソース)" to listOf(R.drawable.help_config4),
)

/** 画面画像の縦横比(撮影画像1086x492)。 */
private const val HELP_IMAGE_ASPECT = 1086f / 492f

// ★SSB/FMで送受信するときの操作順序。
private val ssbFmSteps = listOf(
    HelpStep(
        "初回のみ「設定４」タブの「配信先」でPlutoのIPアドレスを確認する(SSB/FMもDATVと同じPlutoを使う)",
        "First time only: check Pluto's IP address on the Config 4 tab (Stream Output); SSB/FM uses the same Pluto as DATV.",
    ),
    HelpStep(
        "PA/PTTコントローラ(ESP32+W5500)を使う場合は「設定１」タブで「PA/PTTコントローラを使う」をONにし、IPアドレスを入れる"
            + "(既定はON・192.168.0.100。使わない場合はOFFにする)",
        "If you use the PA/PTT controller (ESP32+W5500), turn on \"Use the PA/PTT controller\" on the Config 1 tab and "
            + "enter its IP address (default: on, 192.168.0.100). Turn it off if you do not use one.",
    ),
    HelpStep(
        "「SSB/FM」タブでバンド(1.2G・2.4G・5.6G)とモード(USB・FM)を選び、周波数を合わせる",
        "On the SSB/FM tab, choose the band (1.2G/2.4G/5.6G) and mode (USB/FM), then tune the frequency.",
    ),
    HelpStep(
        "「開始」を押して受信を始める(DATVの送受信・RSSI測定は自動で止まる)",
        "Tap Start to begin receiving (DATV TX/RX and RSSI measurement are stopped automatically).",
    ),
    HelpStep(
        "PTTを押すと送信、もう一度押すと受信に戻る(トグル動作)。終わったら「停止」を押す",
        "Tap PTT to transmit and tap it again to return to receive (toggle). Tap Stop when you are finished.",
    ),
)

// ★送信時に設定すべき項目の推奨順序(タブの並び順とは異なり、実際に必要な操作順)。
private val txSteps = listOf(
    HelpStep(
        "「設定４」タブの「映像ソース」で送信する映像(背面カメラ・前面カメラ・写真・テストパターン)を選ぶ",
        "On the Config 4 tab (Video Source), choose what to transmit (rear camera, front camera, a photo, or the test pattern).",
    ),
    HelpStep(
        "「周波数」タブでバンドと周波数を設定する(相手の受信周波数と一致させる)",
        "On the Frequency tab, set the band and frequency (it must match the receiving station's frequency).",
    ),
    HelpStep(
        "「設定３」タブでシンボルレート・誤り訂正(1/2・3/5・8/9)・変調方式(QPSK・8PSK)を設定する(相手と一致させる)",
        "On the Config 3 tab, set the symbol rate, FEC (1/2, 3/5, or 8/9), and modulation (QPSK or 8PSK); they must match the receiving station.",
    ),
    HelpStep(
        "「設定４」タブの「配信先」でPluto TxのIPアドレスを確認する(初回のみ。送信はPlutoのUDP-TS受信ポート8282へ固定で送られます)",
        "On the Config 4 tab (Stream Output), check Pluto Tx's IP address (first time only). The stream always goes to Pluto's fixed UDP-TS port 8282.",
    ),
    HelpStep(
        "「設定２」タブで出力減衰量を設定する(0dBが最大出力、負の値ほど出力が下がる)",
        "On the Config 2 tab, set the attenuation (0 dB is maximum output; more negative values reduce the output).",
    ),
    HelpStep(
        "「送信」タブでプレビューを確認し「送信開始」を押す",
        "On the Transmit tab, check the preview and tap Start.",
    ),
)

// ★受信時に設定すべき項目の推奨順序。オンデバイス復調(設定１タブ)の有無で受信経路が変わる点に注意。
private val rxSteps = listOf(
    HelpStep(
        "「周波数」「設定３」の各タブを、送信側と同じ値に設定する",
        "Set the Frequency and Config 3 tabs to the same values as the transmitting station.",
    ),
    HelpStep(
        "「設定１」タブで受信方式を選ぶ: オフ(既定)のままなら外部復調機器からのUDP-TSを「設定４」タブ「配信先」の"
            + "TSポート/ステータスポートで待ち受ける。オンにするとPlutoのRF信号をAndroid自身が復調する"
            + "(送信と同時に行う場合はPluto保護のため必ずアッテネータを接続すること)",
        "On the Config 1 tab, choose the receive path: leave on-device demodulation off (default) to listen for UDP-TS "
            + "from an external demodulator on the TS/Status ports set on the Config 4 tab (Stream Output), or turn it on "
            + "to have Android demodulate Pluto's RF signal itself (always connect an attenuator if you transmit at the "
            + "same time, to protect the Pluto).",
    ),
    HelpStep(
        "「設定２」タブでAGC(自動)のままにするか、オフにして手動でゲインを設定する",
        "On the Config 2 tab, keep AGC (auto) on, or turn it off and set the gain manually.",
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
        "PlutoでUSB・FMの送受信を行います(Langstone-V2の移植)。DATVの送受信・RSSI測定とは同時に使えず、"
            + "「開始」を押すとそれらを止めてから受信を始めます。開始時にPlutoのDATV送信処理(pluto_dvb)をSSHで止め、"
            + "「停止」でPlutoの設定(サンプルレート・周波数・ゲイン)を開始前の状態へ戻します。\n"
            + "・周波数表示: MHz単位の7桁(例 1295.100、最下位は1kHz)。桁をタップするとその桁が同調ステップになり、"
            + "「−」「+」で1ステップずつ動きます。長押しで周波数を直接入力できます。\n"
            + "・バンド: 周波数表示の右の1.2G・2.4G・5.6Gで切り替えます。バンドごとに最後の周波数とモードを覚えます。\n"
            + "・モード: USB・FM。\n"
            + "・スペクトル/ウォーターフォール(幅48kHz): 左右にドラッグすると同調、タップするとその周波数へ移動します。\n"
            + "・Sメーター: 受信中は信号の強さ(相対値)、送信中はマイクレベルを表示します。\n"
            + "・音量・スケルチ(FMのみ有効。USBではグレー表示)・マイク・RF利得(AGCのチェックで自動)・送信減衰(0〜89dB)。\n"
            + "・PTT: トグル動作です。押すと送信、もう一度押すと受信に戻ります(送信中はボタンが赤く「送信中」と表示)。\n"
            + "・「設定１」タブで「PA/PTTコントローラを使う」がONのときは、送信開始時にESP32+W5500へ通知して150ms待ってから"
            + "電波を出し、送信終了時は電波を止めてから通知します。ESP32が応答しない場合は、警告を表示してPA/PTTを切り替えないまま送信します。",
        "Transmits and receives USB and FM with Pluto (a port of Langstone-V2). It cannot run at the same time as "
            + "DATV TX/RX or RSSI measurement; tapping Start stops them first and then starts receiving. On start, the "
            + "app stops Pluto's DATV transmitter process (pluto_dvb) over SSH, and Stop restores Pluto's settings "
            + "(sample rate, frequencies, gains) to what they were before.\n"
            + "- Frequency display: 7 digits in MHz (e.g. 1295.100; the last digit is 1 kHz). Tap a digit to make it the "
            + "tuning step, and use − / + to move one step. Long-press to type a frequency.\n"
            + "- Band: switch with the 1.2G/2.4G/5.6G buttons to the right of the frequency. Each band remembers its last "
            + "frequency and mode.\n"
            + "- Mode: USB or FM.\n"
            + "- Spectrum/waterfall (48 kHz wide): drag left/right to tune, or tap to jump to that frequency.\n"
            + "- S-meter: shows the signal strength (relative) while receiving and the mic level while transmitting.\n"
            + "- Volume, squelch (FM only; grayed out in USB), mic gain, RF gain (check AGC for automatic), and TX "
            + "attenuation (0 to 89 dB).\n"
            + "- PTT: toggle operation. Tap to transmit and tap again to return to receive (the button turns red and shows \"TX\" while transmitting).\n"
            + "- When \"Use the PA/PTT controller\" is on (Config 1 tab), the app notifies the ESP32+W5500 at TX start, "
            + "waits 150 ms, then transmits; at TX end it stops transmitting first and then notifies. If the ESP32 "
            + "does not respond, the app shows a warning and transmits without switching the PA/PTT.",
    ),
    HelpSection(
        "送信", "Transmit",
        "「設定４」タブの「映像ソース」で選んだ映像のプレビューを表示します。「送信開始」を押すと、現在の"
            + "周波数・シンボルレート・変調方式・誤り訂正・出力減衰量の設定でPlutoから送信します"
            + "(映像ソースの選択自体はこの画面では行いません)。送信映像はHD(1280x720)・30fpsです。"
            + "オンデバイス復調が有効な場合は「受信画面へ」ボタンで受信タブに移動できます。",
        "Shows a preview of the source selected on the Config 4 tab (Video Source). Tap Start to transmit via "
            + "Pluto with the current frequency, symbol rate, modulation, FEC, and attenuation settings "
            + "(the source itself is not selected here). The transmitted video is HD (1280x720) at 30 fps. "
            + "When on-device demodulation is enabled, the \"Receive Screen\" button opens the Receive tab.",
    ),
    HelpSection(
        "受信", "Receive",
        "「受信開始」を押すとPlutoからの信号(またはUDP-TS)の受信を開始します。ロックすると"
            + "自動的に映像が全画面表示になり(上部のタブも隠れます)、画面をタップすると5秒間だけ"
            + "状態カードとボタンが表示されます(ロックが続いていれば5秒後に自動で全画面表示に戻ります)。"
            + "ロックが1.5秒以上外れると全画面表示を解除します。",
        "Tap Start to begin receiving from Pluto (or UDP-TS). Once locked, the video switches to fullscreen "
            + "automatically (the tabs at the top are hidden too); tap the screen to show the status card and "
            + "buttons for 5 seconds (it returns to fullscreen automatically if still locked). Fullscreen ends if "
            + "the lock is lost for 1.5 seconds or more.",
    ),
    HelpSection(
        "RSSI測定", "RSSI Measurement",
        "「周波数」タブの運用周波数を中心に、±5/10/20MHzの範囲をステップ(kHz)ごとに走査してPlutoのRSSIを測り、"
            + "グラフと「最も強い周波数」を表示します(RSSIは値が小さいほど強い信号です)。検索方法は「連続」"
            + "(「測定停止」まで繰り返す)と「1回」から選べ、RXゲイン(AGC/手動)は「設定２」と共通です。"
            + "「設定１」タブのオンデバイス復調がONのときはテスト用で、「測定開始」と同時に自局もテストパターンで"
            + "送信し、自分の電波のRSSIを測ります(アッテネータを必ず接続すること)。OFFのときは送信せず、"
            + "相手局の電波のRSSIを測ります(相手局が送信していなければグラフが平らなのは正常です)。"
            + "受信中は使えません。SSB/FMは測定開始時に自動で止めます。タブを離れると検索は止まります。"
            + "(画像は、AGC OFF(手動60dB)・オンデバイス復調ONで1回測定した例。1273MHzで送信した自局のDATV信号が山として表れています)",
        "Sweeps ±5/10/20 MHz around the operating frequency (Frequency tab) in kHz steps, measures Pluto's RSSI, "
            + "and shows a graph and the strongest frequency (a smaller RSSI value means a stronger signal). Choose "
            + "Repeat (until Stop Measuring) or Once; the RX gain (AGC/manual) is shared with the Config 2 tab. When on-device "
            + "demodulation (Config 1 tab) is on, this is a test mode: the app also transmits the test pattern and "
            + "measures its own signal (always connect an attenuator). When it is off, the app does not transmit and "
            + "measures the other station (a flat graph is normal if nobody is transmitting). It cannot run while "
            + "receiving; SSB/FM is stopped automatically when measuring starts. Leaving the tab stops the search. "
            + "(The image shows one measurement with AGC off (manual 60 dB) and on-device demodulation on; the station's "
            + "own DATV signal transmitted at 1273 MHz appears as the peak.)",
    ),
    HelpSection(
        "周波数", "Frequency",
        "左のバンド一覧からボタンで選ぶか、右のテンキーで周波数(kHz)を直接入力します。"
            + "DATVの送信と受信は同じ設定を共有します(SSB/FMの周波数はSSB/FMタブで別に設定します)。",
        "Choose a band from the list on the left, or type the frequency in kHz on the keypad on the right. "
            + "DATV transmit and receive share this setting (the SSB/FM frequency is set separately on the SSB/FM tab).",
    ),
    HelpSection(
        "設定１", "Config 1",
        "オンデバイス復調(GNU Radio)の有効/無効と、PA/PTTコントローラを設定します(表示言語は上部バーで切り替えます)。"
            + "オンデバイス復調をONにすると、Pluto1台でRFのループバック試験ができます"
            + "(画像を送信しながら同時にその画像を受信します)。外部アッテネータなしで行うと"
            + "Plutoを破損する恐れがあるため、有効化時に必ず警告が表示されます。"
            + "「PA/PTTコントローラを使う」(既定ON)とIPアドレス(既定192.168.0.100)は、SSB/FMタブのPTTと"
            + "ESP32+W5500(hardware/W5500_PA_PTT_Control)の連携に使います(GET /tx?state=on|off)。"
            + "DATVの送信開始/終了でも同じ通知を送り、アプリ起動の5秒後に12V電源(Pluto含む)をON、"
            + "「終了」でPTTと12V電源をOFFにします(GET /ch?idx=0&state=on|off)。",
        "Sets on-device demodulation (GNU Radio) and the PA/PTT controller (the display language is switched in "
            + "the top bar). Enabling on-device demodulation lets you run an RF loopback test with a single Pluto "
            + "(you transmit an image while receiving that same image). Doing so without an external attenuator can "
            + "damage the Pluto, so a warning is always shown before enabling it. \"Use the PA/PTT controller\" "
            + "(default: on) and its IP address (default: 192.168.0.100) link the SSB/FM tab's PTT to the ESP32+W5500 "
            + "board (hardware/W5500_PA_PTT_Control) via GET /tx?state=on|off. DATV TX start/stop sends the same "
            + "notifications, the 12 V power (including the Pluto) is turned ON 5 seconds after app start, and Quit "
            + "turns PTT and the 12 V power OFF (GET /ch?idx=0&state=on|off).",
    ),
    HelpSection(
        "設定２", "Config 2",
        "DATVの受信感度と送信出力を設定します。受信感度は「自動(AGC)」をONにするとAGCがゲインを"
            + "自動調整し、OFFにすると0〜73dBの範囲で手動調整できます。下には受信のロック状態を表示します。"
            + "送信出力は出力減衰量を-70〜0dBの範囲で設定します(0dBが最大出力で、Plutoの送信出力減衰値としてそのまま適用されます)。",
        "Sets the DATV receive gain and transmit power. For receive gain, turn on \"Auto (AGC)\" to let AGC adjust "
            + "the gain, or turn it off to set 0 to 73 dB manually; the receive lock state is shown below it. For "
            + "transmit power, set the attenuation from -70 to 0 dB (0 dB is maximum output, applied directly as "
            + "Pluto's TX attenuation).",
    ),
    HelpSection(
        "設定３", "Config 3",
        "シンボルレート・誤り訂正(FEC)・変調方式を1画面で設定します。シンボルレートはプリセット"
            + "(250k〜2000k)から選ぶか「直接入力」で100〜5000 kS/sを入力します。誤り訂正は1/2・3/5・8/9、"
            + "変調方式はQPSK・8PSKから選び、コンステレーション(信号点配置)が表示されます。下段に組み合わせと"
            + "帯域幅・ビットレートの目安を表示します。DATVの送信と受信は同じ設定を共有します。",
        "Sets the symbol rate, FEC, and modulation on one screen. Choose a symbol-rate preset (250k to 2000k) or "
            + "tap Custom to enter 100–5000 kS/s. Choose FEC 1/2, 3/5, or 8/9 and QPSK or 8PSK; the constellation "
            + "is shown. The bottom line shows the combination and the estimated bandwidth and bit rate. DATV "
            + "transmit and receive share these settings.",
    ),
    HelpSection(
        "設定４(映像ソース)", "Config 4 (Video Source)",
        "背面カメラ(既定)・前面カメラ・写真(写真フォルダーから選択)・テストパターン(カラーバー)の"
            + "いずれかを選びます。ここで選んだ映像が「送信」タブでのプレビューと送信対象になります。"
            + "「写真」を選ぶと、写真に焼き込む「コールサイン」「備考」を入力できます。"
            + "「マイク音声を送信」をONにすると、端末のマイク音声も一緒に送信します。"
            + "「送信」タブの「送信音量」スライダーでマイク音声の大きさを0〜100%で調整できます(初期値80%で入力そのまま、100%で2倍、送信中も即時反映)。",
        "Choose the rear camera (default), the front camera, a photo (picked from your photo folder), or the test "
            + "pattern (color bars). The selection here is used for the preview and transmission on the Transmit "
            + "tab. When \"Photo\" is chosen, you can enter a \"Callsign\" and \"Note\" that are burned into the "
            + "photo. Turn on \"Transmit Mic Audio\" to send the device's microphone audio as well."
            + " The \"TX Volume\" slider on the Transmit tab adjusts the microphone level from 0 to 100% (default 80% = unchanged, 100% = 2x), applied immediately even while transmitting.",
    ),
    HelpSection(
        "設定４(配信先)", "Config 4 (Stream Output)",
        "「送信先(Pluto Tx)のIPアドレス」でPlutoのIPアドレスを設定します。このPlutoはDATVの送受信だけでなく、"
            + "SSB/FMとRSSI測定でも使います。DATV送信は常にPlutoの固定ポート8282(UDP-TS)へ送ります。"
            + "「自動検出」を押すと、まず端末が接続中のネットワーク(同じサブネット)からPlutoを探し、"
            + "見つからなければESP32ブリッジに問い合わせます。見つかればIPアドレス欄へ自動反映します"
            + "(検索には最大で数十秒かかります)。アプリの起動時にも一度だけ自動検出を行います。"
            + "「受信設定」のTSポート/ステータスポートは、オンデバイス復調がオフの時に外部復調機器からの"
            + "UDP-TSを待ち受けるポートです。",
        "Set Pluto's IP address in \"TX Destination (Pluto Tx) IP\". This Pluto is used not only for DATV TX/RX but "
            + "also for SSB/FM and RSSI measurement. DATV transmission always goes to Pluto's fixed UDP-TS port 8282. "
            + "Tap Auto-Detect to look for Pluto first on the network the device is connected to (same subnet) and, "
            + "if it is not found there, by asking the ESP32 bridge; the IP address field is filled in automatically "
            + "if found (the search can take up to several tens of seconds). Auto-detection also runs once when the "
            + "app starts. The TS/Status ports under Receive Settings are used to listen for UDP-TS from an external "
            + "demodulator when on-device demodulation is off.",
    ),
    HelpSection(
        "上部バー(表示言語・アプリ再起動・終了)", "Top Bar (Language, App Restart, Quit)",
        "上部バーの「日本語｜English」で表示言語を切り替えます(選択中の言語が水色で表示されます)。"
            + "「アプリ再起動」は送受信(DATV・SSB/FM)を止めてからPlutoの再起動要求を送り、Web UI/iiodの復旧を待ちます"
            + "(アプリの起動時にも同じ処理を行います)。通信がおかしい時にお試しください。"
            + "「終了」を押すと確認のあとアプリを終了します。送信・受信中の場合は先に停止してください。",
        "Switch the display language with \"日本語 | English\" in the top bar (the selected language is shown in "
            + "light blue). \"App Restart\" stops TX/RX (DATV and SSB/FM), sends a reboot request to Pluto, and waits for its "
            + "Web UI/iiod to come back online (the same happens when the app starts). Try this if communication "
            + "seems stuck. \"Quit\" closes the app after a confirmation; stop TX/RX first if they are running.",
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

/** ヘルプ画面 -- 全タブの使い方と、SSB/FM・DATV送信・DATV受信それぞれの操作順序を説明する。 */
@Composable
fun HelpScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings

    SettingsSubScreen(
        title = settings.t("ヘルプ", "Help"),
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
                        "SSB/FM送受信機能: Colin Durbridge氏(G4EML)のLangstone-V2(https://github.com/g4eml/Langstone-V2)の"
                            + "Adalm Pluto版の信号処理とPluto制御をC++へ移植して使用しています。Langstone-V2は"
                            + "GNU General Public License v3(GPLv3)のソフトウェアのため、それを使用した本アプリもGPLv3で配布します。"
                            + "Plutoを528kspsで動かすFIR係数と設定手順はAnalog DevicesのlibAD9361-iio(LGPL-2.1)に由来します。",
                        "SSB/FM transceiver: a C++ port of the signal processing and Pluto control of the Adalm Pluto "
                            + "version of Langstone-V2 by Colin Durbridge, G4EML (https://github.com/g4eml/Langstone-V2). "
                            + "Because Langstone-V2 is licensed under the GNU General Public License v3 (GPLv3), this app, "
                            + "which uses it, is also distributed under GPLv3. The FIR coefficients and procedure used to run "
                            + "Pluto at 528 ksps come from Analog Devices' libAD9361-iio (LGPL-2.1).",
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
                    settings.t("SSB/FMの使い方", "Using SSB/FM"),
                    color = SectionTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                HelpStepList(ssbFmSteps, settings)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    settings.t("DATV送信の設定順序", "Setup Order for DATV Transmit"),
                    color = SectionTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                HelpStepList(txSteps, settings)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    settings.t("DATV受信の設定順序", "Setup Order for DATV Receive"),
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
                    sectionImages[section.titleJA]?.forEach { image ->
                        Image(
                            painter = painterResource(image),
                            contentDescription = settings.t(section.titleJA, section.titleEN),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(HELP_IMAGE_ASPECT)
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

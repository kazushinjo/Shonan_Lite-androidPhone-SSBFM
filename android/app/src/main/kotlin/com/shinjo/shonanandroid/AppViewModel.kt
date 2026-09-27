package com.shinjo.shonanandroid

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.diagnostics.FileLogger
import com.shinjo.shonanandroid.dvbs2.PlutoTuner
import com.shinjo.shonanandroid.dvbs2.RssiNativeSession
import com.shinjo.shonanandroid.rx.RxController
import com.shinjo.shonanandroid.ssbfm.SsbFmController
import com.shinjo.shonanandroid.tx.TxController
import com.shinjo.shonanandroid.tx.TxNetworkStats
import com.shinjo.shonanandroid.net.NetworkBinder
import com.shinjo.shonanandroid.net.PlutoSettingsWriter
import com.shinjo.shonanandroid.net.PlutoUdpTsController
import androidx.camera.core.Preview
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** RSSI測定の1点分の結果。rssiDbはAD9361の`voltage0/rssi`(値が小さいほど強い)。 */
data class RssiMeasurement(val frequencyHz: Long, val rssiDb: Double)

/** RSSI測定でRSSI変動をピークと認めるしきい値(dB)。Shonan_Lite-winと同じ値。 */
private const val RSSI_PEAK_THRESHOLD_DB = 3.0
/** RSSI測定の1ステップの間隔(ms)。Shonan_Lite-winのSTEP_INTERVAL_MSと同じ。 */
private const val RSSI_STEP_INTERVAL_MS = 150L
/** RSSI測定がこの回数続けて失敗したら(接続し直しても回復しなければ)検索を中止する。 */
private const val RSSI_MAX_CONSECUTIVE_FAILURES = 3
/** RSSI画面でRXゲインを変更してからPlutoへ反映するまでの待ち(連打・長押しをまとめる)。 */
private const val RSSI_GAIN_APPLY_DELAY_MS = 250L

const val RX_GAIN_MIN_DB = 0
const val RX_GAIN_MAX_DB = 73

/** RSSI値の表示(Win版の`{rssi:g}`相当: 不要な末尾の0を付けない)。 */
fun formatRssiValue(rssi: Double): String =
    if (rssi == Math.floor(rssi)) rssi.toLong().toString() else rssi.toBigDecimal().stripTrailingZeros().toPlainString()

/** libiio IIODの既定TCPポート。 */
private const val IIOD_PORT = 30431

/**
 * libiioの`iio_create_context`は到達不能なIPに対してネイティブクラッシュ(SIGSEGV)する
 * ことがあるため、ネイティブセッションを開く前にTCP到達性を確認する。
 */
private suspend fun isPlutoReachable(ip: String, timeoutMs: Int = 2000): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, IIOD_PORT), timeoutMs) }
            true
        } catch (e: IOException) {
            false
        }
    }

/**
 * アプリ全体の設定とTx/Rxコントローラを保持する -- iOS版`AppViewModel`(実質
 * `AppSettings`+`TxSessionController`+`RxSessionController`の保有側)に相当。
 *
 * [txController]/[rxController]はここで保有し[ProcessLifecycleOwner]に束縛する
 * (Compose destinationのライフサイクルではなく)ことで、画面遷移してもカメラ/
 * 送受信セッションが破棄されないようにする(他プロジェクトの`AppViewModel`と同じ理由)。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    init {
        // インターネット未接続のWi-Fi(Pluto運用時の典型)でもPlutoとの通信が
        // OSにルーティングされるよう、起動時にプロセスをWi-Fiへ明示バインドする。
        // TODO(デバッグ中): SSH(JSch/sshj)だけがTCP接続直後のバナー読み取りで無応答に
        // なる原因切り分けのため、一時的に無効化して検証する。
        // NetworkBinder.bindToWifi(getApplication())
    }

    var settings by mutableStateOf(SettingsStore.load(getApplication()))
        private set

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        settings = update(settings)
        SettingsStore.save(getApplication(), settings)
    }

    val txController = TxController(getApplication(), ProcessLifecycleOwner.get())
    var isTransmitting by mutableStateOf(false)
    // startTX()の準備処理(HTTP/SSH通信で数秒かかる)が完了する前にボタンを連打すると、
    // 前の試行がまだ結果不明のまま次のコルーチンが並行して走ってしまうためのガード。
    var isPreparingTx by mutableStateOf(false)
        private set
    private val _txError = mutableStateOf<String?>(null)
    var txError: String?
        get() = _txError.value
        set(value) {
            _txError.value = value
            value?.let { FileLogger.log("TX_ERROR", it) }
        }

    /** 実運用の送信経路はPluto向けUDP-TS送信に統一する。 */
    val activeTxPreview: Preview get() = txController.preview
    val activeTxStats: TxNetworkStats get() = txController.stats

    /**
     * Pluto起動時リブート([[HomeScreen.runPlutoStartupReboot]])をアプリ起動後に一度だけ
     * 実行済みにするためのフラグ。Home画面のComposableは他画面へ遷移するたびにコンポジションから
     * 外れて`remember`状態が失われるため、このフラグを画面をまたいで生存するViewModel側に持たせないと、
     * Rx/Tx画面からホームへ戻るたびに毎回Plutoへreboot要求が飛び、復旧待ちの全画面表示で
     * 「ホームへ戻るボタンが効かない」ように見えるバグになる。
     */
    var didRunStartupPlutoReboot = false

    /** [[HomeScreen]]の起動時Pluto自動検出([[PlutoDiscoveryClient]])を一度だけ実行済みにする
     *  フラグ。didRunStartupPlutoRebootと同じ理由でViewModel側に持たせる。 */
    var didRunStartupPlutoDiscovery = false

    val rxController = RxController(getApplication())
    var isReceiving by mutableStateOf(false)
    var isPreparingRx by mutableStateOf(false)
        private set
    private val _rxError = mutableStateOf<String?>(null)
    var rxError: String?
        get() = _rxError.value
        set(value) {
            _rxError.value = value
            value?.let { FileLogger.log("RX_ERROR", it) }
        }

    /** SSB/FM送受信タブ(Langstone-V2移植)。DATVの送受信とは同じPlutoを使うため排他にする。 */
    val ssbFm = SsbFmController(getApplication()) { ja, en -> settings.t(ja, en) }

    init {
        txController.onError = { txError = it }
        rxController.onError = { rxError = it }
    }

    fun startSsbFm() {
        stopRssi()
        if (isTransmitting) stopTX()
        if (isReceiving) stopRX()
        ssbFm.start(settings.txDestinationIP)
    }

    /**
     * @param txSettings 送信に使う設定。省略時は現在の設定。RSSI測定の自動送信は
     *   映像ソースだけ差し替えた複製を渡す(保存済みの設定は変えない)。
     */
    fun startTX(txSettings: AppSettings? = null) {
        if (isTransmitting || isPreparingTx) return
        if (isReceiving && !settings.useOnDeviceGRDVBS2Rx) {
            txError = "受信中は送信を開始できません。受信を停止してください。"
            return
        }
        txError = null
        isPreparingTx = true
        viewModelScope.launch {
            try {
                ssbFm.stopAndWait()
                if (!preparePlutoTx()) return@launch
                if (!tunePluto(isTx = true)) return@launch
                txController.start(txSettings ?: settings)
                isTransmitting = true
            } finally {
                isPreparingTx = false
            }
        }
    }

    fun stopTX() {
        txController.stop()
        isTransmitting = false
    }

    fun startRX() {
        if (isReceiving || isPreparingRx) return
        if (isTransmitting && !settings.useOnDeviceGRDVBS2Rx) {
            rxError = "送信中は受信を開始できません。送信を停止してください。"
            return
        }
        rxError = null
        isPreparingRx = true
        viewModelScope.launch {
            try {
                ssbFm.stopAndWait()
                if (!tunePluto(isTx = false)) return@launch
                rxController.start(settings)
                isReceiving = true
            } finally {
                isPreparingRx = false
            }
        }
    }

    fun stopRX() {
        rxController.stop()
        isReceiving = false
    }

    fun setRxVolume(volume: Float) {
        updateSettings { it.copy(rxVolume = volume) }
        rxController.setVolume(volume)
    }

    /**
     * TX/RX開始時にPluto(txDestinationIP)へ実際のLOを設定する(TS自体は別経路のUDPで流れる)。
     * 失敗時はtxError/rxErrorを設定してfalseを返す -- 呼び出し側はisTransmitting/isReceivingを
     * 立てず、送受信コントローラも起動しないこと(以前はチューニング成否を待たずに立てていたため、
     * 失敗時にフラグが true のまま固まり機器試験の診断ボタンが再起動までグレーアウトし続けるバグがあった)。
     */
    private suspend fun tunePluto(isTx: Boolean): Boolean {
        val plutoIp = settings.txDestinationIP
        val frequencyHz = settings.effectiveLoHz
        val ok = withContext(Dispatchers.IO) {
            val reachable = isPlutoReachable(plutoIp)
            FileLogger.log("TUNE", "isPlutoReachable(IIOD:$IIOD_PORT) ip=$plutoIp result=$reachable")
            reachable && run {
                val tuner = PlutoTuner(plutoIp)
                val tuned = tuner.isOpen && tuner.tune(isTx, frequencyHz)
                FileLogger.log("TUNE", "PlutoTuner isOpen=${tuner.isOpen} tuned=$tuned freqHz=$frequencyHz")
                tuner.close()
                tuned
            }
        }
        if (!ok) {
            val message = "Plutoの周波数設定に失敗しました($plutoIp)"
            if (isTx) txError = message else rxError = message
        }
        return ok
    }

    private suspend fun preparePlutoTx(): Boolean {
        val host = settings.txDestinationIP
        FileLogger.log("TX_PREP", "start host=$host")
        val applied = PlutoSettingsWriter.applyTxSettings(host, settings)
        if (applied.isFailure) {
            FileLogger.log("TX_PREP", "applyTxSettings(HTTP:80) failed: ${applied.exceptionOrNull()}")
            txError = "Pluto変調設定の反映に失敗しました: ${applied.exceptionOrNull()?.message}"
            return false
        }
        FileLogger.log("TX_PREP", "applyTxSettings(HTTP:80) ok")
        val restarted = PlutoUdpTsController.restart(host, getApplication())
        if (restarted.isFailure) {
            FileLogger.log("TX_PREP", "PlutoUdpTsController.restart(SSH:22) failed: ${restarted.exceptionOrNull()}")
            txError = "Pluto UDP受信経路の起動に失敗しました: ${restarted.exceptionOrNull()?.message}"
            return false
        }
        FileLogger.log("TX_PREP", "PlutoUdpTsController.restart(SSH:22) ok")
        ssbFm.onPlutoDatvRestarted()
        delay(1_000)
        return true
    }


    // --- RSSI測定 (Shonan_Lite-win `app/gui/screens/rssi.py` と同じ動作) ---
    var rssiIsScanning by mutableStateOf(false)
        private set
    var rssiStatus by mutableStateOf("")
        private set
    /** 実行中の1周(開始〜終了周波数)の範囲。グラフの横軸に使う。 */
    var rssiSweepStartHz by mutableStateOf(0L)
        private set
    var rssiSweepEndHz by mutableStateOf(0L)
        private set
    /** 実行中の1周で得た測定値(周回の先頭に戻るたびにクリアする)。 */
    var rssiMeasurements by mutableStateOf<List<RssiMeasurement>>(emptyList())
        private set
    /** 確定した「最も強い周波数」。明確なピークが無かった周回では前回値を保持する(Win版と同じ)。 */
    var rssiBestFrequencyHz by mutableStateOf<Long?>(null)
        private set
    var rssiBestRssiDb by mutableStateOf<Double?>(null)
        private set
    var rssiRestrictionMessage by mutableStateOf<String?>(null)
        private set
    private var rssiJob: Job? = null
    /** オンデバイス復調ON時に測定開始と同時に送信を開始した場合true(測定停止時に送信も止める)。 */
    private var rssiTxStartedByScan = false
    /** RXゲインが変更された時刻(ms)。走査ループが250ms後にまとめてPlutoへ反映する。0=変更なし。 */
    @Volatile private var rssiGainChangedAtMs = 0L

    /**
     * 開始〜終了周波数をステップごとに走査してRSSIを測定する -- Shonan_Lite-win
     * (`app/gui/screens/rssi.py`)の移植。
     *
     * - AD9361の`voltage0/rssi`は値が小さいほど信号が強い指標なので、最小値の周波数を
     *   「最も強い周波数」とする。1周の変動幅が[RSSI_PEAK_THRESHOLD_DB]未満なら前回の結果を保持する。
     * - 検索方法「連続」は[AppSettings.rssiRepeatScan]がtrueの間、1周ごとに結果を確定して繰り返す。
     *   「1回」は範囲の終わりで自動停止する。「測定停止」は即座に止め、その時点までの結果を確定する。
     * - オンデバイス復調ON時は自局のRXを掃引するだけでは何も受からないため、測定開始と同時に
     *   送信も開始し(送信中なら設定を揃えるため一旦止めて開始し直す)、3秒待ってから測定を始める。
     *   測定停止時は、測定が開始した送信を止める。
     * - RXゲイン(AGC/手動)はRXゲイン画面と同じ設定値を使い、検索中の変更は250ms後にPlutoへ反映して
     *   その周回を最初からやり直す(異なるゲインの測定値が1周に混ざらないようにする)。
     */
    fun startRssi(startHz: Long, endHz: Long, stepHz: Long) {
        if (rssiIsScanning) return
        if (isReceiving) {
            // ★Android版の受信(オンデバイス復調)はRXのLOを使い続けるため、走査で動かすと受信が壊れる。
            rssiRestrictionMessage = settings.t(
                "受信中はRSSI測定できません。受信を停止してから実行してください。",
                "RSSI Measurement cannot run while RX is active. Stop RX first.",
            )
            return
        }
        val centerHz = settings.effectiveLoHz
        val plutoIp = settings.txDestinationIP
        rssiIsScanning = true
        rssiStatus = settings.t("検索中...", "Searching...")
        rssiSweepStartHz = startHz
        rssiSweepEndHz = endHz
        rssiMeasurements = emptyList()
        rssiGainChangedAtMs = 0L

        FileLogger.log("RSSI", "start range=$startHz..$endHz step=$stepHz onDevice=${settings.useOnDeviceGRDVBS2Rx} repeat=${settings.rssiRepeatScan}")
        rssiJob = viewModelScope.launch {
            // SSB/FMも同じPlutoのRX LOを使うため、先に止めてPlutoの設定をDATV側へ戻しておく。
            ssbFm.stopAndWait()
            var session: RssiNativeSession? = null
            var sweepBest: RssiMeasurement? = null
            fun beginSweep() {
                rssiMeasurements = emptyList()
                sweepBest = null
            }
            fun commitSweep() {
                val best = sweepBest ?: return
                val values = rssiMeasurements.map { it.rssiDb }
                if (values.isEmpty() || values.max() - values.min() < RSSI_PEAK_THRESHOLD_DB) return
                rssiBestFrequencyHz = best.frequencyHz
                rssiBestRssiDb = best.rssiDb
            }
            try {
                if (settings.useOnDeviceGRDVBS2Rx) {
                    if (isTransmitting) stopTX()
                    rssiTxStartedByScan = true
                    // ★映像ソースの選択に関係なくテストパターン(カラーバー)で送る。RSSI測定では
                    // 映像の中身は関係なく電波が出ていればよく、カメラ・画像の有無で送信が失敗
                    // しないようにする(Pi5版で、カメラ未接続時に何も送信されずRSSIが変化しない
                    // 不具合を実機確認)。画像(usePhotoSource)はカラーバーより優先されるので両方指定する。
                    startTX(settings.copy(useColorBarSource = true, usePhotoSource = false))
                    while (isPreparingTx) delay(100)
                    if (!isTransmitting) {
                        rssiRestrictionMessage = settings.t(
                            "送信の自動開始に失敗したため、検索を中止しました。",
                            "Automatic TX start failed, so the search was canceled.",
                        )
                        return@launch
                    }
                    // ★TX起動直後は送信がまだ安定していないため、3秒待ってから測定を始める。
                    delay(3_000)
                }
                if (!isPlutoReachable(plutoIp)) {
                    rssiStatus = settings.t("エラー: Plutoへ接続できません", "Error: cannot connect to Pluto")
                    return@launch
                }
                suspend fun openSession(): RssiNativeSession? = withContext(Dispatchers.IO) {
                    session?.close()
                    RssiNativeSession(plutoIp).takeIf { it.isOpen }?.also {
                        it.setRxGain(settings.rxAgcEnabled, settings.rxGainDb)
                    }.also { session = it }
                }
                var opened = openSession()
                if (opened == null) {
                    rssiStatus = settings.t("エラー: Plutoへ接続できません", "Error: cannot connect to Pluto")
                    return@launch
                }
                beginSweep()
                var frequencyHz = startHz
                var consecutiveFailures = 0
                while (isActive) {
                    val changedAt = rssiGainChangedAtMs
                    if (changedAt != 0L && System.currentTimeMillis() - changedAt >= RSSI_GAIN_APPLY_DELAY_MS) {
                        rssiGainChangedAtMs = 0L
                        val current = opened!!
                        withContext(Dispatchers.IO) { current.setRxGain(settings.rxAgcEnabled, settings.rxGainDb) }
                        beginSweep()
                        frequencyHz = startHz
                    }
                    if (frequencyHz > endHz) {
                        if (!settings.rssiRepeatScan) break
                        commitSweep()
                        beginSweep()
                        frequencyHz = startHz
                        continue
                    }
                    val current = opened!!
                    val rssi = withContext(Dispatchers.IO) { current.measure(frequencyHz) }
                    if (!rssi.isFinite()) {
                        // ★Wi-Fiの再接続等でPlutoとの接続が切れると以降の測定がすべて失敗する。
                        // 接続し直して同じ周波数を測り直し、続けて失敗したら検索を中止して知らせる。
                        consecutiveFailures++
                        FileLogger.log("RSSI", "measure failed freqHz=$frequencyHz consecutive=$consecutiveFailures")
                        if (consecutiveFailures >= RSSI_MAX_CONSECUTIVE_FAILURES) {
                            rssiStatus = settings.t(
                                "エラー: Plutoとの通信が途切れたため検索を中止しました(Wi-Fi接続を確認してください)",
                                "Error: lost connection to Pluto, search stopped (check the Wi-Fi connection)",
                            )
                            break
                        }
                        opened = openSession() ?: continue
                        continue
                    }
                    consecutiveFailures = 0
                    run {
                        val measurement = RssiMeasurement(frequencyHz, rssi)
                        rssiMeasurements = rssiMeasurements + measurement
                        if (sweepBest == null || rssi < sweepBest!!.rssiDb) {
                            sweepBest = measurement
                            rssiStatus = settings.t(
                                "検索中: ${frequencyHz / 1000} kHz / RSSI ${formatRssiValue(rssi)}",
                                "Searching: ${frequencyHz / 1000} kHz / RSSI ${formatRssiValue(rssi)}",
                            )
                        }
                    }
                    frequencyHz += stepHz
                    delay(RSSI_STEP_INTERVAL_MS)
                }
            } finally {
                // ★後片付けは全体をNonCancellableで包む。withContext(Dispatchers.IO + NonCancellable)だけだと、
                // IO側の処理が終わって呼び出し元(キャンセル済み)へ戻る時点でwithContextがCancellationExceptionを
                // 投げ(prompt cancellation guarantee)、以降のrssiIsScanning=false等が実行されず「測定停止」が
                // 効かないまま「検索中」表示が残る不具合を実機で確認した。
                withContext(NonCancellable) {
                    // キャンセル後もPlutoを元の中心周波数へ戻してクローズする。
                    withContext(Dispatchers.IO) {
                        session?.let { if (it.isOpen) it.measure(centerHz) }
                        session?.close()
                    }
                    commitSweep()
                    if (rssiTxStartedByScan) {
                        rssiTxStartedByScan = false
                        stopTX()
                    }
                    rssiIsScanning = false
                    rssiJob = null
                    if (rssiStatus.startsWith("検索中") || rssiStatus.startsWith("Searching")) {
                        rssiStatus = settings.t("検索待機中", "Search idle")
                    }
                    FileLogger.log("RSSI", "stopped")
                }
            }
        }
    }

    /** 検索を即座に停止する(その時点までの周回の結果を確定する)。 */
    fun stopRssi() {
        FileLogger.log("RSSI", "stop requested")
        rssiJob?.cancel()
    }

    fun setRssiRepeatScan(repeat: Boolean) {
        updateSettings { it.copy(rssiRepeatScan = repeat) }
    }

    /** RSSI画面からのRXゲイン変更(RXゲイン画面と同じ設定値)。検索中は250ms後にPlutoへ反映する。 */
    fun setRssiRxGain(agcEnabled: Boolean, gainDb: Int) {
        updateSettings { it.copy(rxAgcEnabled = agcEnabled, rxGainDb = gainDb.coerceIn(RX_GAIN_MIN_DB, RX_GAIN_MAX_DB)) }
        if (rssiIsScanning) rssiGainChangedAtMs = System.currentTimeMillis()
    }

    fun clearRssiRestrictionMessage() { rssiRestrictionMessage = null }
}

package com.shinjo.shonanandroid.ssbfm

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shinjo.shonanandroid.diagnostics.FileLogger
import com.shinjo.shonanandroid.net.PlutoUdpTsController
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SSB/FM送受信タブの状態と操作。画面遷移してもセッションが切れないよう
 * [com.shinjo.shonanandroid.AppViewModel]が保有する(DATVのTx/Rxコントローラと同じ扱い)。
 *
 * Plutoの操作はネットワーク越しで時間がかかることがあるため、ネイティブ呼び出しはすべて
 * 1本に直列化したディスパッチャーで行い、UIスレッドを止めない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SsbFmController(
    private val context: Context,
    private val tr: (japanese: String, english: String) -> String,
) {
    var settings by mutableStateOf(SsbFmSettingsStore.load(context))
        private set

    var isRunning by mutableStateOf(false)
        private set
    var isStarting by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var isTransmitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    var signalDb by mutableFloatStateOf(-200f)
        private set
    var rxGainDb by mutableFloatStateOf(0f)
        private set
    var micLevel by mutableFloatStateOf(0f)
        private set
    var squelchOpen by mutableStateOf(true)
        private set

    /** 最新スペクトル(fftshift済み、[SsbFmNative.SPECTRUM_SIZE]点)。[frameCounter]で更新を通知する。 */
    val spectrum = FloatArray(SsbFmNative.SPECTRUM_SIZE) { -200f }
    val waterfall: Bitmap = Bitmap.createBitmap(SsbFmNative.SPECTRUM_SIZE, WATERFALL_ROWS, Bitmap.Config.ARGB_8888)
    var frameCounter by mutableLongStateOf(0L)
        private set
    /** 表示用のノイズフロア(dB)。スペクトル表示とウォーターフォールの色付けの基準にする。 */
    var displayFloorDb by mutableFloatStateOf(-100f)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var native: SsbFmNative? = null
    private var pollJob: Job? = null
    private val frequencyRequests = MutableStateFlow<Long?>(null)
    private val waterfallPixels = IntArray(SsbFmNative.SPECTRUM_SIZE * WATERFALL_ROWS)
    private val colorMap = IntArray(256) { waterfallColor(it / 255f) }

    /** pluto_dvbを止めたPlutoのアドレス。DATV送信の準備でudpts.shが再起動されたらnullに戻す。 */
    private var datvStoppedHost: String? = null

    init {
        waterfall.eraseColor(android.graphics.Color.BLACK)
        // 周波数ドラッグ中の大量の要求は最新値だけを反映する(StateFlowは取りこぼしを許して最新値を流す)。
        scope.launch {
            frequencyRequests.filterNotNull().collect { hz ->
                val n = native ?: return@collect
                if (!isRunning) return@collect
                val ok = withContext(nativeDispatcher) { n.setFrequency(hz) }
                if (!ok) error = tr("周波数を設定できません", "Failed to set the frequency")
            }
        }
    }

    fun start(plutoIp: String) {
        if (isRunning || isStarting) return
        error = null
        isStarting = true
        scope.launch {
            try {
                if (!isReachable(plutoIp)) {
                    error = tr("Plutoに接続できません($plutoIp)", "Cannot reach Pluto ($plutoIp)")
                    return@launch
                }
                if (datvStoppedHost != plutoIp) {
                    status = tr("PlutoのDATV送信処理を停止中…", "Stopping Pluto DATV transmitter…")
                    val stopped = PlutoUdpTsController.stop(plutoIp, context)
                    if (stopped.isSuccess) {
                        datvStoppedHost = plutoIp
                    } else {
                        FileLogger.log("SSBFM", "pluto_dvb stop failed: ${stopped.exceptionOrNull()}")
                        error = tr(
                            "PlutoのDATV送信処理を停止できませんでした(送信できない場合があります)",
                            "Could not stop the Pluto DATV transmitter (transmit may fail)",
                        )
                    }
                }
                status = tr("Plutoを設定中…", "Configuring Pluto…")
                val n = native ?: SsbFmNative().also { created ->
                    created.onError = { message -> scope.launch { error = message } }
                    native = created
                }
                val s = settings
                val ok = withContext(nativeDispatcher) {
                    n.setAfGain(s.afGain)
                    n.setMicGain(s.micGain)
                    n.setSquelch(effectiveSquelch(s))
                    n.start(
                        "ip:$plutoIp", s.frequencyHz, s.mode, s.rxAgc, s.rxGainDb.toDouble(), s.txAttenuationDb.toDouble(),
                    )
                }
                FileLogger.log("SSBFM", "start ip=$plutoIp freq=${s.frequencyHz} mode=${s.mode} ok=$ok")
                if (ok) {
                    isRunning = true
                    startPolling()
                    val host = s.activePttControllerHost
                    if (host.isNotEmpty() && !withContext(Dispatchers.IO) { Esp32PttClient.isReachable(host) }) {
                        error = tr(
                            "PA/PTTコントローラ(ESP32 $host)に接続できません。このまま送信するとPA/PTTは切り替わりません",
                            "Cannot reach the PA/PTT controller (ESP32 $host). If you transmit now, the PA/PTT will not be switched.",
                        )
                    }
                }
            } finally {
                status = null
                isStarting = false
            }
        }
    }

    fun stop() {
        scope.launch { stopAndWait() }
    }

    /** DATVの送受信を始める前に呼び、Plutoの設定をDATV側へ戻し終わるまで待つ。 */
    suspend fun stopAndWait() {
        val n = native ?: return
        if (!isRunning) return
        pollJob?.cancel()
        val wasTransmitting = isTransmitting
        val host = settings.activePttControllerHost
        val message = withContext(nativeDispatcher) {
            n.stop()
            if (wasTransmitting && host.isNotEmpty()) pttOffSequence(n, host) else null
        }
        if (message != null) error = message
        isRunning = false
        isTransmitting = false
        signalDb = -200f
        FileLogger.log("SSBFM", "stopped")
    }

    /** DATV送信の準備でPlutoのudpts.sh(pluto_dvb)が再起動された/Plutoが再起動されたことを知らせる。 */
    fun onPlutoDatvRestarted() {
        datvStoppedHost = null
    }

    /**
     * PTT。PA/PTTコントローラ(ESP32)が設定されていれば、送信開始は
     * 「ESP32へ通知→リレー切替を待つ→PlutoのRF開始」、終了は「RF停止→ESP32へ通知」の順にする
     * (LNAに送信電力を入れない・リレー接点を通電中に切り替えないため)。ESP32が設定されているのに
     * 応答しない場合は、PA/PTTが切り替わっていないことを警告したうえで送信する(利用者の指定)。
     * 押す・離すが続いても順序が崩れないよう、一連の処理はネイティブ呼び出しと同じ直列ディスパッチャーで行う。
     */
    fun setPtt(on: Boolean) {
        val n = native ?: return
        if (!isRunning || on == isTransmitting) return
        isTransmitting = on
        val host = settings.activePttControllerHost
        scope.launch {
            if (on) {
                val result = withContext(nativeDispatcher) { pttOnSequence(n, host) }
                result.warning?.let { error = it }
                if (!result.transmitting) isTransmitting = false
            } else {
                val message = withContext(nativeDispatcher) { pttOffSequence(n, host) }
                if (message != null) error = message
            }
        }
    }

    /** 送信開始の結果。[warning]は画面に出す警告(無ければnull)。 */
    private data class PttOnResult(val transmitting: Boolean, val warning: String?)

    private fun pttOnSequence(n: SsbFmNative, host: String): PttOnResult {
        var warning: String? = null
        var notifiedOn = false
        if (host.isNotEmpty()) {
            val notified = Esp32PttClient.notifyTx(host, on = true)
            FileLogger.log("SSBFM", "ESP32 tx=on host=$host result=$notified")
            if (notified.isSuccess) {
                notifiedOn = true
                Thread.sleep(PTT_LEAD_MS)
            } else {
                warning = tr(
                    "PA/PTTコントローラ(ESP32 $host)が応答しません。PA/PTTは切り替わらないまま送信しています",
                    "The PA/PTT controller (ESP32 $host) did not respond. Transmitting without switching the PA/PTT.",
                )
            }
        }
        if (n.setPtt(true)) return PttOnResult(transmitting = true, warning = warning)
        // RFを出せなかったらESP32側も受信状態へ戻す(エラー表示はネイティブ側から届く)
        if (notifiedOn) Esp32PttClient.notifyTx(host, on = false)
        return PttOnResult(transmitting = false, warning = null)
    }

    private fun pttOffSequence(n: SsbFmNative, host: String): String? {
        n.setPtt(false)
        if (host.isEmpty()) return null
        val notified = Esp32PttClient.notifyTx(host, on = false, attempts = 3)
        FileLogger.log("SSBFM", "ESP32 tx=off host=$host result=$notified")
        return if (notified.isSuccess) null else tr(
            "PA/PTTコントローラ(ESP32 $host)へ送信終了を通知できませんでした。PTT・PAの状態を確認してください",
            "Could not tell the PA/PTT controller (ESP32 $host) that TX ended. Check the PTT/PA state.",
        )
    }

    fun setFrequency(hz: Long) {
        // 表示の最小桁(1kHz)に揃える
        val clamped = ((hz + 500) / 1000 * 1000).coerceIn(SsbFmSettings.MIN_FREQUENCY_HZ, SsbFmSettings.MAX_FREQUENCY_HZ)
        if (clamped == settings.frequencyHz) return
        update { it.copy(frequencyHz = clamped) }
        frequencyRequests.value = clamped
    }

    fun setMode(mode: SsbFmMode) {
        update { it.copy(mode = mode) }
        val sql = effectiveSquelch(settings)
        withNative {
            it.setMode(mode)
            it.setSquelch(sql)
        }
    }

    /** スケルチはFMだけで使う(USBでは常に開放。設定値はFMへ戻したときのために残す)。 */
    private fun effectiveSquelch(s: SsbFmSettings): Float =
        if (s.mode == SsbFmMode.FM) s.squelchDb else SsbFmSettings.SQUELCH_OFF

    fun setStep(stepHz: Long) = update { it.copy(stepHz = stepHz.coerceAtLeast(SsbFmSettings.MIN_STEP_HZ)) }

    /** 今の周波数が属するバンド(どのバンドにも入らなければnull)。 */
    val currentBand: SsbFmBand? get() = SsbFmBand.of(settings.frequencyHz)

    /** 今のバンドの周波数・モードを覚えてから、選んだバンドの最後の周波数・モードへ移る。 */
    fun selectBand(band: SsbFmBand) {
        if (isTransmitting) return
        currentBand?.let { SsbFmSettingsStore.saveBandMemory(context, it, settings.frequencyHz, settings.mode) }
        val (hz, mode) = SsbFmSettingsStore.loadBandMemory(context, band)
        if (mode != settings.mode) setMode(mode)
        setFrequency(hz)
    }

    fun setAfGain(value: Float) {
        update { it.copy(afGain = value) }
        withNative { it.setAfGain(value) }
    }

    fun setSquelch(db: Float) {
        update { it.copy(squelchDb = db) }
        val sql = effectiveSquelch(settings)
        withNative { it.setSquelch(sql) }
    }

    fun setMicGain(value: Float) {
        update { it.copy(micGain = value) }
        withNative { it.setMicGain(value) }
    }

    fun setRxGain(agc: Boolean, db: Float) {
        update { it.copy(rxAgc = agc, rxGainDb = db) }
        withNative { it.setRxGain(agc, db.toDouble()) }
    }

    fun setTxAttenuation(db: Float) {
        update { it.copy(txAttenuationDb = db) }
        withNative { it.setTxAttenuation(db.toDouble()) }
    }

    fun setPttLatch(latch: Boolean) = update { it.copy(pttLatch = latch) }

    fun setPttControllerHost(host: String) = update { it.copy(pttControllerHost = host.trim()) }

    fun setPttControllerEnabled(enabled: Boolean) = update { it.copy(pttControllerEnabled = enabled) }

    private fun update(change: (SsbFmSettings) -> SsbFmSettings) {
        settings = change(settings)
        SsbFmSettingsStore.save(context, settings)
    }

    private fun withNative(action: (SsbFmNative) -> Unit) {
        val n = native ?: return
        if (!isRunning) return
        scope.launch { withContext(nativeDispatcher) { action(n) } }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && isRunning) {
                val n = native ?: break
                signalDb = n.signalDb
                rxGainDb = n.rxGainDb
                micLevel = n.micLevel
                squelchOpen = n.isSquelchOpen
                if (n.readSpectrum(spectrum)) {
                    updateFloor()
                    pushWaterfallRow()
                    frameCounter++
                }
                delay(66)
            }
        }
    }

    // ノイズフロアは下位20%点の値を緩やかに追従させる。
    private fun updateFloor() {
        val sorted = spectrum.copyOf().also { it.sort() }
        val floor = sorted[sorted.size / 5]
        displayFloorDb = if (displayFloorDb < -190f) floor else displayFloorDb + (floor - displayFloorDb) * 0.05f
    }

    private fun pushWaterfallRow() {
        val w = SsbFmNative.SPECTRUM_SIZE
        System.arraycopy(waterfallPixels, 0, waterfallPixels, w, w * (WATERFALL_ROWS - 1))
        val floor = displayFloorDb - 3f
        for (i in 0 until w) {
            val v = ((spectrum[i] - floor) / WATERFALL_RANGE_DB).coerceIn(0f, 1f)
            waterfallPixels[i] = colorMap[(v * 255).toInt()]
        }
        waterfall.setPixels(waterfallPixels, 0, w, 0, 0, w, WATERFALL_ROWS)
    }

    private suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        // libiioは到達不能なIPでネイティブクラッシュすることがあるため、先にIIOD(30431)へのTCP接続を確かめる。
        try {
            Socket().use { it.connect(InetSocketAddress(ip, 30431), 2000) }
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        const val WATERFALL_ROWS = 160
        /** ESP32へ送信開始を通知してからRFを出すまでの待ち時間。ESP32側のPTT遅延(既定50ms)や
         *  LNA→PTT切替(Pi4版100ms)が終わるのを待つ。 */
        const val PTT_LEAD_MS = 150L
        const val WATERFALL_RANGE_DB = 45f

        private fun waterfallColor(v: Float): Int {
            // 黒→青→シアン→黄→赤
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val colors = arrayOf(
                intArrayOf(0, 0, 0), intArrayOf(0, 0, 200), intArrayOf(0, 200, 220),
                intArrayOf(240, 230, 0), intArrayOf(255, 40, 0),
            )
            var k = 0
            while (k < stops.size - 2 && v > stops[k + 1]) k++
            val t = ((v - stops[k]) / (stops[k + 1] - stops[k])).coerceIn(0f, 1f)
            val r = (colors[k][0] + (colors[k + 1][0] - colors[k][0]) * t).toInt()
            val g = (colors[k][1] + (colors[k + 1][1] - colors[k][1]) * t).toInt()
            val b = (colors[k][2] + (colors[k + 1][2] - colors[k][2]) * t).toInt()
            return android.graphics.Color.rgb(r, g, b)
        }
    }
}

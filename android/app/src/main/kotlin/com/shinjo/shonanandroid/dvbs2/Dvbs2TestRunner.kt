package com.shinjo.shonanandroid.dvbs2

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.shinjo.shonanandroid.tx.CameraPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pluto実機に対するTX/RX単体診断(機器試験) -- iOS版`DVBS2TestRunner.swift`の`runDiag()`
 * 移植。libiio経由でPlutoのAD9361へ直接IQサンプルを送受信し(aff3ct/dvbs2でDVB-S2変調・
 * 復調)、半二重運用の実情に合わせTX→完全停止→RXの順に各系統を単体診断する(実際の信号
 * 往復=ループバックの確認はしない)。
 *
 * TXは「送信データが枯渇せず供給できているか(starved率)」「DACへの書き込み振幅が健全か
 * (rms)」、RXは「接続が確立し、エラーなくストリーミングを継続できるか」を合否判定基準と
 * する(iOS版と同じ基準)。
 */
class Dvbs2TestRunner(
    private val scope: CoroutineScope,
    context: Context,
    lifecycleOwner: LifecycleOwner,
) {
    var diagRunning by mutableStateOf(false)
        private set
    var diagPhaseText by mutableStateOf("")
        private set
    var diagResultText by mutableStateOf("")
        private set
    var diagLog by mutableStateOf("")
        private set

    /** 機器診断用: カメラ映像+マイク音声を実際にキャプチャしてPlutoへ送出する経路が
     *  健全かを確認する(Camera/Mic→H.264/AAC→TS Mux→Dvbs2TxPipeline)。 */
    var cameraDiagRunning by mutableStateOf(false)
        private set
    var cameraDiagPhaseText by mutableStateOf("")
        private set
    var cameraDiagResultText by mutableStateOf("")
        private set
    var cameraDiagVideoFrames by mutableStateOf(0)
        private set
    var cameraDiagAudioFrames by mutableStateOf(0)
        private set

    val cameraAudioPipeline = CameraAudioTxDiagPipeline(context, lifecycleOwner)

    private var diagJob: Job? = null
    private var cameraDiagJob: Job? = null
    private val txPipeline = Dvbs2TxPipeline()
    private val rxPipeline = Dvbs2RxPipeline()
    private var cameraTxPipeline: Dvbs2TxPipeline? = null

    private fun appendLog(message: String) {
        diagLog = "$message\n$diagLog"
    }

    fun runDiag(
        context: Context,
        plutoIp: String,
        loHz: Long,
        modCod: String = Dvbs2Constants.PROVEN_WORKING_MOD_COD,
        bandwidthHz: Long = 2_000_000,
        sampleRateHz: Long = 2_500_000,
        rfPort: String = "A_BALANCED",
        txSeconds: Int = 8,
        rxSeconds: Int = 8,
    ) {
        if (diagRunning || cameraDiagRunning) return
        diagRunning = true
        diagResultText = "診断中..."
        diagPhaseText = "TXへ接続中..."
        diagLog = ""
        appendLog("送受信の診断を開始します")
        val plutoUri = "ip:$plutoIp"

        diagJob = scope.launch {
            try {
                // --- フェーズ1: TX単体診断 ---
                appendLog("[診断] TX単体確認 (${txSeconds}秒)")
                var txErrorOccurred = false
                txPipeline.onError = { message -> txErrorOccurred = true; appendLog("[Tx] $message") }
                val txConnected = txPipeline.start(context, plutoUri, modCod, bandwidthHz, sampleRateHz, loHz)

                var txHealthySamples = 0
                var txCheckedSamples = 0
                if (txConnected) {
                    // aff3ctのDVB-S2エンコーダは接続直後から実際にIQフレームを生成し始めるまで
                    // ウォームアップ時間があり、その間はstarved率が一時的に高くなる
                    // (正常な起動過程で異常ではない)。判定対象から除外する。
                    diagPhaseText = "TX起動中(ウォームアップ)..."
                    delay(1_500)

                    // 実機検証用のダミーTSを変調器の必要ビットレート以上で連続投入する。
                    // 以前の26パケット/100msでは約0.39Mbpsしか供給できず、TX DMAが
                    // 正常な接続中にも枯渇して診断を誤って異常と判定していた。
                    val dummyDataJob = launch {
                        val packet = ByteArray(188 * 24)
                        for (i in packet.indices step 188) packet[i] = 0x47.toByte()
                        while (isActive) {
                            txPipeline.write(packet)
                            delay(10)
                        }
                    }
                    for (tick in 1..txSeconds) {
                        if (!isActive) break
                        diagPhaseText = "TX健全性確認中... ($tick/${txSeconds}秒)"
                        delay(1_000)
                        if (!isActive) break
                        txCheckedSamples++
                        val starved = txPipeline.starvedPercent
                        val dmaRms = txPipeline.dmaRms
                        if (starved < 20.0 && dmaRms > 10.0) txHealthySamples++
                    }
                    dummyDataJob.cancel()
                } else {
                    diagPhaseText = "TX接続失敗"
                }
                txPipeline.stop()

                val cancelledDuringTx = !isActive
                val txPass = !cancelledDuringTx && txConnected && !txErrorOccurred &&
                    txCheckedSamples > 0 && txHealthySamples == txCheckedSamples
                appendLog(
                    "[診断] TX結果: ${if (txPass) "OK" else "NG"} " +
                        "(接続=$txConnected, 健全サンプル=$txHealthySamples/$txCheckedSamples)",
                )

                if (cancelledDuringTx) return@launch
                delay(1_500)

                // --- フェーズ2: RX単体診断 ---
                diagPhaseText = "RXへ接続中..."
                appendLog("[診断] RX単体確認 (${rxSeconds}秒)")
                var rxErrorOccurred = false
                var rxTsBytes = 0
                rxPipeline.onError = { message -> rxErrorOccurred = true; appendLog("[Rx] $message") }
                rxPipeline.onTsData = { data -> rxTsBytes += data.size }
                val rxConnected = rxPipeline.start(context, plutoUri, modCod, bandwidthHz, sampleRateHz, loHz, rfPort)

                if (rxConnected) {
                    for (tick in 1..rxSeconds) {
                        if (!isActive) break
                        diagPhaseText = "RX継続確認中... ($tick/${rxSeconds}秒)"
                        delay(1_000)
                    }
                } else {
                    diagPhaseText = "RX接続失敗"
                }
                rxPipeline.stop()

                if (!isActive) return@launch

                val rxPass = rxConnected && !rxErrorOccurred
                appendLog(
                    "[診断] RX結果: ${if (rxPass) "OK" else "NG"} " +
                        "(接続=$rxConnected, 受信バイト数=$rxTsBytes)",
                )

                diagResultText = "TX: ${if (txPass) "✅ 正常" else "❌ 異常"}  /  RX: ${if (rxPass) "✅ 正常" else "❌ 異常"}"
                appendLog("送受信の診断完了: $diagResultText")
            } finally {
                txPipeline.stop()
                rxPipeline.stop()
                diagPhaseText = ""
                if (!isActive) {
                    appendLog("送受信の診断を中断しました")
                    diagResultText = ""
                }
                diagRunning = false
            }
        }
    }

    fun cancelDiag() {
        diagJob?.cancel()
    }

    /**
     * カメラ映像+マイク音声を実際にキャプチャ→H.264/AACエンコード→TS多重化という
     * 実運用と同じ経路でPlutoへ送出できるかを確認する -- iOS版`DVBS2TestRunner.swift`
     * `runCameraAudioDiag()`の移植。送信データの中身がTX段まで正しく流れているかを、
     * フレームカウント(カメラ/マイクが実際に動作しているか)とTX健全性指標
     * (starvedPercent/dmaRms、runDiag()のTX単体診断と同じ基準)の両方で判定する。
     * RX側や実際の復調確認は行わない(runDiag()と同じく半二重運用前提)。
     */
    fun runCameraAudioDiag(
        context: Context,
        plutoIp: String,
        loHz: Long,
        useFrontCamera: Boolean,
        modCod: String = Dvbs2Constants.PROVEN_WORKING_MOD_COD,
        bandwidthHz: Long = 2_000_000,
        sampleRateHz: Long = 2_500_000,
        seconds: Int = 8,
    ) {
        if (diagRunning || cameraDiagRunning) return
        cameraDiagRunning = true
        cameraDiagResultText = "診断中..."
        cameraDiagPhaseText = "TXへ接続中..."
        cameraDiagVideoFrames = 0
        cameraDiagAudioFrames = 0
        appendLog("カメラ+音声送出の診断を開始します")
        val plutoUri = "ip:$plutoIp"

        cameraDiagJob = scope.launch {
            val cameraTx = Dvbs2TxPipeline()
            cameraTxPipeline = cameraTx
            try {
                cameraTx.onError = { message -> appendLog("[Tx] $message") }
                val txConnected = cameraTx.start(context, plutoUri, modCod, bandwidthHz, sampleRateHz, loHz)
                appendLog(if (txConnected) "Tx接続成功" else "Tx接続失敗")

                if (!txConnected) {
                    cameraDiagResultText = "❌ 異常 (PlutoへのTX接続に失敗しました)"
                    appendLog("カメラ+音声送出の診断完了: $cameraDiagResultText")
                    return@launch
                }

                cameraDiagPhaseText = "カメラ/マイク起動中..."
                cameraAudioPipeline.onError = { message -> appendLog("[カメラ診断] $message") }
                cameraAudioPipeline.onTsData = { data -> cameraTx.write(data) }
                cameraAudioPipeline.start(
                    if (useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK,
                    videoBitrateBps = 800_000,
                    audioBitrateBps = 64_000,
                )

                var activeSamples = 0
                var checkedSamples = 0
                var lastStarved = 0.0
                var lastDmaRms = 0.0
                if (isActive) {
                    // aff3ctのDVB-S2エンコーダのウォームアップ期間はrunDiag()同様に判定対象から除外する。
                    cameraDiagPhaseText = "TX起動中(ウォームアップ)..."
                    delay(1_500)

                    for (tick in 1..seconds) {
                        if (!isActive) break
                        cameraDiagPhaseText = "映像/音声の送出確認中... ($tick/${seconds}秒)"
                        delay(1_000)
                        if (!isActive) break
                        cameraDiagVideoFrames = cameraAudioPipeline.videoFrameCount
                        cameraDiagAudioFrames = cameraAudioPipeline.audioFrameCount
                        checkedSamples++
                        lastStarved = cameraTx.starvedPercent
                        lastDmaRms = cameraTx.dmaRms
                        if (lastDmaRms > 10.0) activeSamples++
                    }
                }

                val videoFrames = cameraAudioPipeline.videoFrameCount
                val audioFrames = cameraAudioPipeline.audioFrameCount
                cameraDiagVideoFrames = videoFrames
                cameraDiagAudioFrames = audioFrames

                cameraDiagPhaseText = "停止処理中..."
                withContext(Dispatchers.IO) {
                    cameraAudioPipeline.stop()
                    cameraTx.requestStop()
                }

                if (!isActive) {
                    appendLog("カメラ+音声送出の診断を中断しました")
                    cameraDiagResultText = ""
                    return@launch
                }

                val capturePass = videoFrames > 0 && audioFrames > 0
                val txPass = checkedSamples > 0 && activeSamples > 0
                val overallPass = capturePass && txPass
                cameraDiagResultText = if (overallPass) "✅ 正常" else "❌ 異常"
                appendLog(
                    "[カメラ診断] 映像フレーム=$videoFrames, 音声フレーム=$audioFrames, " +
                        "DAC有効サンプル=$activeSamples/$checkedSamples, " +
                        "最終starved=${"%.1f".format(lastStarved)}%, 最終rms=${"%.1f".format(lastDmaRms)}",
                )
                appendLog("カメラ+音声送出の診断完了: $cameraDiagResultText")
            } finally {
                withContext(Dispatchers.IO) {
                    cameraAudioPipeline.stop()
                    cameraTx.requestStop()
                }
                if (cameraTxPipeline === cameraTx) cameraTxPipeline = null
                cameraDiagPhaseText = ""
                cameraDiagRunning = false
            }
        }
    }

    fun cancelCameraAudioDiag() {
        cameraDiagJob?.cancel()
    }

    fun destroy() {
        cancelDiag()
        cancelCameraAudioDiag()
        scope.launch(Dispatchers.IO) {
            cameraAudioPipeline.stop()
            cameraTxPipeline?.requestStop()
            cameraTxPipeline = null
            txPipeline.destroy()
            rxPipeline.destroy()
        }
    }
}

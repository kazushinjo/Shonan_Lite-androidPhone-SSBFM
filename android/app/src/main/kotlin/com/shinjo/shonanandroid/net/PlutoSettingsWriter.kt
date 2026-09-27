package com.shinjo.shonanandroid.net

import com.shinjo.shonanandroid.core.AppSettings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plutoのsettings.txtを保持したまま、送信に必要な変調値を反映する。 */
object PlutoSettingsWriter {
    private val fields = listOf("callsign", "freq", "channel", "mode", "mod", "sr", "srselect", "fec", "pilots", "frame", "power", "rolloff", "pcrpts", "patperiod", "h265box", "codec", "sound", "audioinput", "remux", "trvlo", "trvloselect", "provname")

    suspend fun applyTxSettings(host: String, settings: AppSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(host.isNotBlank()) { "PlutoのIPアドレスが未設定です" }
            val merged = fetch(host).toMutableMap().apply {
                put("freq", "%.3f".format(settings.effectiveLoHz / 1_000_000.0))
                put("mode", "DVBS2")
                put("mod", settings.modulationScheme.label)
                put("sr", kotlin.math.round(settings.effectiveOperationalSymbolRateMsps * 1_000).toInt().toString())
                put("fec", settings.fecRate.plutoParameter)
                // ★gr-dvbs2rx(RX側dvbs2rx_bridge.cppのplsync_cc)はパイロットなしだと
                // 周波数/位相の再推定がフレーム先頭(SOF)のみに落ち、Normal Frame
                // (64800シンボル)につき1回まで更新頻度が低下する。PlutoSDR(AD9363)の
                // TCXOドリフトに対してこれでは補正が追いつかずロックを維持できない
                // (rpi-dvbs2-receiver-gui調査で確認済みの同一事象)。パイロットありなら
                // 1476シンボルに1回まで更新頻度が上がるため、常にOnを送る。
                put("pilots", "On")
                put("frame", "LongFrame")
                put("power", "%.1f".format(-settings.txPowerDb.toDouble()))
                put("rolloff", settings.dvbs2Rolloff.toString())
            }
            val body = fields.joinToString("&") { key -> "${enc(key)}=${enc(merged[key].orEmpty())}" }
            val connection = (URL("http://$host/save.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 5_000; readTimeout = 5_000; doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            connection.disconnect()
            check(status in 200..399) { "Pluto設定反映失敗 (HTTP $status)" }
        }
    }

    private fun fetch(host: String): Map<String, String> {
        val connection = (URL("http://$host/settings.txt").openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000 }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return text.lineSequence().mapNotNull { line ->
            val index = line.indexOf(' ')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
        }.toMap()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

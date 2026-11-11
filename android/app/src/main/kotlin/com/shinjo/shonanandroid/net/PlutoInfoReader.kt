package com.shinjo.shonanandroid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 設定画面の「Pluto 情報」(iPad/Mac版・Pi4/Pi5版と同じ内容)。どれも読み取りのみで、
 * Plutoには何も書き込まない。
 * - バージョン: iiod(TCP 30431)に`PRINT`を送り、コンテキストXMLのcontext-attributeを読む
 * - セッティング: Pluto Web UIの settings.txt(送信開始時にこのアプリが save.php で書き込む値)
 * - AD9361: iiodに`READ ad9361-phy [INPUT|OUTPUT] <チャンネル> <属性>`を送って17項目を読む
 */
object PlutoInfoReader {
    private const val IIOD_PORT = 30431
    private const val TIMEOUT_MS = 5000

    class PlutoInfoException(message: String) : Exception(message)

    private data class VersionEntry(val ja: String, val en: String, val key: String)

    private val versionEntries = listOf(
        VersionEntry("ファーム", "Firmware", "fw_version"),
        VersionEntry("型式", "Model", "hw_model"),
        VersionEntry("チップ設定", "Chip", "ad9361-phy,model"),
        VersionEntry("xo_correction", "xo_correction", "ad9361-phy,xo_correction"),
        VersionEntry("カーネル", "Kernel", "local,kernel"),
        VersionEntry("シリアル", "Serial", "hw_serial"),
    )

    /** channel=null ならデバイス属性。output はチャンネルの向き。mhz=true なら Hz を MHz 表記にする。 */
    private data class Ad9361Entry(
        val ja: String, val en: String, val channel: String?, val output: Boolean, val attr: String, val mhz: Boolean,
    )

    private val ad9361Entries = listOf(
        Ad9361Entry("ENSMモード", "ENSM mode", null, false, "ensm_mode", false),
        Ad9361Entry("キャリブレーション", "Calibration", null, false, "calib_mode", false),
        Ad9361Entry("xo_correction", "xo_correction", null, false, "xo_correction", false),
        Ad9361Entry("RX LO周波数", "RX LO frequency", "altvoltage0", true, "frequency", true),
        Ad9361Entry("RX LO停止", "RX LO powerdown", "altvoltage0", true, "powerdown", false),
        Ad9361Entry("RXサンプルレート", "RX sample rate", "voltage0", false, "sampling_frequency", true),
        Ad9361Entry("RX帯域", "RX bandwidth", "voltage0", false, "rf_bandwidth", true),
        Ad9361Entry("RXポート", "RX port", "voltage0", false, "rf_port_select", false),
        Ad9361Entry("RXゲイン制御", "RX gain control", "voltage0", false, "gain_control_mode", false),
        Ad9361Entry("RXゲイン", "RX gain", "voltage0", false, "hardwaregain", false),
        Ad9361Entry("RX RSSI", "RX RSSI", "voltage0", false, "rssi", false),
        Ad9361Entry("TX LO周波数", "TX LO frequency", "altvoltage1", true, "frequency", true),
        Ad9361Entry("TX LO停止", "TX LO powerdown", "altvoltage1", true, "powerdown", false),
        Ad9361Entry("TXサンプルレート", "TX sample rate", "voltage0", true, "sampling_frequency", true),
        Ad9361Entry("TX帯域", "TX bandwidth", "voltage0", true, "rf_bandwidth", true),
        Ad9361Entry("TXポート", "TX port", "voltage0", true, "rf_port_select", false),
        Ad9361Entry("TX減衰", "TX attenuation", "voltage0", true, "hardwaregain", false),
    )

    private fun connect(host: String): Socket {
        if (host.isBlank()) throw PlutoInfoException("PlutoのIPアドレスが未設定です")
        return try {
            Socket().apply {
                connect(InetSocketAddress(host.trim(), IIOD_PORT), TIMEOUT_MS)
                soTimeout = TIMEOUT_MS
            }
        } catch (e: Exception) {
            throw PlutoInfoException("Plutoへ接続できません(${host.trim()}): ${e.message}")
        }
    }

    suspend fun readVersion(host: String, english: Boolean): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val xml = connect(host).use { socket ->
            socket.getOutputStream().apply { write("PRINT\r\n".toByteArray()); flush() }
            val input = socket.getInputStream()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(65536)
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
                if (buffer.toString(Charsets.UTF_8.name()).contains("</context>")) break
            }
            buffer.toString(Charsets.UTF_8.name())
        }
        val attrs = parseContextAttributes(xml)
        if (attrs.isEmpty()) throw PlutoInfoException("Plutoのコンテキスト属性を読めませんでした")
        versionEntries.map { (if (english) it.en else it.ja) to (attrs[it.key] ?: "-") }
    }

    /** PRINT応答のXMLから <context-attribute name="..." value="..." /> を取り出す。 */
    fun parseContextAttributes(xml: String): Map<String, String> =
        Regex("""<context-attribute name="([^"]*)" value="([^"]*)"""").findAll(xml)
            .associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }

    private fun xmlUnescape(text: String): String =
        text.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    suspend fun readAd9361(host: String, english: Boolean): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        connect(host).use { socket ->
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            ad9361Entries.map { entry ->
                val command = if (entry.channel == null) {
                    "READ ad9361-phy ${entry.attr}"
                } else {
                    "READ ad9361-phy ${if (entry.output) "OUTPUT" else "INPUT"} ${entry.channel} ${entry.attr}"
                }
                out.write("$command\r\n".toByteArray()); out.flush()
                val value = readIiodValue(input)
                (if (english) entry.en else entry.ja) to formatValue(value, entry.mhz)
            }
        }
    }

    /** iiodのREAD応答(「長さ\n」+値+「\n」、長さが負ならエラー)を1つ読む。エラーならnull。 */
    private fun readIiodValue(input: InputStream): String? {
        val length = readLine(input).trim().toIntOrNull() ?: throw PlutoInfoException("Plutoの応答が読めません")
        if (length < 0) return null
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) throw PlutoInfoException("Plutoの応答が途中で切れました")
            read += n
        }
        readLine(input)  // 値の後ろの改行
        return String(data, Charsets.UTF_8).trimEnd('\u0000').trim()
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    fun formatValue(value: String?, mhz: Boolean): String {
        if (value.isNullOrEmpty()) return "-"
        if (!mhz) return value
        val hz = value.toDoubleOrNull() ?: return value
        return String.format(java.util.Locale.US, "%.3f MHz", hz / 1_000_000)
    }

    suspend fun readSettingsTxt(host: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (host.isBlank()) throw PlutoInfoException("PlutoのIPアドレスが未設定です")
        val connection = try {
            (URL("http://${host.trim()}/settings.txt").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
            }
        } catch (e: Exception) {
            throw PlutoInfoException("Plutoへ接続できません: ${e.message}")
        }
        try {
            val code = connection.responseCode
            if (code == 404) throw PlutoInfoException("settings.txtがありません(Pluto起動後、まだ送信していません)")
            if (code !in 200..299) throw PlutoInfoException("HTTPエラー $code")
            parseSettingsTxt(connection.inputStream.bufferedReader().readText())
        } catch (e: PlutoInfoException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw PlutoInfoException("settings.txtがありません(Pluto起動後、まだ送信していません)")
        } catch (e: Exception) {
            throw PlutoInfoException("Plutoへ接続できません: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /** settings.txt(1行に「キー 値」)を(キー, 値)の並びにする(iPad版と同じ)。 */
    fun parseSettingsTxt(text: String): List<Pair<String, String>> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val space = line.indexOf(' ')
            if (space < 0) line to "" else line.substring(0, space) to line.substring(space + 1)
        }
}

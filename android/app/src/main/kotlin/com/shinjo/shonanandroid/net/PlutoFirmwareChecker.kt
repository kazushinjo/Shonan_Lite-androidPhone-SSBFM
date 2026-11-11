package com.shinjo.shonanandroid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plutoのファームの版を、iiod(TCP 30431)のテキストプロトコルで読む(iOS版PlutoVersionCheckerと同じ方法)。
 * `PRINT`を送ると返ってくるコンテキストXMLから、context-attributeのfw_versionを取り出す。
 */
object PlutoFirmwareChecker {
    private const val IIOD_PORT = 30431

    /** fw_versionを返す。つながらない・読めないときはnull。 */
    suspend fun fetchFirmware(host: String, timeoutMs: Int = 4000): String? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext null
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host.trim(), IIOD_PORT), timeoutMs)
                socket.soTimeout = timeoutMs
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
                Regex("""name="fw_version" value="([^"]*)"""").find(buffer.toString(Charsets.UTF_8.name()))?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    /** 16APSK・32APSKを送れるファームか(Pluto+のFPGA版ファームはfw_versionに"FPGA"を含む)。読めなければnull。 */
    suspend fun isApskCapable(host: String): Boolean? = fetchFirmware(host)?.contains("FPGA")
}

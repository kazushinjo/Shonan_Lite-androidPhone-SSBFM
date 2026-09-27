package com.shinjo.shonanandroid.net

import android.content.Context
import android.net.ConnectivityManager
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plutoの自動検出。2段構えで探す:
 *  1. 端末自身が接続中WiFiのIP/サブネットマスクから同一セグメント内をスキャンし、
 *     "GET /pluto.php"に200応答するホストを探す(一般的なWiFiルーター運用でも動作する)。
 *  2. それで見つからなければ、ESP32ブリッジ(hardware/ESP32_WiFi_Ethernet_Bridge)の
 *     Pluto自動検出API(GET http://192.168.0.1/pluto_ip)にフォールバックする
 *     (ブリッジ側は既知候補+フルスキャンでより気長に探すため保険として残す)。
 */
object PlutoDiscoveryClient {
    private const val DEFAULT_BRIDGE_HOST = "192.168.0.1"
    private const val CONNECT_TIMEOUT_MS = 300
    private const val READ_TIMEOUT_MS = 1_000
    /** 大規模ネットワーク(/23より広い)は走査対象が多すぎるためスキャンしない。 */
    private const val MAX_SCAN_HOST_BITS = 9

    suspend fun discoverPlutoIp(context: Context, bridgeHost: String = DEFAULT_BRIDGE_HOST): String? {
        scanLocalSubnetForPluto(context)?.let { return it }
        return discoverViaBridge(bridgeHost)
    }

    /** 端末の接続中ネットワークと同一サブネットをスキャンしてPlutoを探す。 */
    private suspend fun scanLocalSubnetForPluto(context: Context): String? = withContext(Dispatchers.IO) {
        val (ip, prefixLength) = getLocalIpv4AndPrefix(context) ?: return@withContext null
        val hosts = hostsInSubnet(ip, prefixLength) ?: return@withContext null
        if (hosts.isEmpty()) return@withContext null
        coroutineScope {
            hosts.map { host -> async { if (probePlutoHttp(host)) host else null } }
                .awaitAll()
                .firstOrNull { it != null }
        }
    }

    /** ESP32ブリッジのPluto自動検出API(GET /pluto_ip)へフォールバック問い合わせ。 */
    private suspend fun discoverViaBridge(bridgeHost: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("http://$bridgeHost/pluto_ip").openConnection() as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 45_000
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optBoolean("found", false)) {
                json.optString("pluto_ip").takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
    }

    /** 接続中ネットワークのIPv4アドレスとプレフィックス長(サブネットマスク)を取得する。 */
    private fun getLocalIpv4AndPrefix(context: Context): Pair<String, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProperties = cm.getLinkProperties(network) ?: return null
        val linkAddress = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val hostAddress = linkAddress.address.hostAddress ?: return null
        return hostAddress to linkAddress.prefixLength
    }

    /** サブネット内の全ホストIP(自分自身・ネットワークアドレス・ブロードキャストを除く)を列挙する。 */
    private fun hostsInSubnet(ipStr: String, prefixLength: Int): List<String>? {
        val hostBits = 32 - prefixLength
        if (hostBits < 1 || hostBits > MAX_SCAN_HOST_BITS) {
            return null // 広すぎるネットワーク(/23より広い)はスキャンしない
        }
        val ipInt = ipToInt(ipStr) ?: return null
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val network = ipInt and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast)
            .filter { it != ipInt }
            .map { intToIp(it) }
    }

    private fun ipToInt(ip: String): Int? {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun intToIp(value: Int): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    private fun probePlutoHttp(ip: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, 80), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().write(
                "GET /pluto.php HTTP/1.0\r\nHost: pluto\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            val statusLine = socket.getInputStream().bufferedReader().readLine() ?: return@use false
            statusLine.contains(" 200 ")
        }
    }.getOrDefault(false)
}

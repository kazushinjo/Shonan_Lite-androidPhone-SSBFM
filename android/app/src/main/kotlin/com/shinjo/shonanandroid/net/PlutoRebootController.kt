package com.shinjo.shonanandroid.net

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

/** libiio IIOD(オンデバイス復調が直接TCP接続する)の既定ポート。 */
private const val IIOD_PORT = 30431

object PlutoRebootController {
    /** 起動時の再起動要求。20秒以内に要求が完了しなければ起動を続行する。 */
    suspend fun rebootAtStartup(host: String, timeoutMillis: Long = 20_000L): Boolean {
        if (host.isBlank()) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            reboot(host).isSuccess
        } ?: false
    }

    suspend fun reboot(host: String): Result<Unit> = runCatching {
        require(host.isNotBlank()) { "PlutoのIPアドレスが未設定です" }
        PlutoSshCrypto.ensureRegistered()
        val client = SSHClient()
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = 5_000
        client.timeout = 5_000
        client.connect(host)
        try {
            client.authPassword("root", "analog")
            client.startSession().use { session ->
                session.exec("reboot")
            }
        } finally {
            client.disconnect()
        }
    }

    /**
     * 再起動後、Web UI(pluto.php)とiiod(libiio、TCP 30431)の両方が応答を再開するまで
     * ポーリングする -- iPad版`PlutoRebootController.waitUntilOnline`と同じ基準(iOS版の
     * コメント: Web UIはiiodより先に起動することがあり、Web UIの応答だけで「起動完了」と
     * 判定するとオンデバイス復調RXがまだiiodへ接続できず失敗するため、両方の確認が揃うまで待つ)。
     */
    suspend fun waitUntilOnline(host: String, timeoutMillis: Long = 90_000L, onAttempt: (Int) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        Thread.sleep(5_000)
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            onAttempt(attempt)
            val webOk = runCatching {
                val connection = URL("http://$host/pluto.php").openConnection() as HttpURLConnection
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.responseCode in 200..299
            }.getOrDefault(false)
            if (webOk && canConnectTcp(host, IIOD_PORT, timeoutMs = 3_000)) return true
            Thread.sleep(2_000)
        }
        return false
    }

    private fun canConnectTcp(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)
}

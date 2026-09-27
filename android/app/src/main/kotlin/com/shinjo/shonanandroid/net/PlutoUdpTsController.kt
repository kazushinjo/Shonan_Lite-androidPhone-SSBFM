package com.shinjo.shonanandroid.net

import android.content.Context
import com.shinjo.shonanandroid.diagnostics.FileLogger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

object PlutoUdpTsController {
    /** 設定反映後、停止しているPluto標準UDP-TS入力経路を再起動する。 */
    suspend fun restart(host: String, context: Context): Result<Unit> =
        runRemote(host, context, "killall udpts.sh pluto_dvb tsp 2>/dev/null || true; sleep 1; nohup /root/udpts.sh >/dev/null 2>&1 &")

    /**
     * Pluto標準UDP-TS入力経路(pluto_dvb)を止め、TX DMA(cf-ad9361-dds-core-lpc)を空ける。
     * SSB/FMタブがlibiioで直接IQを送るために使う(DATV送信の開始時は[restart]で再起動される)。
     */
    suspend fun stop(host: String, context: Context): Result<Unit> =
        runRemote(host, context, "killall udpts.sh pluto_dvb tsp 2>/dev/null || true; sleep 1")

    private suspend fun runRemote(host: String, context: Context, command: String): Result<Unit> = withContext(Dispatchers.IO) {
        // Androidタブレット端末でのみSSH(22)だけがタイムアウトする事例があったため、
        // SSHハンドシェイクに入る前に素のTCP到達性(SYN/ACK)を計測して切り分ける。
        val tcpStart = System.currentTimeMillis()
        val tcpReachable = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, 22), 5_000) }
        }.isSuccess
        FileLogger.log("SSH_TCP", "raw TCP connect to $host:22 reachable=$tcpReachable elapsedMs=${System.currentTimeMillis() - tcpStart}")

        // HTTPURLConnection(port 80)は常に成功するのに、生のjava.net.Socketだと
        // SSH(port 22)だけ読み取りがタイムアウトする。「port 22固有」なのか「アプリ内での
        // 生Socket読み取り全般」の問題なのかを切り分けるため、port 80に対しても
        // HTTPURLConnectionを使わず生Socketで同じ読み取りパターンを試す。
        val rawHttpStart = System.currentTimeMillis()
        val rawHttpResult = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 80), 5_000)
                socket.soTimeout = 8_000
                socket.getOutputStream().apply {
                    write("GET / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().read()
            }
        }
        FileLogger.log(
            "RAW_HTTP_TEST",
            "raw socket read from $host:80 result=$rawHttpResult elapsedMs=${System.currentTimeMillis() - rawHttpStart}"
        )

        // JSch(com.github.mwiede:jsch)では、TCP接続自体はTermux/adb shell経由のncと
        // 同じく即成立するのに、直後のSSHバナー読み取り(java.net.Socket.getInputStream()
        // .read())だけがこのアプリのプロセス内で無応答のままタイムアウトする現象が現地の
        // Androidタブレットで確認された。原因を特定できなかったため、Android実績の多い
        // 別実装(sshj)へ切り替える。
        WifiPerformanceLock.withHighPerf(context) {
            var lastResult: Result<Unit>? = null
            for (attempt in 1..2) {
                FileLogger.log("SSH_ATTEMPT", "attempt=$attempt")
                // PlutoのdropbearはSSHバナー/KEXINITの応答に30秒近くかかることがある
                // (再起動直後のエントロピー不足等が要因と見られる)。iPad版が繋がるのに
                // Android版だけ失敗していたのは、この遅延に対しタイムアウトが短すぎた
                // (12秒)ためで、ネットワーク到達性自体は正常だった。
                val result = withTimeoutOrNull(45_000) {
                    runCatching {
                        PlutoSshCrypto.ensureRegistered()
                        val client = SSHClient()
                        client.addHostKeyVerifier(PromiscuousVerifier())
                        client.connectTimeout = 40_000
                        client.timeout = 40_000
                        FileLogger.log("SSHJ", "connecting to $host:22")
                        client.connect(host)
                        FileLogger.log("SSHJ", "connected, remote version=${client.transport.serverVersion}")
                        try {
                            client.authPassword("root", "analog")
                            FileLogger.log("SSHJ", "authenticated")
                            client.startSession().use { session ->
                                FileLogger.log("SSHJ", "exec: $command")
                                session.exec(command)
                                session.join()
                            }
                        } finally {
                            client.disconnect()
                        }
                    }
                } ?: Result.failure(TimeoutException("SSH接続が45秒以内に完了しませんでした"))
                lastResult = result
                if (result.isSuccess) break
                FileLogger.log("SSH_ATTEMPT", "attempt=$attempt failed: ${result.exceptionOrNull()}")
                if (attempt < 2) delay(1_000)
            }
            lastResult!!
        }
    }
}

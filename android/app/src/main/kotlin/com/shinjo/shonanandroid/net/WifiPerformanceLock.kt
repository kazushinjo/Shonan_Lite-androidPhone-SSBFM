package com.shinjo.shonanandroid.net

import android.content.Context
import android.net.wifi.WifiManager
import com.shinjo.shonanandroid.diagnostics.FileLogger

/**
 * PlutoへのSSH(JSch)接続だけが、素のTCP接続は即成立するのに直後のSSHバナー交換で
 * 無応答のままタイムアウトする事例が特定のAndroidタブレットで発生した。同じ端末の
 * Termux(ネイティブOpenSSH)からは問題なく繋がったため、JavaのSocket API使用時に
 * Wi-Fiチップの省電力機能(接続確立後の短い無通信区間でスリープする挙動)が影響して
 * いる可能性を疑い、SSH処理中だけ高パフォーマンスモードで固定する。
 */
object WifiPerformanceLock {
    private var lock: WifiManager.WifiLock? = null

    suspend fun <T> withHighPerf(context: Context, block: suspend () -> T): T {
        acquire(context)
        try {
            return block()
        } finally {
            release()
        }
    }

    private fun acquire(context: Context) {
        runCatching {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val newLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShonanLite:PlutoSsh")
            newLock.setReferenceCounted(false)
            newLock.acquire()
            lock = newLock
            FileLogger.log("WIFI_LOCK", "acquired high-perf lock")
        }.onFailure { FileLogger.log("WIFI_LOCK", "acquire failed: ${it.message}") }
    }

    private fun release() {
        runCatching {
            lock?.release()
            lock = null
            FileLogger.log("WIFI_LOCK", "released")
        }
    }
}

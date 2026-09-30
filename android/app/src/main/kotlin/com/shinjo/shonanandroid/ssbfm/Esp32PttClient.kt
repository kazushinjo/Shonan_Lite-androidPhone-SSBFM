package com.shinjo.shonanandroid.ssbfm

import java.net.HttpURLConnection
import java.net.URL

/**
 * PA/PTTコントローラ(ESP32+W5500、W5500_PA_PTT_Control)へのHTTP通知。
 *
 * ファームウェアは hardware/W5500_PA_PTT_Control(Pi5版と共通)で、Pi5版アプリと同じAPIを使う:
 * - `GET /tx?state=on|off` … 送信開始/終了。ESP32はPTT(GPIO27)だけを切り替える(12V電源には触れない)
 * - `GET /ch?idx=0&state=on|off` … 12V電源(GPIO26、Pluto含む)のON/OFF
 * 呼び出しはブロッキングなのでメインスレッドから呼ばないこと。
 */
object Esp32PttClient {
    /** ファームウェアのチャンネル番号(0=POWER=12V電源、1=PTT)。 */
    const val CHANNEL_POWER = 0

    private const val TIMEOUT_MS = 1000

    /** 送信開始(on=true)/終了を通知する。応答が得られなければ[attempts]回まで試す。 */
    fun notifyTx(host: String, on: Boolean, attempts: Int = 2): Result<String> {
        var last: Result<String> = Result.failure(IllegalStateException("not attempted"))
        repeat(attempts) {
            last = get(host, "/tx?state=${if (on) "on" else "off"}")
            if (last.isSuccess) return last
        }
        return last
    }

    /** 個別チャンネルを明示的にON/OFFする。 */
    fun setChannel(host: String, idx: Int, on: Boolean): Result<String> =
        get(host, "/ch?idx=$idx&state=${if (on) "on" else "off"}")

    /** 接続確認(`GET /api/status`)。 */
    fun isReachable(host: String): Boolean = get(host, "/api/status").isSuccess

    private fun get(host: String, path: String): Result<String> = runCatching {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            check(code in 200..299) { "HTTP $code" }
            body
        } finally {
            conn.disconnect()
        }
    }
}

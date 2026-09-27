package com.shinjo.shonanandroid.ssbfm

import java.net.HttpURLConnection
import java.net.URL

/**
 * PA/PTTコントローラ(ESP32+W5500、W5500_PA_PTT_Control)へのHTTP通知。
 *
 * ファームウェアは版によってピン割当てや個別API(/ptt・/power・/ch)が異なるが、
 * `GET /tx?state=on|off`(送信開始/終了の通知。LNA・PTT・PAの切替順序はESP32側が持つ)は
 * Pi4版・Pi5版・このリポジトリ版のいずれにもあり、Pi5版アプリもこれを使っているため
 * これだけを使う。呼び出しはブロッキングなのでメインスレッドから呼ばないこと。
 */
object Esp32PttClient {
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

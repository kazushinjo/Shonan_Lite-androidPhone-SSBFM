package com.shinjo.shonanandroid.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * アプリの全通信(Pluto/ルーターとのUDP・TCP)を必ずWi-Fi経由にする。
 * ★モバイルデータ通信がオンの端末では、Androidが端末全体のデフォルト経路を
 * モバイル回線に切り替えてしまうことがあり、その状態でPlutoの192.168.0.x宛て
 * 通信を送るとルーティングに失敗しうる(実機依存)。プロセスをWi-Fiネットワークへ
 * 明示的にbindすることで、モバイルデータがオンでも常にWi-Fi経由で通信させる。
 */
object WifiNetworkBinder {
    private const val TAG = "WifiNetworkBinder"

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun bindToWifi(context: Context) {
        if (callback != null) return
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val ok = connectivityManager.bindProcessToNetwork(network)
                Log.i(TAG, "Wi-Fiネットワークにバインド: success=$ok")
            }

            override fun onLost(network: Network) {
                // ★Wi-Fiが切れた場合、bindProcessToNetwork(null)でシステム既定に戻す
                // (モバイルデータのみになっても通信自体は継続できるようにする)。
                connectivityManager.bindProcessToNetwork(null)
                Log.i(TAG, "Wi-Fiネットワークを切断、バインド解除")
            }
        }
        callback = cb
        connectivityManager.requestNetwork(request, cb)
    }
}

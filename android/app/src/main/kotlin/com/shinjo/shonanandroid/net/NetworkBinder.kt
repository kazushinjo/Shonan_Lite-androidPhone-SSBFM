package com.shinjo.shonanandroid.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.shinjo.shonanandroid.diagnostics.FileLogger

/**
 * Plutoはインターネット未接続のWi-Fi(現地APやモバイルホットスポット等)経由で
 * 使われることが多い。AndroidはNET_CAPABILITY_INTERNET未検証のWi-Fiをデフォルト
 * 経路として使わないことがあり、その状態だとアプリの素のSocket/DatagramSocket/
 * JSch通信がすべてタイムアウトする(Pluto UDP受信経路起動失敗やIIOD接続失敗の原因)。
 * そのためインターネット到達性を問わずWi-Fiを明示要求し、プロセス全体を
 * bindProcessToNetworkで固定する。
 */
object NetworkBinder {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun bindToWifi(context: Context) {
        if (callback != null) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                FileLogger.log("NET", "bindProcessToNetwork: $network")
            }
            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                FileLogger.log("NET", "network lost, unbind: $network")
            }
        }
        // CHANGE_NETWORK_STATE未付与の端末やOEM制限でrequestNetworkが例外を投げても
        // アプリ起動自体は継続させる(バインドできない場合は従来動作にフォールバック)。
        runCatching {
            callback = cb
            cm.requestNetwork(request, cb)
        }.onFailure {
            callback = null
            FileLogger.log("NET", "requestNetwork failed: ${it.message}")
        }
    }
}

package com.shinjo.shonanandroid

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.shinjo.shonanandroid.diagnostics.FileLogger
import com.shinjo.shonanandroid.net.WifiNetworkBinder
import com.shinjo.shonanandroid.ui.ShonanNavHost

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    // 権限が拒否された場合の失敗は、実際にカメラ/マイクを使う各コントローラ
    // (CameraCapture/AudioCapture)側のエラーコールバックに委ねる。
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        WifiNetworkBinder.bindToWifi(applicationContext)
        requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        setContent {
            MaterialTheme {
                Surface {
                    ShonanNavHost(viewModel)
                }
            }
        }
    }
}

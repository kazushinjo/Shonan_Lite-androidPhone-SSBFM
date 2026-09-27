package com.shinjo.shonanandroid.ui

import com.shinjo.shonanandroid.core.AppLanguage

/** Converts legacy runtime messages to English when the app is in English mode. */
internal fun runtimeText(text: String, language: AppLanguage): String {
    if (language != AppLanguage.ENGLISH) return text
    val translated = text
        .replace("受信中は送信を開始できません。受信を停止してください。", "Cannot start TX while RX is active. Stop RX first.")
        .replace("送信中は受信を開始できません。送信を停止してください。", "Cannot start RX while TX is active. Stop TX first.")
        .replace("Plutoの周波数設定に失敗しました", "Failed to configure Pluto frequency")
        .replace("Pluto変調設定の反映に失敗しました", "Failed to apply Pluto modulation settings")
        .replace("Pluto UDP受信経路の起動に失敗しました", "Failed to start the Pluto UDP receive path")
        .replace("待機中", "Ready")
        .replace("Plutoへ接続中", "Connecting to Pluto")
        .replace("測定中", "Measuring")
        .replace("停止しました", "Stopped")
        .replace("停止中", "Stopping")
        .replace("エラー", "Error")
        .replace("周波数を変更していません", "frequency was not changed")
        .replace("最良周波数", "best frequency")
        .replace("を設定しました", " applied")
        .replace("デコーダの初期化に失敗しました", "Failed to initialize decoder")
        .replace("オンデバイス復調の初期化に失敗しました", "Failed to initialize on-device demodulation")
        .replace("オンデバイス復調を開始できませんでした", "Could not start on-device demodulation")
        .replace("UDP受信の開始に失敗しました", "Failed to start UDP receiver")
        .replace("UDP受信エラー", "UDP receive error")
        .replace("UDP送出先への接続に失敗しました", "Failed to connect to UDP destination")
        .replace("UDP送出に失敗しました", "UDP transmission failed")
        .replace("MediaCodec初期化に失敗しました", "Failed to initialize MediaCodec")
        .replace("対応H.264エンコーダがありません", "No supported H.264 encoder is available")
        .replace("カメラの初期化に失敗しました", "Failed to initialize camera")
        .replace("マイクへのアクセスが許可されていません", "Microphone access is not permitted")
        .replace("AudioRecordの初期化に失敗しました", "Failed to initialize AudioRecord")
        .replace("フレーム変換に失敗しました", "Failed to convert frame")
        .replace("写真が選択されていません", "No photo is selected")
        .replace("未対応のMODCODです", "Unsupported MODCOD")
        .replace("サポート済み", "Supported")
    return if (translated.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }) {
        "Operation failed. See the diagnostic log for details."
    } else {
        translated
    }
}

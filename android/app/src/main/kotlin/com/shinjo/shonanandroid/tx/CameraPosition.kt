package com.shinjo.shonanandroid.tx

/**
 * 前面/背面カメラ選択。androidx.camera.core.CameraSelectorに直接依存しないための
 * プレーンなenum(iOS版`CameraPosition.swift`相当)。
 */
enum class CameraPosition {
    FRONT, BACK;

    val displayName: String
        get() = if (this == FRONT) "インカメラ" else "アウトカメラ"
}

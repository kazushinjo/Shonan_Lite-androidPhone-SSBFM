package com.shinjo.shonanandroid.dvbs2

import android.content.Context
import java.io.File

/**
 * 機器試験(Diagnostic)機能の実行前セットアップ -- iOS版`DVBS2WorkingDirectory.swift`の移植。
 * aff3ct/dvbs2が相対パス("../conf/mod/...")で参照する`conf/`をAssetsから書き込み可能な
 * `filesDir`配下へコピーし、ネイティブ側(dvbs2_bridge.cpp)がchdirする`cwd/`を用意する。
 */
object Dvbs2Native {
    private const val ASSET_CONF_DIR = "dvbs2_conf"

    /** [Dvbs2TxPipeline]/[Dvbs2RxPipeline]の`start()`より前に一度呼んでおくこと。 */
    fun ensureWorkingDirectory(context: Context) {
        val base = File(context.filesDir, "dvbs2_runtime")
        val confDest = File(base, "conf")
        val cwd = File(base, "cwd")
        cwd.mkdirs()

        // ★過去にconf/コピーが不完全な状態で読み込みクラッシュした事例(iOS版と同じ)を踏まえ、
        // 代表ファイルの実在を確認できなければ毎回コピーし直す。
        val modCheck = File(confDest, "mod/4QAM_GRAY.mod")
        if (!modCheck.exists()) {
            confDest.deleteRecursively()
            copyAssetDir(context, ASSET_CONF_DIR, confDest)
        }
    }

    /** [ensureWorkingDirectory]が用意したcwdのパス(dvbs2_bridge.cppのchdir先と一致させる)。 */
    fun cwdPath(context: Context): String = File(context.filesDir, "dvbs2_runtime/cwd").path

    /** FIFO(named pipe)を作成する一時ディレクトリ。アプリのキャッシュ領域を使う。 */
    fun tmpDirPath(context: Context): String {
        val dir = File(context.cacheDir, "dvbs2_fifo")
        dir.mkdirs()
        return dir.path
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = context.assets.list(assetPath) ?: return
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val subChildren = context.assets.list(childAssetPath)
            if (!subChildren.isNullOrEmpty()) {
                copyAssetDir(context, childAssetPath, childDest)
            } else {
                context.assets.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

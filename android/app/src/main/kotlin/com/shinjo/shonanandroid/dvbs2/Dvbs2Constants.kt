package com.shinjo.shonanandroid.dvbs2

/**
 * オンデバイスDVB-S2処理(dvbs2_bridge.cpp、aff3ct/dvbs2由来)で使う定数。
 *
 * ★根本原因(iOS版Shonanで2026-08-05に静的解析で特定、Android版も同一の
 * third_party/dvbs2-deps-install-arm64-v8a/conf を使うため同じ制約を受ける):
 * conf/mod配下の .mod ファイル(変調方式別)は`4QAM_GRAY`(QPSK)/`8PSK`/`16APSK`のみ同梱で、
 * **32APSK用の.modファイルは存在しない**。conf/src配下の K_*.src ファイル(FEC別)は
 * `K_9552.src`(≒FEC3/5)と`K_14232.src`(≒FEC8/9)の2つしかなく、他のFEC
 * (1/4,1/3,2/5,1/2,2/3,3/4,4/5,5/6,9/10)用ファイルは存在しない。
 * 実際に使えるMODCODはこの5通りのみで、それ以外(例: "QPSK-S_1/2")を渡すと
 * ネイティブ側でSIGSEGVクラッシュする(iOS版で実機確認済み)。
 */
object Dvbs2Constants {
    /** 実機で動作実績のある既定MODCOD。 */
    const val PROVEN_WORKING_MOD_COD = "QPSK-S_3/5"

    /**
     * aff3ctバイナリの静的解析で確認した、実際に動作しうるMODCODのうち運用対象のもの。
     * 16APSK-S_8/9も動作しうるが、16APSKは変調方式の選択肢から削除したため除外する。
     */
    val VALID_MOD_CODS: Set<String> = setOf(
        "QPSK-S_3/5", "QPSK-S_8/9",
        "8PSK-S_3/5", "8PSK-S_8/9",
    )

    /** modCodが実機で動作しうる組み合わせかを判定する。 */
    fun isSupported(modCod: String): Boolean = VALID_MOD_CODS.contains(modCod)
}

package com.shinjo.shonanandroid.net

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * AndroidのAOSP組み込み"BC"プロバイダはX25519鍵交換を提供しておらず、sshjの
 * ハンドシェイクが"no such algorithm: X25519 for provider BC"で失敗する。
 * SSHClientを生成する前に必ずensureRegistered()を呼び、フルのBouncy Castleを
 * "BC"の名前で優先登録してから接続すること。
 */
internal object PlutoSshCrypto {
    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    fun ensureRegistered() {
        // initブロックを一度だけ走らせるためのトリガー。
    }
}

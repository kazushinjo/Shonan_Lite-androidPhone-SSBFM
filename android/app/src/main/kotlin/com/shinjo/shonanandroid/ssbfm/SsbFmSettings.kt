package com.shinjo.shonanandroid.ssbfm

import android.content.Context

/** 変調方式。[nativeId]は`ssbfm_dsp.h`のMode列挙と一致させる(LSBは使わないので置かない)。 */
enum class SsbFmMode(val nativeId: Int, val label: String) {
    USB(0, "USB"),
    FM(2, "FM"),
}

/** SSB/FMタブの設定。DATV側の[com.shinjo.shonanandroid.core.AppSettings]とは独立に保存する。 */
data class SsbFmSettings(
    val frequencyHz: Long = 433_000_000L,
    val mode: SsbFmMode = SsbFmMode.FM,
    val stepHz: Long = 1_000L,
    /** 0〜1(ネイティブ側で2乗カーブにする)。 */
    val afGain: Float = 0.5f,
    /** Sメーターと同じ相対dB。[SQUELCH_OFF]以下は常時開放。 */
    val squelchDb: Float = SQUELCH_OFF,
    val micGain: Float = 1.0f,
    val rxAgc: Boolean = true,
    val rxGainDb: Float = 50f,
    /** Plutoの送信減衰量(0で最大出力)。 */
    val txAttenuationDb: Float = 10f,
    /** trueならPTTボタンが押す度に送信/受信を切り替える。 */
    val pttLatch: Boolean = false,
) {
    companion object {
        const val SQUELCH_OFF = -200f
        const val MIN_FREQUENCY_HZ = 70_000_000L
        const val MAX_FREQUENCY_HZ = 6_000_000_000L
    }
}

object SsbFmSettingsStore {
    private const val PREFS_NAME = "ssbfm_settings"

    fun load(context: Context): SsbFmSettings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val d = SsbFmSettings()
        return SsbFmSettings(
            frequencyHz = p.getLong("frequencyHz", d.frequencyHz),
            // 以前保存された"LSB"はUSBとして読み込む。
            mode = when (val saved = p.getString("mode", d.mode.name)) {
                "LSB" -> SsbFmMode.USB
                else -> runCatching { SsbFmMode.valueOf(saved!!) }.getOrDefault(d.mode)
            },
            stepHz = p.getLong("stepHz", d.stepHz),
            afGain = p.getFloat("afGain", d.afGain),
            squelchDb = p.getFloat("squelchDb", d.squelchDb),
            micGain = p.getFloat("micGain", d.micGain),
            rxAgc = p.getBoolean("rxAgc", d.rxAgc),
            rxGainDb = p.getFloat("rxGainDb", d.rxGainDb),
            txAttenuationDb = p.getFloat("txAttenuationDb", d.txAttenuationDb),
            pttLatch = p.getBoolean("pttLatch", d.pttLatch),
        )
    }

    fun save(context: Context, s: SsbFmSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("frequencyHz", s.frequencyHz)
            .putString("mode", s.mode.name)
            .putLong("stepHz", s.stepHz)
            .putFloat("afGain", s.afGain)
            .putFloat("squelchDb", s.squelchDb)
            .putFloat("micGain", s.micGain)
            .putBoolean("rxAgc", s.rxAgc)
            .putFloat("rxGainDb", s.rxGainDb)
            .putFloat("txAttenuationDb", s.txAttenuationDb)
            .putBoolean("pttLatch", s.pttLatch)
            .apply()
    }
}

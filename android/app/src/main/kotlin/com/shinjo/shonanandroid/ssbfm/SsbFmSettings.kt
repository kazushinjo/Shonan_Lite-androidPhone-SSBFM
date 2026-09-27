package com.shinjo.shonanandroid.ssbfm

import android.content.Context

/** 変調方式。[nativeId]は`ssbfm_dsp.h`のMode列挙と一致させる(LSBは使わないので置かない)。 */
enum class SsbFmMode(val nativeId: Int, val label: String) {
    USB(0, "USB"),
    FM(2, "FM"),
}

/**
 * SSB/FMタブのバンド。範囲は国内のアマチュアバンド割当て。[defaultHz]/[defaultMode]は
 * そのバンドを初めて選んだときの値で、以後はバンドごとに最後の周波数・モードを覚える。
 */
enum class SsbFmBand(val label: String, val lowHz: Long, val highHz: Long, val defaultHz: Long, val defaultMode: SsbFmMode) {
    G1_2("1.2G", 1_260_000_000L, 1_300_000_000L, 1_295_000_000L, SsbFmMode.FM),
    G2_4("2.4G", 2_400_000_000L, 2_450_000_000L, 2_427_000_000L, SsbFmMode.FM),
    G5_6("5.6G", 5_650_000_000L, 5_850_000_000L, 5_760_000_000L, SsbFmMode.FM),
    ;

    companion object {
        fun of(hz: Long): SsbFmBand? = entries.firstOrNull { hz in it.lowHz..it.highHz }
    }
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
        /** 周波数表示の最下位桁(1kHz)。これより細かいステップは使わない。 */
        const val MIN_STEP_HZ = 1_000L
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
            stepHz = p.getLong("stepHz", d.stepHz).coerceAtLeast(SsbFmSettings.MIN_STEP_HZ),
            afGain = p.getFloat("afGain", d.afGain),
            squelchDb = p.getFloat("squelchDb", d.squelchDb),
            micGain = p.getFloat("micGain", d.micGain),
            rxAgc = p.getBoolean("rxAgc", d.rxAgc),
            rxGainDb = p.getFloat("rxGainDb", d.rxGainDb),
            txAttenuationDb = p.getFloat("txAttenuationDb", d.txAttenuationDb),
            pttLatch = p.getBoolean("pttLatch", d.pttLatch),
        )
    }

    /** バンドごとに最後に使った周波数とモード。 */
    fun loadBandMemory(context: Context, band: SsbFmBand): Pair<Long, SsbFmMode> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hz = p.getLong("band_${band.name}_hz", band.defaultHz)
        val mode = runCatching { SsbFmMode.valueOf(p.getString("band_${band.name}_mode", null)!!) }
            .getOrDefault(band.defaultMode)
        return hz to mode
    }

    fun saveBandMemory(context: Context, band: SsbFmBand, hz: Long, mode: SsbFmMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("band_${band.name}_hz", hz)
            .putString("band_${band.name}_mode", mode.name)
            .apply()
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

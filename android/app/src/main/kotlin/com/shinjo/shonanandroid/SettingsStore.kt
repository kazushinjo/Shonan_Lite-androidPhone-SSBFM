package com.shinjo.shonanandroid

import android.content.Context
import com.shinjo.shonanandroid.core.AppLanguage
import com.shinjo.shonanandroid.core.AppMode
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.core.BandProfile
import com.shinjo.shonanandroid.core.FECRate
import com.shinjo.shonanandroid.core.ModulationScheme

/**
 * [AppSettings]をSharedPreferencesへ永続化する -- iOS版`AppSettings.swift`の
 * UserDefaults永続化に相当。:coreはAndroid非依存のため、永続化ロジックはここ:app側に置く。
 */
object SettingsStore {
    private const val PREFS_NAME = "shonan_settings"
    private const val SETTINGS_VERSION = 3

    private object Keys {
        const val MODE = "mode"
        const val LANGUAGE = "language"
        const val BAND = "band"
        const val TX_DESTINATION_IP = "txDestinationIP"
        const val RX_LISTEN_PORT = "rxListenPort"
        const val RX_STATUS_PORT = "rxStatusPort"
        const val RX_VOLUME = "rxVolume"
        const val USE_ON_DEVICE_DVBS2_RX = "useOnDeviceGRDVBS2Rx"
        const val DVBS2_ROLLOFF = "dvbs2Rolloff"
        const val USE_FRONT_CAMERA = "useFrontCamera"
        const val USE_PHOTO_SOURCE = "usePhotoSource"
        const val SELECTED_PHOTO_URI = "selectedPhotoUri"
        const val PHOTO_CALLSIGN = "photoCallsign"
        const val PHOTO_NOTE = "photoNote"
        const val USE_COLOR_BAR_SOURCE = "useColorBarSource"
        const val TRANSMIT_AUDIO = "transmitAudio"
        const val RX_AGC_ENABLED = "rxAgcEnabled"
        const val RX_GAIN_DB = "rxGainDb"
        const val TX_POWER_DB = "txPowerDb"
        const val SYMBOL_RATE_MSPS = "symbolRateMsps"
        const val FEC_RATE = "fecRate"
        const val MODULATION_SCHEME = "modulationScheme"
        const val USE_CUSTOM_LO_FREQUENCY = "useCustomLoFrequency"
        const val CUSTOM_LO_FREQUENCY_HZ = "customLoFrequencyHz"
    }

    fun load(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = AppSettings.default
        val settingsVersion = prefs.getInt("settingsVersion", 0)
        return AppSettings(
            mode = prefs.getString(Keys.MODE, null)?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: default.mode,
            language = prefs.getString(Keys.LANGUAGE, null)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: default.language,
            selectedBand = prefs.getString(Keys.BAND, null)
                ?.let { runCatching { BandProfile.valueOf(it) }.getOrNull() }
                ?: default.selectedBand,
            txDestinationIP = prefs.getString(Keys.TX_DESTINATION_IP, null) ?: default.txDestinationIP,
            rxListenPort = prefs.getInt(Keys.RX_LISTEN_PORT, default.rxListenPort),
            rxStatusPort = prefs.getInt(Keys.RX_STATUS_PORT, default.rxStatusPort),
            // 受信音量は起動時に必ず初期値を優先する。保存済みの音量は
            // セッション中の変更には使うが、次回起動時には読み込まない。
            rxVolume = default.rxVolume,
            // オンデバイス復調は起動時に必ずOFF(初期値)で始める。保存済みの値は
            // セッション中の変更には使うが、次回起動時には読み込まない。
            useOnDeviceGRDVBS2Rx = default.useOnDeviceGRDVBS2Rx,
            dvbs2Rolloff = prefs.getFloat(Keys.DVBS2_ROLLOFF, default.dvbs2Rolloff.toFloat()).toDouble(),
            useFrontCamera = prefs.getBoolean(Keys.USE_FRONT_CAMERA, default.useFrontCamera),
            usePhotoSource = prefs.getBoolean(Keys.USE_PHOTO_SOURCE, default.usePhotoSource),
            selectedPhotoUri = prefs.getString(Keys.SELECTED_PHOTO_URI, default.selectedPhotoUri),
            photoCallsign = prefs.getString(Keys.PHOTO_CALLSIGN, default.photoCallsign) ?: default.photoCallsign,
            photoNote = prefs.getString(Keys.PHOTO_NOTE, default.photoNote) ?: default.photoNote,
            useColorBarSource = prefs.getBoolean(Keys.USE_COLOR_BAR_SOURCE, default.useColorBarSource),
            transmitAudio = prefs.getBoolean(Keys.TRANSMIT_AUDIO, default.transmitAudio),
            rxAgcEnabled = prefs.getBoolean(Keys.RX_AGC_ENABLED, default.rxAgcEnabled),
            rxGainDb = if (settingsVersion < SETTINGS_VERSION) 60 else prefs.getInt(Keys.RX_GAIN_DB, default.rxGainDb),
            txPowerDb = if (settingsVersion < SETTINGS_VERSION) 0 else prefs.getInt(Keys.TX_POWER_DB, default.txPowerDb),
            symbolRateMsps = prefs.getFloat(Keys.SYMBOL_RATE_MSPS, default.symbolRateMsps.toFloat()).toDouble(),
            fecRate = prefs.getString(Keys.FEC_RATE, null)?.let { runCatching { FECRate.valueOf(it) }.getOrNull() } ?: default.fecRate,
            modulationScheme = prefs.getString(Keys.MODULATION_SCHEME, null)?.let { runCatching { ModulationScheme.valueOf(it) }.getOrNull() } ?: default.modulationScheme,
            useCustomLoFrequency = prefs.getBoolean(Keys.USE_CUSTOM_LO_FREQUENCY, default.useCustomLoFrequency),
            customLoFrequencyHz = prefs.getLong(Keys.CUSTOM_LO_FREQUENCY_HZ, default.customLoFrequencyHz),
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt("settingsVersion", SETTINGS_VERSION)
            putString(Keys.MODE, settings.mode.name)
            putString(Keys.LANGUAGE, settings.language.name)
            putString(Keys.BAND, settings.selectedBand.name)
            putString(Keys.TX_DESTINATION_IP, settings.txDestinationIP)
            putInt(Keys.RX_LISTEN_PORT, settings.rxListenPort)
            putInt(Keys.RX_STATUS_PORT, settings.rxStatusPort)
            putFloat(Keys.RX_VOLUME, settings.rxVolume)
            putBoolean(Keys.USE_ON_DEVICE_DVBS2_RX, settings.useOnDeviceGRDVBS2Rx)
            putFloat(Keys.DVBS2_ROLLOFF, settings.dvbs2Rolloff.toFloat())
            putBoolean(Keys.USE_FRONT_CAMERA, settings.useFrontCamera)
            putBoolean(Keys.USE_PHOTO_SOURCE, settings.usePhotoSource)
            putString(Keys.SELECTED_PHOTO_URI, settings.selectedPhotoUri)
            putString(Keys.PHOTO_CALLSIGN, settings.photoCallsign)
            putString(Keys.PHOTO_NOTE, settings.photoNote)
            putBoolean(Keys.USE_COLOR_BAR_SOURCE, settings.useColorBarSource)
            putBoolean(Keys.TRANSMIT_AUDIO, settings.transmitAudio)
            putBoolean(Keys.RX_AGC_ENABLED, settings.rxAgcEnabled)
            putInt(Keys.RX_GAIN_DB, settings.rxGainDb)
            putInt(Keys.TX_POWER_DB, settings.txPowerDb)
            putFloat(Keys.SYMBOL_RATE_MSPS, settings.symbolRateMsps.toFloat())
            putString(Keys.FEC_RATE, settings.fecRate.name)
            putString(Keys.MODULATION_SCHEME, settings.modulationScheme.name)
            putBoolean(Keys.USE_CUSTOM_LO_FREQUENCY, settings.useCustomLoFrequency)
            putLong(Keys.CUSTOM_LO_FREQUENCY_HZ, settings.customLoFrequencyHz)
            apply()
        }
    }
}

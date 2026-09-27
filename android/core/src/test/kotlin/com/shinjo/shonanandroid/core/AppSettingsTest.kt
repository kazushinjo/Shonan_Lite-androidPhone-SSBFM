package com.shinjo.shonanandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultTxDestinationIsPlutoTx() {
        val settings = AppSettings.default
        assertEquals(NetworkDefaults.PLUTO_TX to NetworkDefaults.PLUTO_UDP_TS_PORT, settings.effectiveTxDestination())
    }

    @Test
    fun effectiveTxDestinationAlwaysUsesFixedPlutoPort() {
        val settings = AppSettings.default.copy(txDestinationIP = "192.168.0.99")
        assertEquals("192.168.0.99" to NetworkDefaults.PLUTO_UDP_TS_PORT, settings.effectiveTxDestination())
    }

    @Test
    fun effectiveLoHzFallsBackToBandWhenNotCustom() {
        val settings = AppSettings.default.copy(selectedBand = BandProfile.BAND_2400, useCustomLoFrequency = false)
        assertEquals(2_407_000_000L, settings.effectiveLoHz)
    }

    @Test
    fun effectiveLoHzUsesCustomValueWhenEnabled() {
        val settings = AppSettings.default.copy(useCustomLoFrequency = true, customLoFrequencyHz = 5_555_000_000L)
        assertEquals(5_555_000_000L, settings.effectiveLoHz)
    }
}

class BandProfileTest {
    @Test
    fun band2400RecommendsGhz5DueToOverlap() {
        assertEquals(WiFiBand.GHZ_5, BandProfile.BAND_2400.recommendedWiFiBand)
    }

    @Test
    fun band5600RecommendsGhz24DueToOverlap() {
        assertEquals(WiFiBand.GHZ_2_4, BandProfile.BAND_5600.recommendedWiFiBand)
    }
}

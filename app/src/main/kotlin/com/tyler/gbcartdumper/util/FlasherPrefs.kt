package com.tyler.gbcartdumper.util

import android.content.Context
import android.content.SharedPreferences
import com.tyler.gbcartdumper.flasher.Protocol

/**
 * Tiny SharedPreferences wrapper for the last-known-good flasher config.
 * We key everything on the FT232R serial number so swapping flashers (or
 * plugging one in that was never auto-detected) naturally re-runs discovery.
 */
class FlasherPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("flasher_prefs", Context.MODE_PRIVATE)

    /** Save the baud + MBC that was just proven to work. Keyed by FTDI serial. */
    fun save(serial: String, baud: Int, mbc: Protocol.Mbc) {
        prefs.edit()
            .putInt("${serial}_baud", baud)
            .putString("${serial}_mbc", mbc.name)
            .putLong("${serial}_when", System.currentTimeMillis())
            .apply()
    }

    fun load(serial: String): Remembered? {
        val baud = prefs.getInt("${serial}_baud", -1).takeIf { it > 0 } ?: return null
        val mbcName = prefs.getString("${serial}_mbc", null) ?: return null
        val mbc = runCatching { Protocol.Mbc.valueOf(mbcName) }.getOrNull() ?: return null
        return Remembered(baud, mbc)
    }

    fun forget(serial: String) {
        prefs.edit()
            .remove("${serial}_baud")
            .remove("${serial}_mbc")
            .remove("${serial}_when")
            .apply()
    }

    data class Remembered(val baud: Int, val mbc: Protocol.Mbc)
}

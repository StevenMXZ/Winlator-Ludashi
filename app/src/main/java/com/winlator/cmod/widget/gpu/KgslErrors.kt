// SPDX-License-Identifier: GPL-3.0-only
// Adapted from The412Banner/Ludashi 0ab0473 (original port: DroidDeck #1969/#1981).
package com.winlator.cmod.widget.gpu

internal object KgslErrors {
    fun describe(code: Int): String {
        if (code == 0) return "no error code"
        val name = runCatching { android.system.OsConstants.errnoName(-code) }.getOrNull()
        return if (name != null) "$name ($code)" else "error $code"
    }
}

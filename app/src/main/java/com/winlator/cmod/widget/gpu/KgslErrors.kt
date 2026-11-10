// SPDX-License-Identifier: GPL-3.0-only

package com.winlator.cmod.widget.gpu

internal object KgslErrors {
    fun describe(code: Int): String {
        if (code == 0) return "no error code"
        val name = runCatching { android.system.OsConstants.errnoName(-code) }.getOrNull()
        return if (name != null) "$name ($code)" else "error $code"
    }
}

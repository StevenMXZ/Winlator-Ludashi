package com.winlator.cmod.core.unpack
import android.content.Context
import java.io.File
object SevenZip {
    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "lib7zz.so")
    fun isAvailable(context: Context) = binary(context).canExecute()
    fun newProcess(context: Context, vararg args: String) = ProcessBuilder(binary(context).path, *args).apply {
        environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        environment()["TMPDIR"] = context.cacheDir.path
    }
}

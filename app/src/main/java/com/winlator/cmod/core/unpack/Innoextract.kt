package com.winlator.cmod.core.unpack
import android.content.Context
import java.io.File
object Innoextract {
    data class Result(val exitCode: Int)
    fun isAvailable(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libinnoextract.so").canExecute()
    fun extract(context: Context, source: File, destination: File, progress: (Int) -> Unit, line: (String) -> Unit, onProcess: (Process) -> Unit = {}): Result {
        destination.mkdirs()
        val process = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libinnoextract.so").path,
            "--silent", "--extract", "--output-dir", destination.path, source.path).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        }.start()
        onProcess(process)
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(line) }
        return Result(process.waitFor())
    }
}

package com.zero.recorder.core

import com.zero.recorder.RecorderLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object OutputManager {
    private const val TAG = "ZR.Output"

    fun buildOutputPath(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputDir = File(com.zero.recorder.RecorderConfig.OUTPUT_DIR)
        if (!outputDir.exists()) outputDir.mkdirs()
        return "${outputDir.absolutePath}/Rec_$timestamp.mp4"
    }

    fun cleanupFailedOutput(outputPath: String) {
        try {
            val outputFile = File(outputPath)
            val parentDir = outputFile.parentFile
            parentDir?.let {
                it.listFiles { f -> f.name.startsWith(".tmp_part_") }?.forEach { f -> f.delete() }
            }
            outputFile.delete()
        } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
    }

    fun cleanupResidualEnvironment() {
        try {
            val dir = File(com.zero.recorder.RecorderConfig.OUTPUT_DIR)
            if (dir.exists() && dir.isDirectory) {
                val tmps = dir.listFiles { f -> f.name.startsWith(".tmp_part_") }
                tmps?.forEach { f ->
                    if (f.length() > 1024) {
                        val newName = f.name.replace(".tmp_part_", "recovered_part_") + ".mp4"
                        f.renameTo(File(dir, newName))
                    } else {
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
    }
}

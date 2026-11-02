package com.arkanefans.mnn_engine.runtime

import java.io.File

internal object MnnRuntimeCache {
    fun sizeBytes(runtimeDir: File, modelKey: String? = null): Long {
        val target = targetDir(runtimeDir, modelKey)
        if (!target.exists()) return 0
        return target.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    fun clear(runtimeDir: File, modelKey: String? = null): Long {
        val size = sizeBytes(runtimeDir, modelKey)
        val target = targetDir(runtimeDir, modelKey)
        if (target.exists()) {
            check(target.deleteRecursively()) { "Failed to delete mmap cache at ${target.absolutePath}" }
        }
        if (modelKey == null && !runtimeDir.exists()) {
            check(runtimeDir.mkdirs()) { "Failed to recreate runtime directory." }
        }
        return size
    }

    private fun targetDir(runtimeDir: File, modelKey: String?): File {
        return if (modelKey == null) runtimeDir else File(runtimeDir, modelKey)
    }
}

package com.arkanefans.mnn_engine.model

import android.content.Context
import java.io.File

class MnnTestDirectories(context: Context) {
    private val legacyRootDir = File(context.filesDir, "mnn_test")
    val rootDir = File(context.filesDir, "mnn")
    val modelsDir = File(rootDir, "models")
    val stagingDir = File(rootDir, "staging")
    val runtimeDir = File(rootDir, "runtime")
    val diagnosticsDir = File(rootDir, "diagnostics")

    @Synchronized
    fun ensureCreated() {
        migrateLegacyRootIfNeeded()
        listOf(rootDir, modelsDir, stagingDir, runtimeDir, diagnosticsDir).forEach { dir ->
            check(dir.exists() || dir.mkdirs()) { "Failed to create ${dir.absolutePath}" }
        }
    }

    private fun migrateLegacyRootIfNeeded() {
        if (rootDir.exists() || !legacyRootDir.exists()) {
            return
        }
        if (legacyRootDir.renameTo(rootDir)) {
            return
        }

        val copied = legacyRootDir.copyRecursively(rootDir, overwrite = false)
        if (!copied) {
            // The old tree remains authoritative. Remove only the new partial
            // tree we just created so a later launch can retry migration.
            rootDir.deleteRecursively()
            return
        }
        // Failure to remove the old copy is harmless and intentionally does
        // not fail initialization: both trees contain the same model data.
        legacyRootDir.deleteRecursively()
    }

    fun modelDir(modelKey: String) = File(modelsDir, modelKey)
    fun modelRuntimeDir(modelKey: String) = File(runtimeDir, modelKey)
}

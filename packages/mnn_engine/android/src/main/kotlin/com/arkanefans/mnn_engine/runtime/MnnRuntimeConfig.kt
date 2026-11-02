package com.arkanefans.mnn_engine.runtime

import com.google.gson.JsonObject
import java.io.File

internal object MnnRuntimeConfig {
    fun create(
        source: JsonObject,
        options: MnnLoadOptions,
        runtimeDir: File,
        mnnVersion: String,
    ): JsonObject {
        val root = source.deepCopy()
        root.addProperty("backend_type", options.backend.wireName)
        root.addProperty("use_mmap", options.useMmap)
        root.addProperty("precision", options.precision.wireName)
        root.addProperty("thread_num", options.threadNum)
        val tempDir = File(runtimeDir, "tmp/$mnnVersion/${options.backend.wireName}")
        check(tempDir.isDirectory || tempDir.mkdirs()) { "Failed to create model runtime directory." }
        root.addProperty("tmp_path", tempDir.absolutePath)
        return root
    }
}

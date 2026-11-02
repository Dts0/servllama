package com.arkanefans.mnn_engine.runtime

import android.content.Context
import com.google.gson.JsonParser

internal object MnnNativeBridge {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("MNN")
        System.loadLibrary("mnn_engine_jni")
    }.exceptionOrNull()

    val loaded: Boolean
        get() = loadError == null

    private var configured = false
    private var applicationContext: Context? = null
    private var hexagonAssetsPrepared = false
    private var dspArchitecture: String? = null
    private var hexagonPreparationError: HexagonPreparationError? = null

    private data class HexagonPreparationError(val reason: String, val detail: String)

    @Synchronized
    fun prepare(context: Context) {
        if (!loaded || configured) return
        applicationContext = context.applicationContext
        nativeConfigureBackends(context.applicationInfo.nativeLibraryDir, "")
        configured = true
    }

    @Synchronized
    fun backendCapabilities(): List<Map<String, Any?>> {
        if (!loaded) {
            return MnnBackend.entries.map {
                mapOf("backend" to it.wireName, "compiled" to false, "available" to false,
                    "reason" to "nativeUnavailable", "detail" to loadFailureMessage())
            }
        }
        check(configured) { "MNN backend paths have not been initialized." }
        prepareHexagon()
        return JsonParser.parseString(nativeGetBackendCapabilities()).asJsonArray.map { value ->
            val item = value.asJsonObject
            val isHexagon = item.get("backend").asString == "hexagon"
            val error = hexagonPreparationError.takeIf { isHexagon }
            mapOf(
                "backend" to item.get("backend").asString,
                "compiled" to item.get("compiled").asBoolean,
                "available" to (error == null && item.get("available").asBoolean),
                "reason" to (error?.reason ?: item.get("reason").asString),
                "detail" to (error?.detail ?: item.get("detail").asString),
                "dspArchitecture" to dspArchitecture.takeIf { isHexagon },
            )
        }
    }

    private fun prepareHexagon() {
        if (hexagonAssetsPrepared) return
        val context = checkNotNull(applicationContext)
        hexagonPreparationError = null
        dspArchitecture = null
        try {
            if (!MnnHexagonAssets.isPackaged(context)) return
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val device = JsonParser.parseString(nativeDetectHexagonArchitecture(nativeDir)).asJsonObject
            if (device.get("reason").asString != "available") {
                hexagonPreparationError = HexagonPreparationError(
                    device.get("reason").asString, device.get("detail").asString,
                )
                return
            }
            val architecture = device.get("architecture").asString
            dspArchitecture = architecture
            val commit = version().substringAfter("(", "unknown").substringBefore(")")
            val directory = MnnHexagonAssets.prepare(context, commit, architecture)
            nativeConfigureBackends(nativeDir, directory.absolutePath)
            hexagonAssetsPrepared = true
        } catch (error: Throwable) {
            hexagonPreparationError = HexagonPreparationError(
                if (error is MnnHexagonAssets.ArchitectureUnavailable) "deviceUnsupported" else "runtimeLibrariesMissing",
                error.message ?: "Hexagon runtime preparation failed.",
            )
        }
    }

    fun version(): String {
        loadError?.let { error ->
            throw IllegalStateException("MNN native libraries are unavailable", error)
        }
        return nativeGetVersion()
    }

    fun loadFailureMessage(): String? = loadError?.message

    fun takeDiagnosticLogs(sinceMillis: Long): String {
        check(loaded) { "MNN native libraries are unavailable." }
        return nativeTakeDiagnosticLogs(sinceMillis)
    }

    private external fun nativeGetVersion(): String
    private external fun nativeTakeDiagnosticLogs(sinceMillis: Long): String
    private external fun nativeConfigureBackends(nativeLibraryDir: String, dspLibraryDir: String)
    private external fun nativeDetectHexagonArchitecture(nativeLibraryDir: String): String
    private external fun nativeGetBackendCapabilities(): String
}

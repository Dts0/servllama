package com.arkanefans.mnn_engine.service

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.arkanefans.mnn_engine.BuildConfig
import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnTestDirectories
import com.arkanefans.mnn_engine.model.MnnModelImporter
import com.arkanefans.mnn_engine.model.MnnModelValidator
import com.arkanefans.mnn_engine.model.MnnTestModelRepository
import com.arkanefans.mnn_engine.runtime.MnnNativeBridge
import com.arkanefans.mnn_engine.runtime.MnnLoadOptions
import com.arkanefans.mnn_engine.runtime.MnnRuntimeCache
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.arkanefans.mnn_engine.runtime.RuntimeSnapshot
import com.arkanefans.mnn_engine.server.MnnOpenAiServer
import com.arkanefans.mnn_engine.server.MnnBindMode
import com.arkanefans.mnn_engine.server.MnnServerSocketPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class MnnEngineService : Service() {
    val logStore = MnnLogStore()
    private lateinit var directories: MnnTestDirectories
    private lateinit var repository: MnnTestModelRepository
    private lateinit var importer: MnnModelImporter
    lateinit var runtimeManager: MnnRuntimeManager
        private set
    private lateinit var notificationManager: MnnNotificationManager
    private lateinit var powerController: MnnServerPowerController
    private lateinit var openAiServer: MnnOpenAiServer
    private val revision = AtomicLong(0)
    private val runtimeListeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()
    private val foregroundLifecycleLock = Any()
    @Volatile
    private var snapshot = RuntimeSnapshot()
    @Volatile
    private var foregroundRequested = false
    private var foregroundSessionActive = false

    override fun onCreate() {
        super.onCreate()
        directories = MnnTestDirectories(this)
        val validator = MnnModelValidator()
        repository = MnnTestModelRepository(directories, validator)
        importer = MnnModelImporter(this, directories, validator, repository, logStore)
        runtimeManager = MnnRuntimeManager(
            directories = directories,
            repository = repository,
            logStore = logStore,
        ) { modelState, generationState, activeModel, lastError ->
            updateSnapshot(
                snapshot.copy(
                    modelState = modelState,
                    generationState = generationState,
                    activeModel = activeModel?.toMap(),
                    lastError = lastError,
                ),
            )
        }
        notificationManager = MnnNotificationManager(this)
        powerController = MnnServerPowerController(this, logStore)
        openAiServer = MnnOpenAiServer(
            context = this,
            runtimeManager = runtimeManager,
            snapshotProvider = ::getSnapshot,
            logStore = logStore,
        )
        directories.ensureCreated()
        cleanupStaging()
        logStore.debug(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_FOREGROUND) {
            val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: "http://127.0.0.1:8081"
            val testUrl = intent.getStringExtra(EXTRA_TEST_URL) ?: baseUrl
            val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "MNN model"
            val keepWifiAwake = intent.getBooleanExtra(EXTRA_KEEP_WIFI_AWAKE, false)
            try {
                synchronized(foregroundLifecycleLock) {
                    if (!foregroundRequested) {
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    enterForegroundSessionLocked(
                        baseUrl = baseUrl,
                        testUrl = testUrl,
                        modelName = modelName,
                        keepWifiAwake = keepWifiAwake,
                    )
                }
            } catch (error: Throwable) {
                logStore.error(TAG, "Failed to enter foreground serving mode", error)
                clearForegroundSession(stopService = false)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    fun initializeEngine(): Map<String, Any?> {
        directories.ensureCreated()
        MnnNativeBridge.prepare(this)
        val loaded = MnnNativeBridge.loaded
        val version = if (loaded) {
            runCatching { MnnNativeBridge.version() }.getOrElse { error ->
                logStore.error(TAG, "Failed to query MNN version", error)
                "unavailable"
            }
        } else {
            MnnNativeBridge.loadFailureMessage()?.let { message ->
                logStore.warn(TAG, "Native library is not ready: $message")
            }
            "unavailable"
        }
        updateSnapshot(
            snapshot.copy(
                engineState = if (loaded) "ready" else "native_unavailable",
                lastError = if (loaded) null else MnnNativeBridge.loadFailureMessage(),
            ),
        )
        return mapOf(
            "pluginVersion" to BuildConfig.MNN_ENGINE_VERSION,
            "mnnVersion" to version.substringBefore(" (").ifBlank { "unavailable" },
            "mnnCommit" to version.substringAfter("(", "unknown").substringBefore(")"),
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            "androidApiLevel" to Build.VERSION.SDK_INT,
            "ndkVersion" to "27.3.13750724",
            "nativeLibraryLoaded" to loaded,
            "testRootPath" to directories.rootDir.absolutePath,
        )
    }

    fun getSnapshot(): Map<String, Any?> = snapshot.toMap()

    fun getBackendCapabilities(): List<Map<String, Any?>> {
        MnnNativeBridge.prepare(this)
        return runtimeManager.backendCapabilities()
    }

    fun getTestRootPath(): String {
        directories.ensureCreated()
        return directories.rootDir.absolutePath
    }

    fun listImportedModels(): List<Map<String, Any?>> {
        val active = runtimeManager.activeModel()
        return repository.list(active?.modelId).map { model ->
            if (active != null && model.modelId == active.modelId) active.toMap() else model.toMap()
        }
    }

    fun importModelDirectory(treeUri: Uri, replaceExisting: Boolean): Map<String, Any?> {
        requireServerStopped("import a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        return runModelImport {
            importer.import(treeUri, replaceExisting, activeModelId).toMap()
        }
    }

    fun importModelDirectoryWithResult(
        treeUri: Uri,
        replaceExisting: Boolean,
        autoRename: Boolean,
        unavailableNames: Collection<String>,
    ): Map<String, Any?> {
        requireServerStopped("import a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        return runModelImport {
            importer.importWithResult(
                treeUri = treeUri,
                replaceExisting = replaceExisting,
                activeModelId = activeModelId,
                autoRename = autoRename,
                unavailableNames = unavailableNames,
            ).toMap()
        }
    }

    fun importModelFromPath(directoryPath: String, replaceExisting: Boolean): Map<String, Any?> {
        requireServerStopped("import a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        return runModelImport {
            importer.importFromPath(java.io.File(directoryPath), replaceExisting, activeModelId).toMap()
        }
    }

    fun importModelFromPathWithResult(
        directoryPath: String,
        replaceExisting: Boolean,
        autoRename: Boolean,
        unavailableNames: Collection<String>,
    ): Map<String, Any?> {
        requireServerStopped("import a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        return runModelImport {
            importer.importFromPathWithResult(
                sourceDir = java.io.File(directoryPath),
                replaceExisting = replaceExisting,
                activeModelId = activeModelId,
                autoRename = autoRename,
                unavailableNames = unavailableNames,
            ).toMap()
        }
    }

    private fun runModelImport(operation: () -> Map<String, Any?>): Map<String, Any?> {
        return try {
            operation()
        } catch (error: MnnEngineOperationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            val code = if (error.message.orEmpty().contains("config.json")) {
                "model_config_not_found"
            } else {
                "model_path_not_readable"
            }
            throw MnnEngineOperationException(
                code,
                error.message ?: "Model import failed.",
                cause = error,
            )
        }
    }

    fun deleteImportedModel(modelId: String) {
        requireServerStopped("delete a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        repository.delete(modelId, activeModelId)
        logStore.info(TAG, "Deleted imported model: $modelId")
    }

    fun renameImportedModel(modelId: String, newName: String): Map<String, Any?> {
        requireServerStopped("rename a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        return try {
            repository.rename(modelId, newName, activeModelId).toMap().also {
                logStore.info(TAG, "Renamed imported model: $modelId -> $newName")
            }
        } catch (error: MnnEngineOperationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "Failed to rename the model."
            val code = when {
                message.contains("not found", ignoreCase = true) -> "model_not_found"
                message.contains("already exists", ignoreCase = true) -> "model_name_exists"
                message.contains("active model", ignoreCase = true) -> "model_active"
                else -> "invalid_model_name"
            }
            throw MnnEngineOperationException(code, message, cause = error)
        } catch (error: Throwable) {
            throw MnnEngineOperationException(
                "model_rename_failed",
                error.message ?: "Failed to rename the model.",
                cause = error,
            )
        }
    }

    fun loadModel(modelId: String, options: MnnLoadOptions = MnnLoadOptions()): Map<String, Any?> {
        requireServerStopped("load or switch models")
        MnnNativeBridge.prepare(this)
        return try {
            runtimeManager.load(modelId, options).toMap()
        } catch (error: MnnEngineOperationException) {
            throw error
        } catch (error: MnnRuntimeManager.GenerationBusyException) {
            throw MnnEngineOperationException("model_busy", error.message ?: "The model is busy.", cause = error)
        } catch (error: IllegalArgumentException) {
            throw MnnEngineOperationException("model_config_not_found", error.message ?: "Model not found.", cause = error)
        } catch (error: Throwable) {
            val code = when {
                error.message?.startsWith("backend_unavailable:") == true -> "backend_unavailable"
                error.message?.startsWith("model_backend_incompatible:") == true -> "model_backend_incompatible"
                else -> "model_load_failed"
            }
            throw MnnEngineOperationException(code, error.message ?: "MNN model load failed.", cause = error)
        }
    }

    fun unloadModel() {
        requireServerStopped("unload the model")
        try {
            runtimeManager.unload()
        } catch (error: MnnRuntimeManager.GenerationBusyException) {
            throw MnnEngineOperationException("model_busy", error.message ?: "The model is busy.", cause = error)
        } catch (error: Throwable) {
            throw MnnEngineOperationException("runtime_release_failed", error.message ?: "Failed to release MNN Runtime.", cause = error)
        }
    }

    fun cancelGeneration() {
        runtimeManager.cancelGeneration()
    }

    fun getMmapCache(modelId: String?): Map<String, Any?> {
        directories.ensureCreated()
        return mmapCachePayload(modelId, cleared = false)
    }

    fun clearMmapCache(modelId: String?): Map<String, Any?> {
        val active = runtimeManager.activeModel()
        if (active != null && (modelId == null || active.modelId == modelId)) {
            throw MnnEngineOperationException(
                "model_active",
                "Unload the model before clearing its mmap cache.",
            )
        }
        directories.ensureCreated()
        val modelKey = resolveRuntimeCacheKey(modelId)
        val sizeBytes = MnnRuntimeCache.clear(directories.runtimeDir, modelKey)
        logStore.info(
            TAG,
            if (modelId == null) {
                "Cleared MNN mmap cache, bytes=$sizeBytes"
            } else {
                "Cleared MNN mmap cache for $modelId, bytes=$sizeBytes"
            },
        )
        return mapOf(
            "sizeBytes" to sizeBytes,
            "cleared" to true,
            "modelId" to modelId,
        )
    }

    private fun mmapCachePayload(modelId: String?, cleared: Boolean): Map<String, Any?> {
        val modelKey = resolveRuntimeCacheKey(modelId)
        return mapOf(
            "sizeBytes" to MnnRuntimeCache.sizeBytes(directories.runtimeDir, modelKey),
            "cleared" to cleared,
            "modelId" to modelId,
        )
    }

    private fun resolveRuntimeCacheKey(modelId: String?): String? {
        if (modelId == null) return null
        val model = repository.find(modelId, runtimeManager.activeModel()?.modelId)
            ?: throw MnnEngineOperationException("model_not_found", "Model not found: $modelId")
        return model.modelKey
    }

    fun startServer(bindMode: String, port: Int, apiKey: String?): Map<String, Any?> {
        val mode = try {
            MnnBindMode.parse(bindMode)
        } catch (error: IllegalArgumentException) {
            throw MnnEngineOperationException("invalid_argument", error.message ?: "Invalid bind mode.")
        }
        if (port !in 1024..65535) {
            throw MnnEngineOperationException(
                "invalid_argument",
                "Port must be between 1024 and 65535.",
                mapOf("bindMode" to mode.wireName, "port" to port),
            )
        }
        val model = runtimeManager.activeModel()
            ?: throw MnnEngineOperationException("model_not_loaded", "Load a model before starting the MNN Server.")
        openAiServer.info()?.let { existing ->
            if (existing.running && existing.bindMode == mode.wireName && existing.port == port) return existing.toMap()
            throw MnnEngineOperationException("server_start_failed", "MNN Server is already running at ${existing.baseUrl}.")
        }
        updateSnapshot(snapshot.copy(serverState = "starting", lastError = null))
        val baseUrl = "http://127.0.0.1:$port"
        val testUrl = baseUrl
        return try {
            startForegroundSession(
                baseUrl = baseUrl,
                testUrl = testUrl,
                modelName = model.displayName,
                keepWifiAwake = mode == MnnBindMode.ALL_INTERFACES,
            )
            val info = openAiServer.start(mode, port, apiKey)
            updateSnapshot(
                snapshot.copy(
                    serverState = "running",
                    server = info.toMap(),
                    lastError = null,
                ),
            )
            info.toMap()
        } catch (error: Throwable) {
            val operationError = when {
                error is MnnEngineOperationException -> error
                MnnServerSocketPolicy.isAddressAlreadyInUse(error) ->
                    MnnEngineOperationException(
                        "port_in_use",
                        "Port $port is unavailable.",
                        mapOf("bindMode" to mode.wireName, "port" to port),
                        cause = error,
                    )
                else ->
                    MnnEngineOperationException(
                        "server_start_failed",
                        error.message ?: "Failed to start MNN Server.",
                        cause = error,
                    )
            }
            clearForegroundSession()
            updateSnapshot(snapshot.copy(serverState = "error", server = null, lastError = operationError.message))
            throw operationError
        }
    }

    fun stopServer() {
        if (openAiServer.info() == null) {
            clearForegroundSession()
            return
        }
        updateSnapshot(snapshot.copy(serverState = "stopping"))
        try {
            openAiServer.stop()
        } catch (error: Throwable) {
            updateSnapshot(snapshot.copy(serverState = "error", server = openAiServer.info()?.toMap(), lastError = error.message))
            throw error
        }
        clearForegroundSession()
        updateSnapshot(snapshot.copy(serverState = "stopped", server = null, lastError = null))
    }

    fun checkPort(bindMode: String, port: Int): Map<String, Any?> {
        val mode = runCatching { MnnBindMode.parse(bindMode) }.getOrElse { error ->
            return mapOf("available" to false, "ownedByMnn" to false, "message" to error.message)
        }
        if (port !in 1024..65535) {
            return mapOf(
                "available" to false,
                "ownedByMnn" to false,
                "message" to "Port must be between 1024 and 65535.",
            )
        }
        openAiServer.info()?.let { info ->
            if (info.bindMode == mode.wireName && info.port == port) {
                return mapOf("available" to false, "ownedByMnn" to true, "message" to null)
            }
        }
        val message = MnnServerSocketPolicy.probe(mode.host, port)
        return mapOf(
            "available" to (message == null),
            "ownedByMnn" to false,
            "message" to message,
        )
    }

    fun addRuntimeListener(listener: (Map<String, Any?>) -> Unit) {
        runtimeListeners.addIfAbsent(listener)
    }

    fun removeRuntimeListener(listener: (Map<String, Any?>) -> Unit) {
        runtimeListeners.remove(listener)
    }

    private fun requireServerStopped(operation: String) {
        openAiServer.info()?.let { info ->
            throw MnnEngineOperationException(
                "model_busy",
                "Stop the MNN Server at ${info.baseUrl} before attempting to $operation.",
            )
        }
    }

    private fun updateSnapshot(newSnapshot: RuntimeSnapshot) {
        snapshot = newSnapshot.copy(revision = revision.incrementAndGet())
        val event = mapOf("type" to "snapshot", "snapshot" to getSnapshot())
        runtimeListeners.forEach { listener -> listener(event) }
    }

    private fun startForegroundSession(
        baseUrl: String,
        testUrl: String,
        modelName: String,
        keepWifiAwake: Boolean,
    ) {
        val foregroundIntent = Intent(this, MnnEngineService::class.java).apply {
            action = ACTION_START_FOREGROUND
            putExtra(EXTRA_BASE_URL, baseUrl)
            putExtra(EXTRA_TEST_URL, testUrl)
            putExtra(EXTRA_MODEL_NAME, modelName)
            putExtra(EXTRA_KEEP_WIFI_AWAKE, keepWifiAwake)
        }
        synchronized(foregroundLifecycleLock) {
            foregroundRequested = true
            try {
                ContextCompat.startForegroundService(this, foregroundIntent)
                // The service already exists through the Flutter plugin binding.
                // Promote it synchronously so the API server never starts before foreground
                // mode and the server power locks are active. onStartCommand is
                // retained as an idempotent fallback for Android's start callback.
                enterForegroundSessionLocked(
                    baseUrl = baseUrl,
                    testUrl = testUrl,
                    modelName = modelName,
                    keepWifiAwake = keepWifiAwake,
                )
            } catch (error: Throwable) {
                clearForegroundSessionLocked()
                stopSelf()
                throw error
            }
        }
    }

    private fun enterForegroundSessionLocked(
        baseUrl: String,
        testUrl: String,
        modelName: String,
        keepWifiAwake: Boolean,
    ) {
        if (foregroundSessionActive) return
        val notification = notificationManager.build(baseUrl, testUrl, modelName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MnnNotificationManager.NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } else {
            startForeground(MnnNotificationManager.NOTIFICATION_ID, notification)
        }
        try {
            powerController.acquire(keepWifiAwake)
            foregroundSessionActive = true
        } catch (error: Throwable) {
            stopForegroundCompat()
            throw error
        }
    }

    private fun clearForegroundSession(stopService: Boolean = true) {
        synchronized(foregroundLifecycleLock) {
            clearForegroundSessionLocked()
        }
        if (stopService) stopSelf()
    }

    private fun clearForegroundSessionLocked() {
        foregroundRequested = false
        powerController.release()
        if (foregroundSessionActive) {
            stopForegroundCompat()
            foregroundSessionActive = false
        }
    }

    private fun cleanupStaging() {
        val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        directories.stagingDir.listFiles().orEmpty().forEach { entry ->
            if (entry.lastModified() < cutoff || entry.listFiles().isNullOrEmpty()) {
                entry.deleteRecursively()
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        runCatching {
            openAiServer.stop()
            runtimeManager.release()
        }.onFailure { error ->
            logStore.error(TAG, "Native cleanup was not confirmed during service teardown", error)
        }
        clearForegroundSession(stopService = false)
        logStore.debug(TAG, "Service destroyed")
        runtimeListeners.clear()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): MnnEngineService = this@MnnEngineService
    }

    private companion object {
        const val TAG = "MnnEngineService"
        const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val ACTION_START_FOREGROUND = "com.arkanefans.mnn_engine.START_FOREGROUND"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_TEST_URL = "test_url"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_KEEP_WIFI_AWAKE = "keep_wifi_awake"
    }
}

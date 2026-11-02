package com.arkanefans.mnn_engine

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.arkanefans.mnn_engine.logging.MnnLogEntry
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.arkanefans.mnn_engine.service.MnnEngineService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.Executors

class MnnEnginePlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {
    private lateinit var applicationContext: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var logChannel: EventChannel
    private var service: MnnEngineService? = null
    private var bound = false
    private var bindingInProgress = false
    private val pendingCalls = ArrayDeque<PendingCall>()
    private var runtimeEventSink: EventChannel.EventSink? = null
    private var logEventSink: EventChannel.EventSink? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingImportResult: MethodChannel.Result? = null
    private var pendingReplaceExisting = true
    private var pendingAutoRename = false
    private var pendingUnavailableNames = emptyList<String>()
    private var pendingReturnsImportResult = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val runtimeListener: (Map<String, Any?>) -> Unit = { event ->
        mainHandler.post { runtimeEventSink?.success(event) }
    }
    private val logListener: (MnnLogEntry) -> Unit = { entry ->
        mainHandler.post { logEventSink?.success(entry.toMap()) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MnnEngineService.LocalBinder).getService()
            bindingInProgress = false
            bound = true
            service?.addRuntimeListener(runtimeListener)
            service?.logStore?.addListener(logListener)
            while (pendingCalls.isNotEmpty()) {
                val pending = pendingCalls.removeFirst()
                dispatch(pending.call, pending.result)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeRuntimeListener(runtimeListener)
            service?.logStore?.removeListener(logListener)
            service = null
            bindingInProgress = false
            bound = false
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        logChannel = EventChannel(binding.binaryMessenger, LOG_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(runtimeStreamHandler)
        logChannel.setStreamHandler(logStreamHandler)
        bindService()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (service == null) {
            pendingCalls.addLast(PendingCall(call, result))
            bindService()
            return
        }
        dispatch(call, result)
    }

    private fun dispatch(call: MethodCall, result: MethodChannel.Result) {
        val currentService = service
        if (currentService == null) {
            result.error("service_not_connected", "MNN engine service is not connected.", null)
            return
        }
        try {
            when (call.method) {
                "initialize" -> executeIo(result) { currentService.initializeEngine() }
                "getBackendCapabilities" -> executeIo(result) { currentService.getBackendCapabilities() }
                "getSnapshot" -> result.success(currentService.getSnapshot())
                "getTestRootPath" -> result.success(currentService.getTestRootPath())
                "listImportedModels" -> executeIo(result) { currentService.listImportedModels() }
                "importModelDirectory" -> startModelDirectoryPicker(
                    result,
                    call.argument<Boolean>("replaceExisting") ?: true,
                )
                "importModelDirectoryWithResult" -> startModelDirectoryPicker(
                    result = result,
                    replaceExisting = call.argument<Boolean>("replaceExisting") ?: false,
                    autoRename = call.argument<Boolean>("autoRename") ?: true,
                    unavailableNames = call.argument<List<String>>("unavailableNames").orEmpty(),
                    returnsImportResult = true,
                )
                "importModelFromPath" -> {
                    val directoryPath = call.argument<String>("directoryPath")
                        ?: throw IllegalArgumentException("directoryPath is required.")
                    executeIo(result) {
                        currentService.importModelFromPath(
                            directoryPath,
                            call.argument<Boolean>("replaceExisting") ?: true,
                        )
                    }
                }
                "importModelFromPathWithResult" -> {
                    val directoryPath = call.argument<String>("directoryPath")
                        ?: throw IllegalArgumentException("directoryPath is required.")
                    executeIo(result) {
                        currentService.importModelFromPathWithResult(
                            directoryPath = directoryPath,
                            replaceExisting = call.argument<Boolean>("replaceExisting") ?: false,
                            autoRename = call.argument<Boolean>("autoRename") ?: true,
                            unavailableNames = call.argument<List<String>>("unavailableNames").orEmpty(),
                        )
                    }
                }
                "deleteImportedModel" -> {
                    val modelId = call.argument<String>("modelId")
                        ?: throw IllegalArgumentException("modelId is required.")
                    executeIo(result) {
                        currentService.deleteImportedModel(modelId)
                        null
                    }
                }
                "renameImportedModel" -> {
                    val modelId = call.argument<String>("modelId")
                        ?: throw IllegalArgumentException("modelId is required.")
                    val newName = call.argument<String>("newName")
                        ?: throw IllegalArgumentException("newName is required.")
                    executeIo(result) { currentService.renameImportedModel(modelId, newName) }
                }
                "loadModel" -> {
                    val modelId = call.argument<String>("modelId")
                        ?: throw IllegalArgumentException("modelId is required.")
                    val options = com.arkanefans.mnn_engine.runtime.MnnLoadOptions.fromMap(
                        call.argument<Map<*, *>>("options"),
                    )
                    executeIo(result) { currentService.loadModel(modelId, options) }
                }
                "unloadModel" -> executeIo(result) {
                    currentService.unloadModel()
                    null
                }
                "cancelGeneration" -> {
                    currentService.cancelGeneration()
                    result.success(null)
                }
                "getMmapCache" -> executeIo(result) {
                    currentService.getMmapCache(call.argument<String>("modelId"))
                }
                "clearMmapCache" -> executeIo(result) {
                    currentService.clearMmapCache(call.argument<String>("modelId"))
                }
                "startServer" -> executeIo(result) {
                    currentService.startServer(
                        bindMode = call.argument<String>("bindMode") ?: "loopback",
                        port = call.argument<Int>("port") ?: 8081,
                        apiKey = call.argument<String>("apiKey"),
                    )
                }
                "stopServer" -> executeIo(result) {
                    currentService.stopServer()
                    null
                }
                "checkPort" -> executeIo(result) {
                    currentService.checkPort(
                        call.argument<String>("bindMode") ?: "loopback",
                        call.argument<Int>("port") ?: 8081,
                    )
                }
                "getLogSnapshot" -> result.success(
                    currentService.logStore.snapshot().map(MnnLogEntry::toMap),
                )
                "clearLogs" -> {
                    currentService.logStore.clear()
                    result.success(null)
                }
                else -> result.error(
                    "not_implemented",
                    "${call.method} is not implemented yet.",
                    null,
                )
            }
        } catch (error: Throwable) {
            sendError(result, error)
        }
    }

    private fun executeIo(
        result: MethodChannel.Result,
        operation: () -> Any?,
    ) {
        ioExecutor.execute {
            val outcome = runCatching(operation)
            mainHandler.post {
                outcome.onSuccess(result::success).onFailure { error ->
                    sendError(result, error)
                }
            }
        }
    }

    private fun startModelDirectoryPicker(
        result: MethodChannel.Result,
        replaceExisting: Boolean,
        autoRename: Boolean = false,
        unavailableNames: List<String> = emptyList(),
        returnsImportResult: Boolean = false,
    ) {
        val activity = activityBinding?.activity
        if (activity == null) {
            result.error("activity_unavailable", "An Android activity is required to import a model.", null)
            return
        }
        if (pendingImportResult != null) {
            result.error("import_in_progress", "A model import is already in progress.", null)
            return
        }
        pendingImportResult = result
        pendingReplaceExisting = replaceExisting
        pendingAutoRename = autoRename
        pendingUnavailableNames = unavailableNames
        pendingReturnsImportResult = returnsImportResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        activity.startActivityForResult(intent, IMPORT_MODEL_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != IMPORT_MODEL_REQUEST_CODE) return false
        val result = pendingImportResult ?: return true
        pendingImportResult = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            clearPendingImportOptions()
            result.error("import_cancelled", "Model directory selection was cancelled.", null)
            return true
        }
        val treeUri = data.data!!
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(
                treeUri,
                data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val replaceExisting = pendingReplaceExisting
        val autoRename = pendingAutoRename
        val unavailableNames = pendingUnavailableNames
        val returnsImportResult = pendingReturnsImportResult
        clearPendingImportOptions()
        ioExecutor.execute {
            val operation = runCatching {
                val currentService = service
                    ?: throw IllegalStateException("MNN engine service is not connected.")
                if (returnsImportResult) {
                    currentService.importModelDirectoryWithResult(
                        treeUri = treeUri,
                        replaceExisting = replaceExisting,
                        autoRename = autoRename,
                        unavailableNames = unavailableNames,
                    )
                } else {
                    currentService.importModelDirectory(treeUri, replaceExisting)
                }
            }
            mainHandler.post {
                operation.onSuccess(result::success).onFailure { error ->
                    sendError(result, error, fallbackCode = "model_import_failed")
                }
            }
        }
        return true
    }

    private fun bindService() {
        if (bound || bindingInProgress) return
        val intent = Intent(applicationContext, MnnEngineService::class.java)
        bindingInProgress = applicationContext.bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bindingInProgress) {
            while (pendingCalls.isNotEmpty()) {
                pendingCalls.removeFirst().result.error(
                    "service_bind_failed",
                    "Failed to bind MNN engine service.",
                    null,
                )
            }
        }
    }

    private fun sendError(
        result: MethodChannel.Result,
        error: Throwable,
        fallbackCode: String = "native_error",
    ) {
        val operationError = error as? MnnEngineOperationException
        val code = when {
            operationError != null -> operationError.code
            error is MnnRuntimeManager.GenerationBusyException -> "model_busy"
            error is IllegalArgumentException -> "invalid_argument"
            else -> fallbackCode
        }
        val message = error.message ?: error.javaClass.simpleName
        // Model/runtime layers attach context; the method-channel boundary
        // owns the operation log so errors are not repeated at every layer.
        when (code) {
            "invalid_argument", "model_busy", "backend_unavailable", "model_backend_incompatible",
            "model_name_exists", "invalid_model_name", "model_active", "model_not_found",
            "model_config_not_found", "port_in_use" -> service?.logStore?.warn("plugin", "$code: $message")
            else -> service?.logStore?.error("plugin", "$code: $message")
        }
        result.error(
            code,
            message,
            operationError?.details,
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        logChannel.setStreamHandler(null)
        service?.removeRuntimeListener(runtimeListener)
        service?.logStore?.removeListener(logListener)
        if (bound || bindingInProgress) {
            applicationContext.unbindService(serviceConnection)
        }
        service = null
        bindingInProgress = false
        bound = false
        pendingCalls.clear()
        pendingImportResult?.error("plugin_detached", "Plugin detached during model import.", null)
        pendingImportResult = null
        clearPendingImportOptions()
        ioExecutor.shutdownNow()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    private fun detachActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
    }

    private fun clearPendingImportOptions() {
        pendingReplaceExisting = true
        pendingAutoRename = false
        pendingUnavailableNames = emptyList()
        pendingReturnsImportResult = false
    }

    private val runtimeStreamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            runtimeEventSink = events
            service?.getSnapshot()?.let { snapshot ->
                events?.success(mapOf("type" to "snapshot", "snapshot" to snapshot))
            }
        }

        override fun onCancel(arguments: Any?) {
            runtimeEventSink = null
        }
    }

    private val logStreamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            logEventSink = events
        }

        override fun onCancel(arguments: Any?) {
            logEventSink = null
        }
    }

    private data class PendingCall(
        val call: MethodCall,
        val result: MethodChannel.Result,
    )

    private companion object {
        const val METHOD_CHANNEL = "com.arkanefans.mnn_engine/methods"
        const val EVENT_CHANNEL = "com.arkanefans.mnn_engine/events"
        const val LOG_CHANNEL = "com.arkanefans.mnn_engine/logs"
        const val IMPORT_MODEL_REQUEST_CODE = 5801
    }
}

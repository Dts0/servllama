package com.arkanefans.servllama

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** SAF staging only: the Dart repository verifies pinned hashes before atomic adoption. */
class MiniCpmImporter(private val activity: Activity) {
    private data class Asset(val path: String, val bytes: Long)
    private class Operation(val result: MethodChannel.Result, val files: List<Asset>) {
        val cancelled = AtomicBoolean(false)
    }
    private var pending: Operation? = null
    private var picker: Operation? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "cancelMiniCpmImport" -> { cancel(); result.success(null) }
            "pickMiniCpmDirectory" -> {
                if (pending != null || picker != null) { result.error("import_busy", "An import is active", null); return }
                try {
                    val rows = call.argument<List<Map<String, Any>>>("files") ?: error("Missing file list")
                    require(rows.size in 1..64)
                    val files = rows.map { row ->
                        val path = row["path"] as? String ?: error("Missing relative path")
                        val size = (row["sizeBytes"] as? Number)?.toLong() ?: error("Missing file size")
                        require(path.length <= 240 && path.split('/').all {
                            it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+"))
                        })
                        require(size in 1..(32L * 1024 * 1024 * 1024))
                        Asset(path, size)
                    }
                    require(files.map { it.path }.toSet().size == files.size)
                    require(files.sumOf { it.bytes } <= 64L * 1024 * 1024 * 1024)
                    pending = Operation(result, files)
                    picker = pending
                    activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, REQUEST)
                } catch (_: Exception) {
                    pending = null
                    picker = null
                    result.error("invalid_import", "Unable to open model directory picker", null)
                }
            }
            else -> result.notImplemented()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val op = picker ?: return true
        picker = null
        if (pending !== op || op.cancelled.get()) return true
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pending = null; op.result.success(null); return true
        }
        executor.execute {
            val staging = File(activity.filesDir, "minicpm_import/${UUID.randomUUID()}")
            try {
                check(!op.cancelled.get())
                check(activity.filesDir.usableSpace > op.files.sumOf { it.bytes } + 128L * 1024 * 1024)
                // Reap only abandoned, app-owned staging directories older than a day.
                val importRoot = File(activity.filesDir, "minicpm_import")
                importRoot.listFiles()?.filter {
                    it.isDirectory && it.name.matches(Regex("[0-9a-f-]{36}")) &&
                        it.canonicalFile.parentFile == importRoot.canonicalFile &&
                        System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
                }?.take(8)?.forEach { it.deleteRecursively() }
                check(staging.mkdirs())
                for (asset in op.files) {
                    check(!op.cancelled.get())
                    var current = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    val segments = asset.path.split('/')
                    for ((index, name) in segments.withIndex()) {
                        current = child(uri, current, name, index < segments.lastIndex)
                    }
                    val target = File(staging, asset.path)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    activity.contentResolver.openInputStream(current).use { input ->
                        check(input != null)
                        target.outputStream().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var count = 0L
                            while (true) {
                                check(!op.cancelled.get())
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                check(count <= asset.bytes)
                                output.write(buffer, 0, read)
                            }
                            check(count == asset.bytes)
                        }
                    }
                }
                activity.runOnUiThread {
                    if (pending === op && !op.cancelled.get()) {
                        pending = null; op.result.success(staging.absolutePath)
                    } else staging.deleteRecursively()
                }
            } catch (_: Exception) {
                staging.deleteRecursively()
                activity.runOnUiThread {
                    if (pending === op) {
                        pending = null
                        op.result.error("import_failed", "Model import failed: check files, sizes and free storage", null)
                    }
                }
            }
        }
        return true
    }

    private fun child(tree: Uri, parent: Uri, name: String, directory: Boolean): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        var found: String? = null
        activity.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ), null, null, null).use { cursor ->
            check(cursor != null)
            while (cursor.moveToNext()) {
                if (cursor.getString(1) != name) continue
                check(found == null) // Ambiguous provider names are not safe to import.
                check((cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) == directory)
                found = cursor.getString(0)
            }
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, found ?: error("Missing asset"))
    }

    fun cancel() {
        val op = pending ?: return
        op.cancelled.set(true)
        pending = null
        op.result.success(null)
    }

    fun detach() { cancel(); executor.shutdown() }
    companion object { const val REQUEST = 1002 }
}

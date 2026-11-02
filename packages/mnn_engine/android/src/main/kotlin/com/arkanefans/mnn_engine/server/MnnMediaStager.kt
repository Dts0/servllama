package com.arkanefans.mnn_engine.server

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal class MnnMediaStager(
    context: Context,
    private val logStore: MnnLogStore,
) {
    private val mediaRoot = File(context.filesDir, "mnn_test/runtime/media")

    fun stage(requestId: String, messages: List<JsonObject>): StagedMessages {
        val requestDir = File(mediaRoot, requestId)
        check(requestDir.mkdirs()) { "Failed to create request media directory." }
        var imageIndex = 0
        var totalPixels = 0L
        return try {
            val prepared = messages.map { message ->
                val copy = message.deepCopy()
                val content = copy.get("content")
                if (content?.isJsonArray == true) {
                    val text = StringBuilder()
                    content.asJsonArray.forEach { part ->
                        val item = part.asJsonObject
                        when (item.get("type")?.asString) {
                            "text" -> text.append(item.get("text").asString)
                            "image_url" -> {
                                require(imageIndex < MAX_IMAGES) {
                                    "A request may contain at most $MAX_IMAGES images."
                                }
                                val image = item.getAsJsonObject("image_url")
                                val staged = stageImage(
                                    requestDir,
                                    image.get("url").asString,
                                    imageIndex++,
                                )
                                totalPixels += staged.pixels
                                require(totalPixels <= MAX_TOTAL_PIXELS) {
                                    "Total image pixels exceed 24 megapixels."
                                }
                                text.append("<img>").append(staged.file.absolutePath).append("</img>\n")
                            }
                        }
                    }
                    copy.addProperty("content", text.toString())
                } else if (content?.isJsonPrimitive == true) {
                    rejectClientMediaTags(content.asString)
                }
                copy
            }
            if (imageIndex > 0) logStore.debug(TAG, "Staged $imageIndex image(s) for $requestId")
            StagedMessages(prepared, requestDir)
        } catch (error: Throwable) {
            requestDir.deleteRecursively()
            throw error
        }
    }

    fun cleanupStale() {
        if (!mediaRoot.exists()) return
        mediaRoot.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory) entry.deleteRecursively()
        }
    }

    private fun stageImage(requestDir: File, dataUri: String, index: Int): StagedImage {
        val comma = dataUri.indexOf(',')
        require(comma > 0) { "Invalid image data URI." }
        val header = dataUri.substring(0, comma).lowercase()
        val mime = when {
            header == "data:image/jpeg;base64" -> "image/jpeg"
            header == "data:image/png;base64" -> "image/png"
            else -> throw IllegalArgumentException("Only JPEG/PNG Base64 data image URLs are supported.")
        }
        val bytes = runCatching {
            Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
        }.getOrElse { throw IllegalArgumentException("Image Base64 data is invalid.", it) }
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) {
            "Image data must be between 1 byte and 32 MiB."
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outMimeType == mime && options.outWidth > 0 && options.outHeight > 0) {
            "Image header does not match its MIME type or dimensions are invalid."
        }
        require(maxOf(options.outWidth, options.outHeight) <= MAX_IMAGE_EDGE) {
            "Image longest edge exceeds 8192 pixels."
        }
        val pixels = options.outWidth.toLong() * options.outHeight.toLong()
        require(pixels <= MAX_IMAGE_PIXELS) { "Image exceeds 24 megapixels." }
        val extension = if (mime == "image/png") "png" else "jpg"
        val file = File(requestDir, "image-$index.$extension")
        val partial = File(requestDir, ".image-$index.part")
        FileOutputStream(partial).use { it.write(bytes) }
        check(partial.renameTo(file)) { "Failed to commit staged image." }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(8)
        logStore.debug(TAG, "Staged $mime ${options.outWidth}x${options.outHeight} ${bytes.size} bytes sha=$digest")
        return StagedImage(file, pixels)
    }

    private fun rejectClientMediaTags(content: String) {
        require(!content.contains("<img>", ignoreCase = true) &&
            !content.contains("<audio>", ignoreCase = true) &&
            !content.contains("<video>", ignoreCase = true)) {
            "Inline media tags are not accepted in text content."
        }
    }

    internal data class StagedMessages(
        val messages: List<JsonObject>,
        private val requestDir: File,
    ) : AutoCloseable {
        override fun close() {
            requestDir.deleteRecursively()
        }
    }

    private data class StagedImage(val file: File, val pixels: Long)

    private companion object {
        const val TAG = "media"
        const val MAX_IMAGES = 2
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 24L * 1024L * 1024L
        const val MAX_TOTAL_PIXELS = 24L * 1024L * 1024L
        const val MAX_IMAGE_EDGE = 8192
    }
}

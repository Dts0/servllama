package com.arkanefans.mnn_engine.runtime

import com.arkanefans.mnn_engine.MnnEngineOperationException

enum class MnnBackend(val wireName: String) {
    CPU("cpu"), OPENCL("opencl"), VULKAN("vulkan"), HEXAGON("hexagon");
}

enum class MnnPrecision(val wireName: String) {
    LOW("low"), HIGH("high");

    companion object {
        fun fromWire(value: String): MnnPrecision {
            return entries.firstOrNull { it.wireName == value }
                ?: throw MnnEngineOperationException(
                    "invalid_argument",
                    "Unsupported MNN precision: $value",
                )
        }
    }
}

data class MnnLoadOptions(
    val backend: MnnBackend = MnnBackend.CPU,
    val useMmap: Boolean = DEFAULT_USE_MMAP,
    val precision: MnnPrecision = DEFAULT_PRECISION,
    val threadNum: Int = DEFAULT_THREAD_NUM,
) {
    companion object {
        const val MIN_THREAD_NUM = 1
        const val MAX_THREAD_NUM = 8
        const val DEFAULT_THREAD_NUM = 4
        const val DEFAULT_USE_MMAP = false
        val DEFAULT_PRECISION = MnnPrecision.LOW

        fun fromMap(options: Map<*, *>?): MnnLoadOptions {
            if (options == null) return MnnLoadOptions()
            val backend = when (val value = options["backend"]) {
                null -> MnnBackend.CPU
                is String -> MnnBackend.entries.firstOrNull { it.wireName == value }
                    ?: throw MnnEngineOperationException(
                        "invalid_backend",
                        "Unsupported MNN backend: $value",
                    )
                else -> throw MnnEngineOperationException(
                    "invalid_backend",
                    "Unsupported MNN backend: $value",
                )
            }
            val useMmap = when (val value = options["useMmap"]) {
                null -> DEFAULT_USE_MMAP
                is Boolean -> value
                else -> throw MnnEngineOperationException(
                    "invalid_argument",
                    "useMmap must be a boolean.",
                )
            }
            val precision = when (val value = options["precision"]) {
                null -> DEFAULT_PRECISION
                is String -> MnnPrecision.fromWire(value)
                else -> throw MnnEngineOperationException(
                    "invalid_argument",
                    "Unsupported MNN precision: $value",
                )
            }
            val threadNum = when (val value = options["threadNum"]) {
                null -> DEFAULT_THREAD_NUM
                is Number -> value.toInt()
                else -> throw MnnEngineOperationException(
                    "invalid_argument",
                    "threadNum must be an integer.",
                )
            }
            if (threadNum !in MIN_THREAD_NUM..MAX_THREAD_NUM) {
                throw MnnEngineOperationException(
                    "invalid_argument",
                    "threadNum must be between $MIN_THREAD_NUM and $MAX_THREAD_NUM.",
                )
            }
            return MnnLoadOptions(
                backend = backend,
                useMmap = useMmap,
                precision = precision,
                threadNum = threadNum,
            )
        }
    }
}

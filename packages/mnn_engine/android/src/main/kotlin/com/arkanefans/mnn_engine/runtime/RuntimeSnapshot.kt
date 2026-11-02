package com.arkanefans.mnn_engine.runtime

data class RuntimeSnapshot(
    val revision: Long = 0,
    val engineState: String = "uninitialized",
    val modelState: String = "unloaded",
    val serverState: String = "stopped",
    val generationState: String = "idle",
    val activeModel: Map<String, Any?>? = null,
    val server: Map<String, Any?>? = null,
    val lastError: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "revision" to revision,
        "engineState" to engineState,
        "modelState" to modelState,
        "serverState" to serverState,
        "generationState" to generationState,
        "activeModel" to activeModel,
        "server" to server,
        "lastError" to lastError,
    )
}

package com.arkanefans.mnn_engine.logging

data class MnnLogEntry(
    val sequence: Long,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sequence" to sequence,
        "timestamp" to timestamp,
        "level" to level,
        "tag" to tag,
        "message" to message,
    )
}

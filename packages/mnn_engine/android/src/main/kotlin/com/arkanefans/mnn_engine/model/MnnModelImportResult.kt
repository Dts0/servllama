package com.arkanefans.mnn_engine.model

data class MnnModelImportResult(
    val requestedModelName: String,
    val model: MnnModelInfo,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "requestedModelName" to requestedModelName,
        "model" to model.toMap(),
    )
}

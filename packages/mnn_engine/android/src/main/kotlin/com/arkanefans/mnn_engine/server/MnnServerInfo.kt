package com.arkanefans.mnn_engine.server

data class MnnServerInfo(
    val running: Boolean,
    val bindMode: String,
    val bindAddress: String,
    val port: Int,
    val localBaseUrl: String,
    val advertisedUrls: List<String>,
    val requiresApiKey: Boolean,
    val startedAt: Long?,
    val startDurationMs: Long?,
) {
    val host: String
        get() = bindAddress

    val baseUrl: String
        get() = localBaseUrl

    fun toMap(): Map<String, Any?> = mapOf(
        "running" to running,
        "host" to bindAddress,
        "bindMode" to bindMode,
        "bindAddress" to bindAddress,
        "port" to port,
        "baseUrl" to localBaseUrl,
        "localBaseUrl" to localBaseUrl,
        "advertisedUrls" to advertisedUrls,
        "requiresApiKey" to requiresApiKey,
        "startedAt" to startedAt,
        "startDurationMs" to startDurationMs,
    )
}

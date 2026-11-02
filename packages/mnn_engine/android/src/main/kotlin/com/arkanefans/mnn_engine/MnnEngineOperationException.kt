package com.arkanefans.mnn_engine

class MnnEngineOperationException(
    val code: String,
    message: String,
    val details: Map<String, Any?>? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

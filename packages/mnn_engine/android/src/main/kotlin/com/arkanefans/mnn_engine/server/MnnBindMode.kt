package com.arkanefans.mnn_engine.server

enum class MnnBindMode(val wireName: String, val host: String) {
    LOOPBACK("loopback", "127.0.0.1"),
    ALL_INTERFACES("allInterfaces", "0.0.0.0");

    companion object {
        fun parse(value: String?): MnnBindMode = when (value) {
            null, "loopback" -> LOOPBACK
            "allInterfaces" -> ALL_INTERFACES
            else -> throw IllegalArgumentException("Unsupported MNN bind mode: $value")
        }
    }
}

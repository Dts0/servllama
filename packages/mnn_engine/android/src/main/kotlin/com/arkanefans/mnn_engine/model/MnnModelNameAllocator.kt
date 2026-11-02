package com.arkanefans.mnn_engine.model

internal object MnnModelNameAllocator {
    fun nextAvailable(
        requestedModelName: String,
        unavailableNames: Collection<String>,
    ): String {
        val occupied = unavailableNames.mapNotNullTo(mutableSetOf()) { name ->
            name.trim().takeIf(String::isNotEmpty)?.lowercase()
        }
        if (requestedModelName.lowercase() !in occupied) return requestedModelName
        var suffix = 2
        while (true) {
            val candidate = "$requestedModelName ($suffix)"
            if (candidate.lowercase() !in occupied) return candidate
            suffix += 1
        }
    }
}

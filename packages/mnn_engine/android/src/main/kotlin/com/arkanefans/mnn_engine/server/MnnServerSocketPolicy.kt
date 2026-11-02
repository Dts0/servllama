package com.arkanefans.mnn_engine.server

import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EngineConnectorBuilder
import java.net.ServerSocket

/**
 * Socket settings shared by the real CIO listener and the optional diagnostic
 * port probe. Keeping them in one place prevents the probe from disagreeing
 * with the socket that actually serves requests.
 */
internal object MnnServerSocketPolicy {
    const val reuseAddress: Boolean = true

    fun configure(
        configuration: CIOApplicationEngine.Configuration,
        host: String,
        port: Int,
    ) {
        configuration.reuseAddress = reuseAddress
        configuration.connectors.add(EngineConnectorBuilder().apply {
            this.host = host
            this.port = port
        })
    }

    fun probe(host: String, port: Int): String? {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = reuseAddress
                socket.bind(java.net.InetSocketAddress(host, port))
            }
            null
        } catch (error: Exception) {
            error.message ?: error.javaClass.simpleName
        }
    }

    fun isAddressAlreadyInUse(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            message.contains("EADDRINUSE", ignoreCase = true) ||
                message.contains("Address already in use", ignoreCase = true)
        }
    }
}

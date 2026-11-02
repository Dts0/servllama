package com.arkanefans.mnn_engine.server

import io.ktor.server.cio.CIOApplicationEngine
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MnnServerSocketPolicyTest {
    @Test
    fun configuresCioWithAddressReuseAndTheRequestedConnector() {
        val configuration = CIOApplicationEngine.Configuration()

        MnnServerSocketPolicy.configure(configuration, "127.0.0.1", 18081)

        assertTrue(configuration.reuseAddress)
        assertTrue(configuration.connectors.any { connector ->
            connector.host == "127.0.0.1" && connector.port == 18081
        })
    }

    @Test
    fun probeRejectsAnActivelyBoundPort() {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val message = MnnServerSocketPolicy.probe(
                "127.0.0.1",
                listener.localPort,
            )

            assertNotNull(message)
        }
    }

    @Test
    fun addressInUseErrorsAreRecognizedThroughWrappedCauses() {
        val error = IllegalStateException(
            "wrapper",
            java.net.BindException("bind failed: EADDRINUSE (Address already in use)"),
        )

        assertTrue(MnnServerSocketPolicy.isAddressAlreadyInUse(error))
        assertFalse(
            MnnServerSocketPolicy.isAddressAlreadyInUse(
                IllegalStateException("permission denied"),
            ),
        )
    }
}

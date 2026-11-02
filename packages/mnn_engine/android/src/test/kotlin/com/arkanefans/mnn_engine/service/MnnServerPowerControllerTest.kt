package com.arkanefans.mnn_engine.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MnnServerPowerControllerTest {
    @Test
    fun loopbackKeepsTheCpuAwakeWithoutHoldingWifi() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val controller = controller(cpu, wifi)

        controller.acquire(keepWifiAwake = false)

        assertTrue(cpu.isHeld)
        assertFalse(wifi.isHeld)

        controller.release()

        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
    }

    @Test
    fun lanServingKeepsBothCpuAndWifiAwake() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val controller = controller(cpu, wifi)

        controller.acquire(keepWifiAwake = true)

        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)
    }

    @Test
    fun failedWifiAcquisitionRollsBackTheCpuLock() {
        val cpu = FakeLock()
        val wifi = FakeLock(failOnAcquire = true)
        val controller = controller(cpu, wifi)

        assertFailsWith<IllegalStateException> {
            controller.acquire(keepWifiAwake = true)
        }

        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
    }

    private fun controller(
        cpu: FakeLock,
        wifi: FakeLock,
    ) = MnnServerPowerController(
        cpuWakeLock = cpu,
        wifiLock = wifi,
        onWarning = {},
    )

    private class FakeLock(
        private val failOnAcquire: Boolean = false,
    ) : MnnServerPowerController.ManagedLock {
        override var isHeld: Boolean = false
            private set

        override fun acquire() {
            if (failOnAcquire) throw IllegalStateException("acquire failed")
            isHeld = true
        }

        override fun release() {
            isHeld = false
        }
    }
}

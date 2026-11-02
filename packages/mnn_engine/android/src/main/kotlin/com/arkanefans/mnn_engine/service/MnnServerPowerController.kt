package com.arkanefans.mnn_engine.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.arkanefans.mnn_engine.logging.MnnLogStore

internal class MnnServerPowerController internal constructor(
    private val cpuWakeLock: ManagedLock,
    private val wifiLock: ManagedLock?,
    private val onWarning: (String) -> Unit,
) {
    constructor(context: Context, logStore: MnnLogStore) : this(
        cpuWakeLock = createCpuWakeLock(context),
        wifiLock = createWifiLock(context),
        onWarning = { message -> logStore.warn(TAG, message) },
    )

    @Synchronized
    fun acquire(keepWifiAwake: Boolean) {
        try {
            if (!cpuWakeLock.isHeld) {
                cpuWakeLock.acquire()
            }
            if (keepWifiAwake) {
                if (wifiLock == null) {
                    onWarning("Wi-Fi service is unavailable; background LAN connectivity cannot be guaranteed.")
                } else if (!wifiLock.isHeld) {
                    wifiLock.acquire()
                }
            } else {
                releaseLock(wifiLock, "Wi-Fi")
            }
        } catch (error: Throwable) {
            releaseInternal()
            throw error
        }
    }

    @Synchronized
    fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        releaseLock(wifiLock, "Wi-Fi")
        releaseLock(cpuWakeLock, "CPU wake")
    }

    private fun releaseLock(lock: ManagedLock?, label: String) {
        if (lock?.isHeld != true) return
        runCatching { lock.release() }.onFailure { error ->
            onWarning("Failed to release $label lock: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    internal interface ManagedLock {
        val isHeld: Boolean
        fun acquire()
        fun release()
    }

    private class CpuWakeLock(
        private val lock: PowerManager.WakeLock,
    ) : ManagedLock {
        override val isHeld: Boolean
            get() = lock.isHeld

        @SuppressLint("WakelockTimeout")
        override fun acquire() {
            lock.acquire()
        }

        override fun release() {
            lock.release()
        }
    }

    private class HighPerformanceWifiLock(
        private val lock: WifiManager.WifiLock,
    ) : ManagedLock {
        override val isHeld: Boolean
            get() = lock.isHeld

        override fun acquire() {
            lock.acquire()
        }

        override fun release() {
            lock.release()
        }
    }

    private companion object {
        const val TAG = "power"

        fun createCpuWakeLock(context: Context): ManagedLock {
            val appContext = context.applicationContext
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: throw IllegalStateException("Android PowerManager is unavailable.")
            return CpuWakeLock(
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${appContext.packageName}:mnn_server_cpu",
                ).apply { setReferenceCounted(false) },
            )
        }

        @Suppress("DEPRECATION")
        fun createWifiLock(context: Context): ManagedLock? {
            val appContext = context.applicationContext
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            return HighPerformanceWifiLock(
                wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "${appContext.packageName}:mnn_server_wifi",
                ).apply { setReferenceCounted(false) },
            )
        }
    }
}

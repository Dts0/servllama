package com.arkanefans.mnn_engine.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Request admission remains held until native work has actually returned. */
internal class MnnRequestGate {
    enum class Admission { ACCEPTED, BUSY, STOPPING }
    @Volatile var isOpen = true
        private set
    private class Request(val id: String) {
        @Volatile var cancelled = false
        val finished = CountDownLatch(1)
    }
    @Volatile private var current: Request? = null
    @Synchronized fun acquire(requestId: String): Admission = when {
        !isOpen -> Admission.STOPPING
        current != null -> Admission.BUSY
        else -> Admission.ACCEPTED.also { current = Request(requestId) }
    }
    // This read must not take the gate lock: reset holds the native lock.
    fun allows(requestId: String): Boolean {
        val r = current
        return isOpen && r != null && r.id == requestId && !r.cancelled
    }
    @Synchronized fun cancel(requestId: String, cancelNative: () -> Unit): Boolean {
        val r = current
        if (r == null || r.id != requestId || r.cancelled) return false
        r.cancelled = true
        // Keep ownership locked through the signal: a late disconnect must
        // never cancel a later request using the same native session.
        cancelNative()
        return true
    }
    @Synchronized fun release(requestId: String) {
        val r = current ?: return
        if (r.id != requestId) return
        current = null
        r.finished.countDown()
    }
    @Synchronized fun close() { isOpen = false }

    fun awaitIdle(timeoutMillis: Long): Boolean {
        check(!isOpen) { "Close admission before waiting for native work." }
        return current?.finished?.await(timeoutMillis, TimeUnit.MILLISECONDS) ?: true
    }
}

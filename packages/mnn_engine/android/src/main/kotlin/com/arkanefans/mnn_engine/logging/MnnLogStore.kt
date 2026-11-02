package com.arkanefans.mnn_engine.logging

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class MnnLogStore(
    private val capacity: Int = 1000,
) {
    private val entries = ArrayDeque<MnnLogEntry>(capacity)
    private val sequence = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<(MnnLogEntry) -> Unit>()

    @Synchronized
    fun snapshot(): List<MnnLogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    fun addListener(listener: (MnnLogEntry) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (MnnLogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun debug(tag: String, message: String) = append("debug", tag, message)
    fun info(tag: String, message: String) = append("info", tag, message)
    fun warn(tag: String, message: String) = append("warning", tag, message)

    fun error(tag: String, message: String, error: Throwable? = null) {
        append("error", tag, buildString {
            append(message)
            error?.message?.let { append(": ").append(it) }
        })
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = MnnLogEntry(
            sequence = sequence.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        synchronized(this) {
            while (entries.size >= capacity) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
        when (level) {
            "debug" -> Log.d(tag, message)
            "warning" -> Log.w(tag, message)
            "error" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
        listeners.forEach { listener -> listener(entry) }
    }
}

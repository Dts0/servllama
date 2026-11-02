package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MnnRequestGateTest {
    @Test
    fun cancellationCannotAffectAnotherRequestAndDoesNotReleaseAdmission() {
        val gate = MnnRequestGate()
        var nativeCancels = 0
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("first"))
        assertFalse(gate.cancel("external") { nativeCancels++ })
        assertEquals(0, nativeCancels)
        assertEquals(true, gate.cancel("first") { nativeCancels++ })
        assertFalse(gate.allows("first"))
        assertFalse(gate.cancel("first") { error("Cancellation is idempotent") })
        assertEquals(MnnRequestGate.Admission.BUSY, gate.acquire("next"))
        gate.release("first")
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("next"))
        gate.release("first")
        assertTrue(gate.allows("next"))
        assertFalse(gate.cancel("first") { error("A late disconnect must not cancel the next request") })
        assertEquals(1, nativeCancels)
    }

    @Test
    fun unrelatedCancellationIsNotSavedForFutureRequests() {
        val gate = MnnRequestGate()
        assertFalse(gate.cancel("unknown") { error("Native must not be touched") })
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("unknown"))
        assertTrue(gate.allows("unknown"))
    }

    @Test
    fun onlyOneRequestIsAdmittedAndClosingTakesPrecedenceOverBusy() {
        val gate = MnnRequestGate()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("first"))
        assertEquals(MnnRequestGate.Admission.BUSY, gate.acquire("second"))
        gate.close()
        assertEquals(MnnRequestGate.Admission.STOPPING, gate.acquire("second"))
        assertFalse(gate.isOpen)
        assertFalse(gate.awaitIdle(1))
        gate.release("first")
        assertTrue(gate.awaitIdle(1))
    }

    @Test
    fun finishingAnOldRequestCannotReopenItOrReleaseTheNewServersRequest() {
        val old = MnnRequestGate()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, old.acquire("old"))
        old.close()
        val restarted = MnnRequestGate()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, restarted.acquire("new"))
        old.release("old")
        assertEquals(MnnRequestGate.Admission.STOPPING, old.acquire("old"))
        assertEquals(MnnRequestGate.Admission.BUSY, restarted.acquire("another"))
        restarted.release("new")
        assertEquals(MnnRequestGate.Admission.ACCEPTED, restarted.acquire("another"))
    }
}

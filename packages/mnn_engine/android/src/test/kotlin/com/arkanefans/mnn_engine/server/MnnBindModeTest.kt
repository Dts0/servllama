package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MnnBindModeTest {
    @Test
    fun parsesModesAndHosts() {
        assertEquals(MnnBindMode.LOOPBACK, MnnBindMode.parse(null))
        assertEquals("127.0.0.1", MnnBindMode.parse("loopback").host)
        assertEquals("0.0.0.0", MnnBindMode.parse("allInterfaces").host)
        assertFailsWith<IllegalArgumentException> { MnnBindMode.parse("localhost") }
    }
}

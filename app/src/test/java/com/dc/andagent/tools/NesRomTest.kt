package com.dc.andagent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NesRomTest {
    @Test fun headerValidation() {
        val valid = byteArrayOf(78, 69, 83, 26, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(validNesHeader(valid))
        assertFalse(validNesHeader(valid.copyOfRange(0, 4)))
        valid[3] = 0
        assertFalse(validNesHeader(valid))
    }
}

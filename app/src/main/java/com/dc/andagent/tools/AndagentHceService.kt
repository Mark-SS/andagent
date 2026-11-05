package com.dc.andagent.tools

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * #7 optional HCE card emulation.
 *
 * Registered for the test AID F0010203040506 in res/xml/hce_aid_list.xml.
 * SELECT returns a short identifier; any other instruction answers 6A82.
 */
class AndagentHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return SW_FILE_NOT_FOUND
        return when {
            isSelectAid(commandApdu) -> IDENTIFIER + SW_OK
            (commandApdu[1].toInt() and 0xff) == 0x00 -> IDENTIFIER + SW_OK
            else -> SW_FILE_NOT_FOUND
        }
    }

    override fun onDeactivated(reason: Int) { /* nothing to release */ }

    private fun isSelectAid(apdu: ByteArray): Boolean {
        if ((apdu[0].toInt() and 0xff) != 0x00 || (apdu[1].toInt() and 0xff) != 0xA4 ||
            (apdu[2].toInt() and 0xff) != 0x04
        ) return false
        val aid = AID
        for (i in aid.indices) {
            val at = 5 + i
            if (at >= apdu.size || apdu[at] != aid[i]) return false
        }
        return true
    }

    companion object {
        private val AID = byteArrayOf(
            0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        )
        private val IDENTIFIER = "ANDAGENT".toByteArray(Charsets.US_ASCII)
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }
}

package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class SdpServerTest {

    @Test
    fun testIap2ServiceSearchAttributeQuery() {
        val identity = UsbBtIdentity(name = "CarPlay-Test")
        val records = SdpCodec.defaultRecords(identity)
        val server = SdpServer(records)

        // Construct SDP ServiceSearchAttributeRequest (PDU 6) querying iAP2 UUID
        val pattern = DataElement.Sequence(DataElement.Uuid.of128(UsbBtIdentity.IAP2_ACCESSORY_UUID)).encode()
        val attrRanges = DataElement.Sequence(
            DataElement.UInt(4, 0x0000FFFFL) // query all attributes 0x0000..0xFFFF
        ).encode()

        val reqPayload = ByteArrayOutputStream()
        reqPayload.write(pattern)
        reqPayload.write(0x02) // MaxAttributeByteCount (512 bytes LE: 0x0200)
        reqPayload.write(0x00)
        reqPayload.write(attrRanges)
        reqPayload.write(0x00) // Continuation state length = 0

        val request = ByteArrayOutputStream()
        request.write(SdpCodec.SERVICE_SEARCH_ATTRIBUTE_REQUEST)
        request.write(0x00) // TID = 0x0001
        request.write(0x01)
        val params = reqPayload.toByteArray()
        request.write((params.size ushr 8) and 0xFF)
        request.write(params.size and 0xFF)
        request.write(params)

        val response = server.handle(request.toByteArray(), peerMtu = 1000)

        assertNotNull(response)
        assertTrue(response.size >= 9)
        // PDU ID must be 7 (SERVICE_SEARCH_ATTRIBUTE_RESPONSE)
        assertEquals(SdpCodec.SERVICE_SEARCH_ATTRIBUTE_RESPONSE, response[0].toInt() and 0xFF)
        // TID must match 0x0001
        assertEquals(0x00, response[1].toInt() and 0xFF)
        assertEquals(0x01, response[2].toInt() and 0xFF)

        // AttributeListByteCount (2 bytes BE)
        val attrByteCount = ((response[5].toInt() and 0xFF) shl 8) or (response[6].toInt() and 0xFF)
        assertTrue(attrByteCount > 0)

        // Decode the returned attribute list sequence
        val (elem, _) = SdpCodec.decode(response, 7)
        assertTrue(elem is DataElement.Sequence)
        val seq = elem as DataElement.Sequence
        assertTrue("Must contain at least one matched service record", seq.items.isNotEmpty())

        // Verify that the record contains the service name "Wireless iAPv2"
        val recordSeq = seq.items[0] as DataElement.Sequence
        val textElem = recordSeq.items.filterIsInstance<DataElement.Text>().firstOrNull()
        assertNotNull(textElem)
        assertEquals("Wireless iAPv2", String(textElem!!.value, Charsets.UTF_8))
    }
}

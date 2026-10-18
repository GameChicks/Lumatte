package com.lumixfilmmirror.camera

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class CameraProtocolTest {
    @Test fun cameraAddressesRejectInternetAndHostnames() {
        for (host in listOf("example.com", "8.8.8.8", "127.0.0.1", "0.0.0.0", "224.0.0.1", "::1", "2001:4860::8888", "192.168.1.999"))
            assertThrows(IllegalArgumentException::class.java) { CameraAddress.literal(host) }
        for (host in listOf("192.168.54.1", "10.0.0.2", "172.16.0.2", "169.254.2.3", "fc00::1", "fe80::1"))
            assertNotNull(CameraAddress.literal(host))
    }
    @Test fun descriptionCannotCarryCredentialsOrChangeScheme() {
        for (uri in listOf("https://192.168.1.1/x", "http://user@192.168.1.1/x", "http://192.168.1.1/x#secret"))
            assertThrows(IllegalArgumentException::class.java) { CameraAddress.requireDescription(URI(uri)) }
        val original = URI("http://192.168.1.1:60606/a%20b?q=%2F")
        assertEquals("/a%20b", CameraAddress.withAuthority(original,"192.168.1.2").rawPath)
        assertEquals("q=%2F", CameraAddress.withAuthority(original,"192.168.1.2").rawQuery)
    }
    @Test fun ssidsPreserveSpacesAndUnicodeButRejectTruncation() {
        for (ssid in listOf(" S5-C70616 ", "相机 WiFi", "LUMIX-123"))
            assertEquals(ssid, LumixBleProtocol.decodeSsid(ssid.toByteArray()+byteArrayOf(0,0)))
        for (ssid in listOf("相".repeat(11), "bad\nname", "\uD800", " "))
            assertThrows(IllegalArgumentException::class.java) { CameraRouterSsid.parse(ssid) }
        assertThrows(IllegalArgumentException::class.java) { LumixBleProtocol.decodeSsid(byteArrayOf(0xc3.toByte())) }
    }
    @Test fun bluetoothChallengeEncodingHandlesZeroKeyAndBounds() {
        for (key in listOf(0, 0x12345678, -1)) {
            val encoded = LumixBleProtocol.encode("password123".toByteArray(), key)
            assertEquals(20, encoded.size)
            assertEquals("password123", LumixBleProtocol.decodePassword(encoded,key))
        }
        assertThrows(IllegalArgumentException::class.java) { LumixBleProtocol.encode(ByteArray(20),1) }
        assertThrows(IllegalArgumentException::class.java) { LumixBleProtocol.challenge(ByteArray(3)) }
    }
    @Test fun protocolInfoKeepsQuotedFilenameSeparators() {
        val info = CameraProtocolInfo.parse("http-get:*:image/jpeg:PANASONIC.COM_PN=CAM_ORG;OriginalFileName=\"a;b:c.jpg\"")
        assertEquals("image/jpeg",info.mimeType)
        assertEquals("CAM_ORG",info.profile)
        assertEquals("a;b:c.jpg",info.originalName)
        assertEquals("CAM_RAW", CameraProtocolInfo.parse("http-get:*:image/x-panasonic-rw2;PANASONIC.COM_PN=CAM_RAW").profile)
    }
    @Test fun transferModesDoNotBecomeOrdinaryBrowsing() {
        assertFalse(CameraAccessMode.fromReply("ok").incoming)
        assertTrue(CameraAccessMode.fromReply("upload_bt").incoming)
        assertThrows(IllegalArgumentException::class.java) { CameraIncomingDirectory(CameraAccessMode.BROWSING,"0") }
        assertThrows(IllegalArgumentException::class.java) { CameraIncomingDirectory(CameraAccessMode.MARKED_TRANSFER,"0") }
    }
}

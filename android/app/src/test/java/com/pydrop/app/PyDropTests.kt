package com.pydrop.app

import org.junit.Test
import org.junit.Assert.*

class DeviceTest {
    @Test
    fun deviceCreation() {
        val device = Device("id123", "TestDevice", "192.168.1.100", 8080)
        assertEquals("id123", device.id)
        assertEquals("TestDevice", device.name)
        assertEquals("192.168.1.100", device.address)
        assertEquals(8080, device.httpPort)
    }

    @Test
    fun deviceToString() {
        val device = Device("id123", "TestDevice", "192.168.1.100", 8080)
        val str = device.toString()
        assertTrue(str.contains("TestDevice"))
        assertTrue(str.contains("192.168.1.100"))
    }
}

class DeviceInfoTest {
    @Test
    fun deviceInfoCreation() {
        val info = DeviceInfo("device-id", "MyPhone", "192.168.1.50")
        assertEquals("device-id", info.id)
        assertEquals("MyPhone", info.name)
        assertEquals("192.168.1.50", info.ip)
    }

    @Test
    fun deviceInfoCopy() {
        val original = DeviceInfo("id", "name", "ip")
        val copy = original.copy(name = "newName")
        assertEquals("id", copy.id)
        assertEquals("newName", copy.name)
        assertEquals("ip", copy.ip)
    }
}

class ReceivedFileTest {
    @Test
    fun receivedFileCreation() {
        val file = ReceivedFile("abc123", "test.pdf", 1024L, "1234567890", "/path/to/file")
        assertEquals("abc123", file.id)
        assertEquals("test.pdf", file.name)
        assertEquals(1024L, file.size)
        assertEquals("1234567890", file.time)
        assertEquals("/path/to/file", file.path)
    }
}

class PyDropEventTest {
    @Test
    fun fileReceivedEvent() {
        val event = PyDropEvent.FileReceived("file-id", "document.pdf", 2048L, "timestamp")
        assertEquals("file-id", event.id)
        assertEquals("document.pdf", event.name)
        assertEquals(2048L, event.size)
        assertEquals("timestamp", event.time)
    }
}

class FilenameSanitizationTest {
    @Test
    fun stripsPathComponents() {
        val rawName = "/home/user/Documents/../secret.txt"
        val filename = java.io.File(rawName).name
        assertEquals("secret.txt", filename)
    }

    @Test
    fun handlesEmptyName() {
        val rawName = ""
        val filename = java.io.File(rawName).name.ifBlank { "file_${System.currentTimeMillis()}" }
        assertTrue(filename.startsWith("file_"))
    }

    @Test
    fun handlesNullCharacter() {
        val rawName = "file\u0000name.txt"
        val filename = java.io.File(rawName).name
        assertFalse(filename.contains("\u0000"))
    }
}

class DiscoveryMessageTest {
    @Test
    fun parseValidDiscoveryMessage() {
        val message = "PYDROP_ANNOUNCE|device123|MyDevice|8080"
        val parts = message.split("|")
        
        assertTrue(message.startsWith("PYDROP_ANNOUNCE"))
        assertEquals(4, parts.size)
        assertEquals("device123", parts[1])
        assertEquals("MyDevice", parts[2])
        assertEquals("8080", parts[3])
    }

    @Test
    fun rejectInvalidDiscoveryMessage() {
        val message = "PYDROP_ANNOUNCE|device123|MyDevice" // missing port
        val parts = message.split("|")
        
        assertFalse(parts.size >= 4)
    }

    @Test
    fun rejectWrongPrefix() {
        val message = "OTHER|device123|MyDevice|8080"
        assertFalse(message.startsWith("PYDROP_ANNOUNCE"))
    }
}

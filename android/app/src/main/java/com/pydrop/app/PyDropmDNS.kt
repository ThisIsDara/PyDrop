package com.pydrop.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.*

class PyDropmDNS(
    private val deviceName: String,
    private val deviceId: String,
    private val localIp: String,
    private val httpPort: Int,
    private val onDeviceFound: (Device) -> Unit
) {

    private var listenSocket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "PyDropmDNS"
        // Both sides listen AND broadcast on the same port so packets cross platforms
        private const val DISCOVERY_PORT = 8766
        private const val MESSAGE_ANNOUNCE = "PYDROP_ANNOUNCE"
    }

    fun start() {
        isRunning = true
        scope.launch { listenForDevices() }
        scope.launch { broadcastPresence() }
    }

    private suspend fun listenForDevices() {
        try {
            listenSocket = DatagramSocket(DISCOVERY_PORT).apply {
                soTimeout = 2000
                broadcast = true
            }

            while (isRunning) {
                try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length).trim()
                    val senderIp = packet.address.hostAddress ?: continue

                    if (!message.startsWith(MESSAGE_ANNOUNCE)) continue

                    val parts = message.split("|")
                    if (parts.size < 4) continue

                    val remoteId = parts[1]
                    val remoteName = parts[2]
                    val remotePort = parts[3].toIntOrNull() ?: 8080

                    // Filter by device ID — more reliable than IP comparison
                    if (remoteId == deviceId) continue

                    val device = Device(remoteId, remoteName, senderIp, remotePort)
                    onDeviceFound(device)
                    Log.d(TAG, "Found device: $remoteName at $senderIp:$remotePort")

                } catch (_: java.net.SocketTimeoutException) {
                    // Normal — just loop again
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "Receive error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listen socket error", e)
        } finally {
            listenSocket?.close()
        }
    }

    private suspend fun broadcastPresence() {
        val message = "$MESSAGE_ANNOUNCE|$deviceId|$deviceName|$httpPort"
        val buffer = message.toByteArray()
        val address = InetAddress.getByName("255.255.255.255")

        while (isRunning) {
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.send(DatagramPacket(buffer, buffer.size, address, DISCOVERY_PORT))
                }
                Log.d(TAG, "Broadcast presence on port $DISCOVERY_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast error: ${e.message}")
            }
            delay(3000)
        }
    }

    fun discover() {
        scope.launch {
            try {
                val message = "$MESSAGE_ANNOUNCE|$deviceId|$deviceName|$httpPort"
                val buffer = message.toByteArray()
                val address = InetAddress.getByName("255.255.255.255")
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.send(DatagramPacket(buffer, buffer.size, address, DISCOVERY_PORT))
                }
                Log.d(TAG, "Sent immediate discovery burst")
            } catch (e: Exception) {
                Log.e(TAG, "Discover error: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        listenSocket?.close()
        scope.cancel()
    }
}

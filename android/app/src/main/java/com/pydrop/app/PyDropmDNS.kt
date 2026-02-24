package com.pydrop.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.*

class PyDropmDNS(
    private val deviceName: String,
    private val deviceId: String,
    private val localIp: String,
    private val httpPort: Int,
    private val context: Context,
    private val onDeviceFound: (Device) -> Unit,
    private val scope: CoroutineScope
) {

    private var listenSocket: DatagramSocket? = null

    companion object {
        private const val TAG = "PyDropmDNS"
        private const val DISCOVERY_PORT = 8766
        private const val MESSAGE_ANNOUNCE = "PYDROP_ANNOUNCE"
    }

    fun start() {
        // Acquire MulticastLock so the Wi-Fi chip doesn't silently drop
        // incoming UDP broadcast packets (critical on most Android devices).
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("PyDrop").also {
            it.setReferenceCounted(true)
            it.acquire()
        }

        isRunning = true
        scope.launch { listenForDevices() }
        scope.launch { broadcastPresence() }
    }

    private suspend fun listenForDevices() {
        try {
            // Use no-arg constructor so we can set ReuseAddress BEFORE bind.
            // DatagramSocket(port) binds immediately, making setReuseAddress a no-op.
            listenSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
                bind(InetSocketAddress(DISCOVERY_PORT))
            }

            Log.d(TAG, "Listening on UDP port $DISCOVERY_PORT")

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
            Log.e(TAG, "Listen socket error: ${e.message}", e)
        } finally {
            listenSocket?.close()
        }
    }

    private suspend fun broadcastPresence() {
        val message = "$MESSAGE_ANNOUNCE|$deviceId|$deviceName|$httpPort"
        val buffer = message.toByteArray()
        val address = InetAddress.getByName("255.255.255.255")

        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                while (isRunning) {
                    try {
                        sock.send(DatagramPacket(buffer, buffer.size, address, DISCOVERY_PORT))
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast error: ${e.message}")
                    }
                    delay(3000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast socket error: ${e.message}")
        }
    }

    fun broadcastPresenceBurst() {
        scope.launch {
            try {
                val message = "$MESSAGE_ANNOUNCE|$deviceId|$deviceName|$httpPort"
                val buffer = message.toByteArray()
                val address = InetAddress.getByName("255.255.255.255")
                // Send a few bursts for reliability
                repeat(3) {
                    DatagramSocket().use { sock ->
                        sock.broadcast = true
                        sock.send(DatagramPacket(buffer, buffer.size, address, DISCOVERY_PORT))
                    }
                    delay(100)
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
        multicastLock?.let { if (it.isHeld) it.release() }
    }
}

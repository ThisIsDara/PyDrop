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

    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "PyDropmDNS"
        private const val DISCOVERY_PORT = 8766
        private const val BROADCAST_PORT = 8767
        private const val MESSAGE_ANNOUNCE = "PYDROP_ANNOUNCE"
        private const val MESSAGE_DISCOVER = "PYDROP_DISCOVER"
    }

    fun start() {
        isRunning = true
        
        // Start listening for discovery packets
        scope.launch {
            listenForDevices()
        }
        
        // Broadcast our presence
        scope.launch {
            broadcastPresence()
        }
    }

    private suspend fun listenForDevices() {
        try {
            socket = DatagramSocket(DISCOVERY_PORT)
            socket?.soTimeout = 5000
            
            while (isRunning) {
                try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    val senderIp = packet.address.hostAddress
                    
                    if (senderIp != localIp && message.startsWith(MESSAGE_ANNOUNCE)) {
                        val parts = message.split("|")
                        if (parts.size >= 4) {
                            val remoteId = parts[1]
                            val remoteName = parts[2]
                            val remotePort = parts[3].toIntOrNull() ?: 8080
                            
                            if (remoteId != deviceId) {
                                val device = Device(remoteId, remoteName, senderIp, remotePort)
                                onDeviceFound(device)
                                Log.d(TAG, "Found device: $remoteName at $senderIp")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Timeout or error, continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listening", e)
        }
    }

    private suspend fun broadcastPresence() {
        while (isRunning) {
            try {
                val message = "$MESSAGE_ANNOUNCE|$deviceId|$deviceName|$httpPort"
                val buffer = message.toByteArray()
                
                // Broadcast to local network
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(buffer, buffer.size, address, BROADCAST_PORT)
                
                val sendSocket = DatagramSocket()
                sendSocket.broadcast = true
                sendSocket.send(packet)
                sendSocket.close()
                
                Log.d(TAG, "Broadcast presence: $deviceName")
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting", e)
            }
            
            delay(5000) // Broadcast every 5 seconds
        }
    }

    fun discover() {
        // Send discovery request
        scope.launch {
            try {
                val message = "$MESSAGE_DISCOVER|$deviceId"
                val buffer = message.toByteArray()
                
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(buffer, buffer.size, address, BROADCAST_PORT)
                
                val sendSocket = DatagramSocket()
                sendSocket.broadcast = true
                sendSocket.send(packet)
                sendSocket.close()
                
                Log.d(TAG, "Sent discovery request")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending discovery", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        scope.cancel()
    }
}

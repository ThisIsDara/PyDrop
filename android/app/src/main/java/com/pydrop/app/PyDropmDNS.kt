package com.pydrop.app

import android.util.Log
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class PyDropmDNS(
    private val deviceName: String,
    private val deviceId: String,
    private val localIp: String,
    private val httpPort: Int,
    private val onDeviceFound: (Device) -> Unit
) {

    private var jmdns: JmDNS? = null
    private val serviceType = "_pydrop._tcp.local."

    fun start() {
        try {
            val addr = InetAddress.getLocalHost()
            jmdns = JmDNS.create(addr, deviceName)
            
            // Register our service
            val info = javax.jmdns.ServiceInfo(
                serviceType,
                "$deviceName.$serviceType",
                httpPort,
                0,
                0,
                mapOf(
                    "deviceId" to deviceId,
                    "deviceName" to deviceName,
                    "httpPort" to httpPort.toString()
                )
            )
            jmdns?.registerService(info)
            
            // Add listener for discovering other devices
            jmdns?.addServiceListener(serviceType, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    Log.d("PyDropmDNS", "Service added: ${event.name}")
                }
                
                override fun serviceRemoved(event: ServiceEvent) {
                    Log.d("PyDropmDNS", "Service removed: ${event.name}")
                }
                
                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val props = info.properties
                    
                    val foundId = String(props?.get("deviceId") ?: ByteArray(0))
                    if (foundId != deviceId && foundId.isNotEmpty()) {
                        val name = String(props?.get("deviceName") ?: ByteArray(0)).ifEmpty { event.name }
                        val port = String(props?.get("httpPort") ?: "8080".toByteArray()).toIntOrNull() ?: 8080
                        
                        val address = info.inetAddresses.firstOrNull()?.hostAddress ?: return
                        
                        val device = Device(foundId, name, address, port)
                        onDeviceFound(device)
                        Log.d("PyDropmDNS", "Device found: $name at $address:$port")
                    }
                }
            })
            
            Log.d("PyDropmDNS", "mDNS started, service registered")
        } catch (e: Exception) {
            Log.e("PyDropmDNS", "Failed to start mDNS", e)
        }
    }

    fun discover() {
        // Re-query for services
        jmdns?.list(serviceType)
    }

    fun stop() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
            jmdns = null
        } catch (e: Exception) {
            Log.e("PyDropmDNS", "Error stopping mDNS", e)
        }
    }
}

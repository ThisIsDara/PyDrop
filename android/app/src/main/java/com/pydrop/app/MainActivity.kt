package com.pydrop.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pydrop.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val devices = mutableListOf<Device>()
    private lateinit var deviceAdapter: DeviceAdapter
    private var server: PyDropServer? = null
    private var mDNS: PyDropmDNS? = null
    private var deviceName: String = ""
    private var deviceId: String = ""
    private var localIp: String = ""
    private val httpPort: Int = 8080

    // Track which device was chosen before launching the file picker
    private var pendingTargetDevice: Device? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val target = pendingTargetDevice
        pendingTargetDevice = null
        if (target != null) {
            sendFileToDevice(target, uri)
        } else {
            showDevicePicker(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = UUID.randomUUID().toString().take(12)
        deviceName = android.os.Build.MODEL
        localIp = getLocalIpAddress()

        setupUI()
        startServer()
    }

    private fun setupUI() {
        binding.tvHostname.text = deviceName
        binding.tvIp.text = "$localIp:$httpPort"

        deviceAdapter = DeviceAdapter { device ->
            pendingTargetDevice = device
            pickFileLauncher.launch("*/*")
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        binding.btnSend.setOnClickListener {
            if (devices.isEmpty()) {
                toast("No devices found")
                return@setOnClickListener
            }
            pendingTargetDevice = null
            pickFileLauncher.launch("*/*")
        }

        binding.btnExit.setOnClickListener {
            finishAndRemoveTask()
        }
    }

    private fun startServer() {
        setStatus(ready = false, text = "STARTING...")

        lifecycleScope.launch {
            try {
                server = PyDropServer(httpPort, deviceId, deviceName, localIp, this@MainActivity) { event, data ->
                    runOnUiThread {
                        if (event == "file_received") {
                            toast("File received: ${data["name"]}")
                        }
                    }
                }
                server?.start()

                mDNS = PyDropmDNS(deviceName, deviceId, localIp, httpPort, this@MainActivity) { device ->
                    runOnUiThread { addDevice(device) }
                }
                mDNS?.start()

                setStatus(ready = true, text = "READY")

            } catch (e: Exception) {
                setStatus(ready = false, text = "ERROR")
                toast("Server error: ${e.message}")
            }
        }
    }

    private fun setStatus(ready: Boolean, text: String) {
        val color = ContextCompat.getColor(this, if (ready) R.color.green else R.color.red)
        binding.tvStatusDot.setTextColor(color)
        binding.tvStatusText.text = text
        binding.tvStatusText.setTextColor(color)
    }

    private fun addDevice(device: Device) {
        val existing = devices.indexOfFirst { it.id == device.id }
        if (existing >= 0) {
            devices[existing] = device
        } else {
            devices.add(device)
        }
        // submitList triggers DiffUtil on a background thread — no manual notify calls needed
        deviceAdapter.submitList(devices.toList())
        binding.tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDevicePicker(uri: Uri) {
        if (devices.isEmpty()) {
            toast("No devices found")
            return
        }
        if (devices.size == 1) {
            sendFileToDevice(devices[0], uri)
            return
        }
        val names = devices.map { "${it.name}  ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("SELECT DEVICE")
            .setItems(names) { _, which -> sendFileToDevice(devices[which], uri) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun sendFileToDevice(device: Device, uri: Uri) {
        lifecycleScope.launch {
            try {
                val fileName = getFileName(uri)
                val fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch
                val size = fmtSize(fileBytes.size.toLong())

                setStatus(ready = false, text = "SENDING $fileName ($size)...")

                val boundary = "----PyDropBoundary${UUID.randomUUID().toString().replace("-", "")}"
                val body = buildMultipartBody(fileName, fileBytes, boundary).toRequestBody(
                    "multipart/form-data; boundary=$boundary".toMediaType()
                )
                val request = Request.Builder()
                    .url("http://${device.address}:${device.httpPort}/api/upload")
                    .post(body)
                    .build()

                val ok = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { it.isSuccessful }
                }

                if (ok) {
                    setStatus(ready = true, text = "READY")
                    toast("Sent $fileName to ${device.name}")
                } else {
                    setStatus(ready = true, text = "READY")
                    toast("Send failed")
                }
            } catch (e: Exception) {
                setStatus(ready = true, text = "READY")
                toast("Error: ${e.message}")
            }
        }
    }

    private fun buildMultipartBody(fileName: String, fileBytes: ByteArray, boundary: String): ByteArray {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val footer = "\r\n--$boundary--\r\n"
        return header.toByteArray() + fileBytes + footer.toByteArray()
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.contains(".")) return ip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        mDNS?.stop()
    }
}

data class Device(val id: String, val name: String, val address: String, val httpPort: Int)

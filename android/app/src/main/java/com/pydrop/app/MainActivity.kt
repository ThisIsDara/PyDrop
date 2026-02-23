package com.pydrop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.pydrop.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Bug 3 fix: add read + write timeouts so large transfers don't hang forever
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val devices = mutableListOf<Device>()
    private val files = mutableListOf<ReceivedFile>()
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var fileAdapter: FileAdapter
    private var server: PyDropServer? = null
    private var mDNS: PyDropmDNS? = null
    private var deviceId: String = ""
    private var deviceName: String = ""
    private var localIp: String = ""
    private var httpPort: Int = 8080

    // Bug 2 fix: track which device the user selected BEFORE launching the file picker
    private var pendingTargetDevice: Device? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val target = pendingTargetDevice
        if (target != null) {
            // Came from showDeviceOptions — send directly to the chosen device
            pendingTargetDevice = null
            sendFileToDevice(target, uri)
        } else {
            // Came from the top-level "Send File" button — show device picker first
            uploadFile(uri)
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
        binding.tvIp.text = "IP: $localIp:$httpPort"
        binding.tvDeviceId.text = "ID: $deviceId"

        deviceAdapter = DeviceAdapter(devices) { device ->
            showDeviceOptions(device)
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        fileAdapter = FileAdapter(files) { file ->
            downloadFile(file)
        }
        binding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
        }

        binding.btnQrCode.setOnClickListener { showQrCode() }
        binding.btnRefresh.setOnClickListener { refreshDevices() }
        // Top-level send button: no device pre-selected; file picker leads to device picker
        binding.btnSendFile.setOnClickListener {
            pendingTargetDevice = null
            pickFileLauncher.launch("*/*")
        }
        binding.btnReceive.setOnClickListener { openWebUI() }
        binding.swipeRefresh.setOnRefreshListener {
            refreshDevices()
            loadFiles()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.btnStartServer.text = "⏹ Stop Server"
        binding.btnStartServer.setOnClickListener { toggleServer() }
    }

    private fun toggleServer() {
        if (server != null) {
            server?.stop()
            server = null
            mDNS?.stop()
            mDNS = null
            binding.btnStartServer.text = "▶ Start Server"
            binding.tvStatus.text = "Server Stopped"
        } else {
            startServer()
            binding.btnStartServer.text = "⏹ Stop Server"
        }
    }

    private fun startServer() {
        binding.tvStatus.text = "Server Starting..."

        lifecycleScope.launch {
            try {
                server = PyDropServer(httpPort, deviceId, deviceName, localIp, this@MainActivity) { event, data ->
                    runOnUiThread {
                        handleServerEvent(event, data)
                    }
                }
                server?.start()

                // Bug 1 & 6 fix: PyDropmDNS now needs context for MulticastLock
                mDNS = PyDropmDNS(deviceName, deviceId, localIp, httpPort, this@MainActivity) { device ->
                    runOnUiThread {
                        addDevice(device)
                    }
                }
                mDNS?.start()

                binding.tvStatus.text = "Server Running"
                loadFiles()
                refreshDevices()
                generateQrCode()

            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
                Toast.makeText(this@MainActivity, "Server error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleServerEvent(event: String, data: Map<String, Any>) {
        when (event) {
            "file_received" -> {
                loadFiles()
                Toast.makeText(this, "File received: ${data["name"]}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateQrCode() {
        try {
            val content = "pydrop://$localIp:$httpPort/$deviceId"
            val encoder = BarcodeEncoder()
            val bitmap = encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 200, 200)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            binding.ivQrCode.visibility = View.GONE
        }
    }

    private fun refreshDevices() {
        devices.clear()
        deviceAdapter.notifyDataSetChanged()

        lifecycleScope.launch {
            try {
                mDNS?.discover()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Discovery error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addDevice(device: Device) {
        val existing = devices.indexOfFirst { it.id == device.id }
        if (existing >= 0) {
            devices[existing] = device
            deviceAdapter.notifyItemChanged(existing)
        } else {
            devices.add(device)
            deviceAdapter.notifyItemInserted(devices.size - 1)
        }

        binding.tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDeviceOptions(device: Device) {
        // Bug 2 fix: set pendingTargetDevice BEFORE launching the file picker so the
        // launcher callback knows which device to send to without showing the list again.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(device.name)
            .setMessage("IP: ${device.address}\nPort: ${device.httpPort}")
            .setPositiveButton("Send File") { _, _ ->
                pendingTargetDevice = device
                pickFileLauncher.launch("*/*")
            }
            .setNegativeButton("Close", null)
            .create()
            .show()
    }

    // Called when the top-level Send button is tapped and a file was picked
    private fun uploadFile(uri: Uri) {
        if (devices.isEmpty()) {
            Toast.makeText(this, "No devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(deviceNames) { _, which ->
                sendFileToDevice(devices[which], uri)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendFileToDevice(device: Device, uri: Uri) {
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val fileName = getFileName(uri)
                val fileBytes = inputStream?.readBytes() ?: return@launch

                val boundary = "----WebKitFormBoundary${UUID.randomUUID().toString().replace("-", "")}"
                val body = buildMultipartBody(fileName, fileBytes, boundary).toRequestBody(
                    "multipart/form-data; boundary=$boundary".toMediaType()
                )

                val request = Request.Builder()
                    .url("http://${device.address}:${device.httpPort}/api/upload")
                    .post(body)
                    .build()

                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Sent $fileName to ${device.name}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Send failed: ${response.code}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun loadFiles() {
        lifecycleScope.launch {
            try {
                val request = Request.Builder()
                    .url("http://localhost:$httpPort/api/files")
                    .build()

                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@launch
                    val jsonObj = JSONObject(body)
                    val json = jsonObj.getJSONArray("files")
                    files.clear()
                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        files.add(ReceivedFile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            size = obj.getLong("size"),
                            time = obj.optString("time", ""),
                            direction = obj.optString("direction", "received")
                        ))
                    }
                    fileAdapter.notifyDataSetChanged()
                    binding.tvNoFiles.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (_: Exception) {
                // Server might not be running yet
            }
        }
    }

    private fun downloadFile(file: ReceivedFile) {
        lifecycleScope.launch {
            try {
                val url = "http://localhost:$httpPort/api/download?id=${file.id}"
                val request = Request.Builder().url(url).build()
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return@launch
                    val downloadsDir = getExternalFilesDir(null)
                    val dest = File(downloadsDir, file.name)
                    FileOutputStream(dest).use { it.write(bytes) }

                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        dest
                    )

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open with"))
                    Toast.makeText(this@MainActivity, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showQrCode() {
        // Already shown in main UI
    }

    private fun openWebUI() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://$localIp:$httpPort"))
        startActivity(intent)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val ip = address.hostAddress ?: continue
                        if (ip.contains(".")) return ip  // IPv4
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

data class ReceivedFile(val id: String, val name: String, val size: Long, val time: String, val direction: String)

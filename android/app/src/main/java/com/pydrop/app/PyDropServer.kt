package com.pydrop.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class PyDropServer(
    private val port: Int,
    private val deviceInfo: DeviceInfo,
    private val context: Context,
    private val onEvent: (PyDropEvent) -> Unit
) : NanoHTTPD(port) {

    companion object {
        /** Maximum upload size: 4 GB. Reject anything larger to prevent disk-fill DoS. */
        private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
    }

    private val deviceId = deviceInfo.id
    private val deviceName = deviceInfo.name
    private val localIp = deviceInfo.ip

    private val receivedFiles = CopyOnWriteArrayList<ReceivedFile>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/" -> serveIndex()
            uri == "/api/info" -> serveInfo()
            uri == "/api/files" -> serveFiles()
            uri == "/api/download" -> serveDownload(session)
            uri == "/api/upload" && method == Method.POST -> handleUpload(session)
            uri == "/api/qr" -> serveQr()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found").also {
                Log.d("PyDropServer", "Unhandled route: $method $uri")
            }
        }
    }

    private fun serveIndex(): Response {
        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>PyDrop</title>
    <style>
        body { font-family: system-ui; background: #0a0a0f; color: #fff; padding: 20px; }
        .card { background: #12121a; padding: 20px; border-radius: 12px; margin: 10px 0; }
        .btn { background: #00d4aa; color: #0a0a0f; padding: 12px 24px; border: none; border-radius: 8px; cursor: pointer; }
    </style>
</head>
<body>
    <h1>&#x2B21; PyDrop</h1>
    <p>Server running</p>
    <a href="/api/files"><button class="btn">View Files</button></a>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveInfo(): Response {
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("ip", localIp)
            put("httpPort", port)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveFiles(): Response {
        val jsonArray = org.json.JSONArray()
        receivedFiles.forEach { file ->
            jsonArray.put(JSONObject().apply {
                put("id", file.id)
                put("name", file.name)
                put("size", file.size)
                put("time", file.time)
                put("direction", "received")
            })
        }
        val wrapper = JSONObject().put("files", jsonArray)
        return newFixedLengthResponse(Response.Status.OK, "application/json", wrapper.toString())
    }

    private fun serveDownload(session: IHTTPSession): Response {
        val params = session.parameters
        val fileId = params["id"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file id").also {
                Log.w("PyDropServer", "Download failed: missing file id parameter")
            }

        val fileInfo = receivedFiles.find { it.id == fileId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found").also {
                Log.w("PyDropServer", "Download failed: file not found for id=$fileId")
            }

        val f = File(fileInfo.path)
        if (!f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found on disk").also {
            Log.w("PyDropServer", "Download failed: file not on disk: ${fileInfo.path}")
        }

        val resp = newChunkedResponse(Response.Status.OK, "application/octet-stream", f.inputStream())
        resp.addHeader("Content-Disposition", "attachment; filename=\"${fileInfo.name}\"")
        return resp
    }

    private fun handleUpload(session: IHTTPSession): Response {
        // Check Content-Length header before parsing body to reject oversized uploads early
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_UPLOAD_BYTES) {
            val resp = newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain",
                "File too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB)"
            )
            Log.w("PyDropServer", "Rejected upload: Content-Length $contentLength exceeds limit")
            return resp
        }

        return try {
            // NanoHTTPD parses multipart and writes temp files; the map values are temp file paths
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val tempPath = files["file"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file field")

            // NanoHTTPD puts the original filename into session.parameters under the field name
            // (see decodeMultipartFormData: values.add(fileName) for binary parts).
            // Strip any path components to prevent path traversal attacks.
            val rawName = session.parameters["file"]?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "file_${System.currentTimeMillis()}"
            val filename = File(rawName).name.ifBlank { "file_${System.currentTimeMillis()}" }

            val fileId = UUID.randomUUID().toString().take(8)
            val saveDir = File(context.filesDir, "received")
            saveDir.mkdirs()
            val dest = File(saveDir, "${fileId}_$filename")

            File(tempPath).copyTo(dest, overwrite = true)

            val fileInfo = ReceivedFile(
                id = fileId,
                name = filename,
                size = dest.length(),
                time = System.currentTimeMillis().toString(),
                path = dest.absolutePath
            )
            receivedFiles.add(fileInfo)
            onEvent(PyDropEvent.FileReceived(
                id = fileInfo.id,
                name = fileInfo.name,
                size = fileInfo.size,
                time = fileInfo.time
            ))

            newFixedLengthResponse(
                Response.Status.OK, "application/json",
                JSONObject().put("success", true).put("fileId", fileId).toString()
            )
        } catch (e: Exception) {
            Log.e("PyDropServer", "Upload error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Upload failed")
        }
    }

    private fun serveQr(): Response {
        val qrData = "pydrop://$localIp:$port/$deviceId"
        return newFixedLengthResponse(Response.Status.OK, "text/plain", qrData)
    }

}

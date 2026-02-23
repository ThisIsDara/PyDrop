package com.pydrop.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class PyDropServer(
    private val port: Int,
    private val deviceId: String,
    private val context: Context,
    private val onEvent: (String, Map<String, Any>) -> Unit
) : NanoHTTPD(port) {

    private val receivedFiles = CopyOnWriteArrayList<MutableMap<String, Any>>()

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
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
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
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveFiles(): Response {
        val json = org.json.JSONArray()
        receivedFiles.forEach { file ->
            json.put(JSONObject().apply {
                put("id", file["id"])
                put("name", file["name"])
                put("size", file["size"])
                put("time", file["time"])
            })
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveDownload(session: IHTTPSession): Response {
        val params = session.parameters
        val fileId = params["id"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file id")

        val fileInfo = receivedFiles.find { it["id"] == fileId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        val path = fileInfo["path"] as? String
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File path not found")
        val name = fileInfo["name"] as? String ?: "file"

        val f = File(path)
        if (!f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found on disk")

        val resp = newChunkedResponse(Response.Status.OK, "application/octet-stream", f.inputStream())
        resp.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        return resp
    }

    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            // NanoHTTPD parses multipart and writes temp files; the map values are temp file paths
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val tempPath = files["file"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file field")

            // Get original filename from parameters
            val filename = session.parameters["file"]?.firstOrNull()
                ?: "file_${System.currentTimeMillis()}"

            val fileId = UUID.randomUUID().toString().take(8)
            val saveDir = File(context.filesDir, "received")
            saveDir.mkdirs()
            val dest = File(saveDir, "${fileId}_$filename")

            File(tempPath).copyTo(dest, overwrite = true)

            val fileInfo = mutableMapOf<String, Any>(
                "id" to fileId,
                "name" to filename,
                "size" to dest.length(),
                "time" to System.currentTimeMillis().toString(),
                "path" to dest.absolutePath
            )
            receivedFiles.add(fileInfo)
            onEvent("file_received", fileInfo)

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
        val qrData = "pydrop://:$port/$deviceId"
        return newFixedLengthResponse(Response.Status.OK, "text/plain", qrData)
    }

    fun stopServer() {
        stop()
    }
}

package com.pydrop.app

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
    private val deviceName: String,
    private val onEvent: (String, Map<String, Any>) -> Unit
) : NanoHTTPD(port) {

    private val receivedFiles = CopyOnWriteArrayList<MutableMap<String, Any>>()
    private var session: NanoHTTPDHTTPSession? = null

    override fun serve(session: HTTPSession): Response {
        this.session = session
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/" -> serveFile()
            uri == "/api/info" -> serveInfo()
            uri == "/api/files" -> serveFiles()
            uri == "/api/download" -> serveDownload(session)
            uri == "/api/upload" && method == Method.POST -> serveUpload(session)
            uri == "/api/qr" -> serveQr()
            uri.startsWith("/api/thumb") -> serveThumb(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun serveFile(): Response {
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
    <h1>⬡ PyDrop</h1>
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

    private fun serveDownload(session: HTTPSession): Response {
        val params = session.parameters
        val fileId = params["id"]?.firstOrNull() ?: return notFound()

        val file = receivedFiles.find { it["id"] == fileId } ?: return notFound()
        val path = file["path"] as? String ?: return notFound()
        val name = file["name"] as? String ?: "file"

        val f = File(path)
        if (!f.exists()) return notFound()

        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", f.inputStream(), f.length())
            .addHeader("Content-Disposition", "attachment; filename=\"$name\"")
    }

    private fun serveUpload(session: HTTPSession): Response {
        try {
            val files = session.parameters["file"]
            if (files.isNullOrEmpty()) return badRequest()

            val tempFile = session.parameters["file"]?.firstOrNull()
            if (tempFile == null) {
                // Parse multipart manually
                val contentType = session.headers["content-type"] ?: return badRequest()
                if (contentType.contains("multipart")) {
                    return handleMultipartUpload(session)
                }
                return badRequest()
            }

            return ok()
        } catch (e: Exception) {
            Log.e("PyDropServer", "Upload error", e)
            return error(e.message ?: "Error")
        }
    }

    private fun handleMultipartUpload(session: HTTPSession): Response {
        try {
            val inputStream = session.inputStream
            val bytes = inputStream.readBytes()
            
            // Find filename in boundary
            val body = String(bytes)
            val filenameMatch = Regex("filename=\"([^\"]+)\"").find(body)
            val filename = filenameMatch?.groupValues?.get(1) ?: "file_${System.currentTimeMillis()}"
            
            // Find file content start
            val boundary = session.headers["content-type"]?.substringAfter("boundary=") ?: return badRequest()
            val boundaryStart = body.indexOf("\r\n\r\n")
            if (boundaryStart < 0) return badRequest()
            
            val fileContent = body.substring(boundaryStart + 4, body.lastIndexOf("--$boundary"))
            
            val fileId = UUID.randomUUID().toString().take(8)
            val saveDir = File(applicationWorkingDirectory, "received")
            saveDir.mkdirs()
            val file = File(saveDir, "${fileId}_$filename")
            FileOutputStream(file).use { it.write(fileContent.toByteArray()) }
            
            val fileInfo = mutableMapOf<String, Any>(
                "id" to fileId,
                "name" to filename,
                "size" to file.length(),
                "time" to java.time.Instant.now().toString(),
                "path" to file.absolutePath
            )
            receivedFiles.add(fileInfo)
            
            onEvent("file_received", fileInfo)
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", 
                JSONObject().put("success", true).put("fileId", fileId).toString())
        } catch (e: Exception) {
            return error(e.message ?: "Upload failed")
        }
    }

    private fun serveQr(): Response {
        // Return simple QR placeholder
        val qrData = "pydrop://localhost:$port/$deviceId"
        return newFixedLengthResponse(Response.Status.OK, "text/plain", qrData)
    }

    private fun serveThumb(session: HTTPSession): Response {
        return notFound()
    }

    private fun ok() = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
    private fun badRequest() = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request")
    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    private fun error(msg: String) = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", msg)

    fun stop() {
        super.stop()
    }
}

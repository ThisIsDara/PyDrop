package com.pydrop.app

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

class FileTransferClient(
    private val contentResolver: ContentResolver
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed class SendResult {
        data class Success(val fileName: String, val deviceName: String) : SendResult()
        data class Failure(val error: String) : SendResult()
    }

    suspend fun sendFile(
        device: Device,
        uri: Uri,
        onProgress: (String) -> Unit
    ): SendResult {
        return try {
            val fileName = getFileName(uri)
            val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            onProgress("SENDING: $fileName")

            val streamingBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = fileSize
                override fun writeTo(sink: BufferedSink) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val source = stream.source()
                        var totalWritten = 0L
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = source.read(buffer, 65536)
                            if (read == -1L) break
                            sink.write(buffer, read)
                            totalWritten += read
                            if (fileSize > 0) {
                                val pct = (totalWritten * 100 / fileSize).toInt()
                                onProgress("SENDING: $pct%")
                            }
                        }
                    }
                }
            }
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, streamingBody)
                .build()

            val request = Request.Builder()
                .url("http://${device.address}:${device.httpPort}/api/upload")
                .post(multipart)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                SendResult.Success(fileName, device.name)
            } else {
                SendResult.Failure("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            SendResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name
    }
}

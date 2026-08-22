package com.example.paymentsmstracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class FileHandler {

    fun saveCsvFileToDownloads(context: Context, filename: String, content: String): Uri? {
        return saveTextFile(context, filename, content, mimeType = "text/csv")
    }

    fun saveTextFile(
        context: Context,
        filename: String,
        content: String,
        mimeType: String = "text/plain"
    ): Uri? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveTextFileToDownloads(context, filename, bytes, mimeType)
        } else {
            saveTextFileToDownloadsLegacy(context, filename, bytes)
        }
    }

    /** Writes to cache and returns a FileProvider URI for sharing with other apps. */
    fun saveTextFileForShare(
        context: Context,
        filename: String,
        content: String,
        mimeType: String = "text/plain"
    ): Uri? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, filename)
            file.writeText(content, Charsets.UTF_8)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e("FileHandler", "Failed to save share file", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveTextFileToDownloads(
        context: Context,
        filename: String,
        bytes: ByteArray,
        mimeType: String
    ): Uri? {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            Log.e("FileHandler", "Failed to create file in Downloads")
            return null
        }
        return try {
            val outputStream = contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                Log.e("FileHandler", "Failed to open output stream for Downloads")
                contentResolver.delete(uri, null, null)
                return null
            }
            outputStream.use { stream ->
                stream.write(bytes)
            }
            Log.d("FileHandler", "File saved to Downloads: $filename")
            uri
        } catch (e: Exception) {
            Log.e("FileHandler", "Failed to save file", e)
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun saveTextFileToDownloadsLegacy(
        context: Context,
        filename: String,
        bytes: ByteArray
    ): Uri? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            Toast.makeText(context, "Storage not available", Toast.LENGTH_SHORT).show()
            return null
        }
        return try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, filename)
            FileOutputStream(file).use { stream ->
                stream.write(bytes)
            }
            Log.d("FileHandler", "File saved to Downloads: $filename")
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e("FileHandler", "Failed to save file", e)
            null
        }
    }
}

package com.example.paymentsmstracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class FileHandler {

    fun saveCsvFileToDownloads(context: Context, filename: String, content: String) {
        saveTextFile(context, filename, content, mimeType = "text/csv")
    }

    fun saveTextFile(
        context: Context,
        filename: String,
        content: String,
        mimeType: String = "text/plain"
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveTextFileToDownloads(context, filename, content, mimeType)
        } else {
            saveTextFileToDownloadsLegacy(context, filename, content)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveTextFileToDownloads(
        context: Context,
        filename: String,
        content: String,
        mimeType: String
    ) {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            try {
                val outputStream: OutputStream? = contentResolver.openOutputStream(uri)
                outputStream?.use { stream ->
                    stream.write(content.toByteArray())
                    Log.d("FileHandler", "File saved to Downloads: $filename")
                }
            } catch (e: Exception) {
                Log.e("FileHandler", "Failed to save file", e)
            }
        } else {
            Log.e("FileHandler", "Failed to create file in Downloads")
        }
    }

    private fun saveTextFileToDownloadsLegacy(
        context: Context,
        filename: String,
        content: String
    ) {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            Toast.makeText(context, "Storage not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            FileOutputStream(File(downloadsDir, filename)).use { stream ->
                stream.write(content.toByteArray())
            }
            Log.d("FileHandler", "File saved to Downloads: $filename")
        } catch (e: Exception) {
            Log.e("FileHandler", "Failed to save file", e)
        }
    }
}

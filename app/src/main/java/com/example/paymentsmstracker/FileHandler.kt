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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above
            saveTextFileToDownloads(context, filename, content)
        } else {
            // For Android 9 and below
            // Make sure you have handled runtime permissions for WRITE_EXTERNAL_STORAGE
            saveTextFileToDownloadsLegacy(context, filename, content)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveTextFileToDownloads(
        context: Context,
        filename: String,
        content: String
    ) {
        // The MediaStore API is the modern way to interact with media files.
        val contentResolver = context.contentResolver

        // Use ContentValues to specify file details.
        val contentValues = ContentValues().apply {
            // Set the file name.
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)

            // Set the file's MIME type.
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")

            // Set the relative path to the Downloads directory.
            // This is the key to saving in the Downloads folder on Android 10+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        // Use the MediaStore's Downloads collection.
        // EXTERNAL_CONTENT_URI refers to the primary shared storage.
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                // Open an OutputStream to the file's URI.
                val outputStream: OutputStream? = contentResolver.openOutputStream(uri)
                outputStream?.use { stream ->
                    // Write the content to the file.
                    stream.write(content.toByteArray())
                    Log.d("FileHandler", "File saved to Downloads")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("FileHandler", "Failed to save file", e)
            }
        } else {
            Log.e("FileHandler", "Failed to create file in Downloads")
        }
    }

    fun saveTextFileToDownloadsLegacy(
        context: Context,
        filename: String,
        content: String
    ) {
        // Check if external storage is available for write
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            Toast.makeText(context, "Storage not available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Get the public Downloads directory
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            // Ensure the directory exists
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val file = File(downloadsDir, filename)

            // Write to the file
            FileOutputStream(file).use { stream ->
                stream.write(content.toByteArray())
            }
            Log.d("FileHandler", "File saved to Downloads")

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("FileHandler", "Failed to save file", e)

        }

    }


}
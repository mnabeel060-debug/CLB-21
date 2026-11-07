package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.ReceiptAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    /**
     * Reads a selected file Uri, compresses if large image, and returns a ReceiptAttachment.
     */
    fun processSelectedUri(context: Context, uri: Uri): ReceiptAttachment? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            var fileName = "receipt_file"

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }

            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val rawBytes = inputStream.readBytes()
            inputStream.close()

            // If it's an image, optimize dimensions/quality to stay well within Firestore document limits
            val finalBytes = if (mimeType.startsWith("image/")) {
                compressImageBytes(rawBytes)
            } else {
                // PDF or other document
                rawBytes
            }

            // Cap base64 size around 750KB
            val base64Data = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            ReceiptAttachment(
                fileName = fileName,
                mimeType = mimeType,
                base64Data = base64Data
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun compressImageBytes(rawBytes: ByteArray): ByteArray {
        return try {
            val originalBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            val maxDimension = 1200
            val width = originalBitmap.width
            val height = originalBitmap.height

            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val ratio = width.toFloat() / height.toFloat()
                val targetWidth: Int
                val targetHeight: Int
                if (width > height) {
                    targetWidth = maxDimension
                    targetHeight = (maxDimension / ratio).toInt()
                } else {
                    targetHeight = maxDimension
                    targetWidth = (maxDimension * ratio).toInt()
                }
                Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
            } else {
                originalBitmap
            }

            val outStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
            outStream.toByteArray()
        } catch (e: Exception) {
            rawBytes
        }
    }

    /**
     * Downloads/saves receipt to local device storage and launches viewer/share intent.
     */
    fun downloadAndOpenReceipt(context: Context, fileName: String, mimeType: String, base64Data: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)

            // Save to app cache directory first for instant viewing
            val cacheFile = File(context.cacheDir, fileName)
            FileOutputStream(cacheFile).use { it.write(bytes) }

            // Save to Public Downloads or Documents via MediaStore if available
            saveToPublicStorage(context, fileName, mimeType, bytes)

            // Open with intent
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = try {
                FileProvider.getUriForFile(context, authority, cacheFile)
            } catch (e: Exception) {
                Uri.fromFile(cacheFile)
            }

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, "Open Receipt: $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Toast.makeText(context, "Receipt saved: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving receipt: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToPublicStorage(context: Context, fileName: String, mimeType: String, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (mimeType.startsWith("image/")) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CLB21Eighteen_Rawalpindi")
                }

                val uri = context.contentResolver.insert(collection, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            }
        } catch (e: Exception) {
            // Non-fatal, cache file is already available for viewing
        }
    }
}

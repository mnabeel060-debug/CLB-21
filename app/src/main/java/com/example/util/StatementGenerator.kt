package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.Transaction
import com.example.data.model.TransactionTypes
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatementGenerator {

    /**
     * Generates a printable, multi-page PDF financial statement with all transactions,
     * totals, and balance, saves it to device storage, and opens with default viewer/print chooser.
     */
    fun generateAndOpenStatementPdf(
        context: Context,
        transactions: List<Transaction>,
        totalCashIn: Double,
        totalCashOut: Double,
        availableBalance: Double
    ) {
        if (transactions.isEmpty()) {
            Toast.makeText(context, "No transactions to export.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pdfDoc = PdfDocument()
            val pageWidth = 595 // Standard A4 width in points (72 dpi)
            val pageHeight = 842 // Standard A4 height in points

            val paint = Paint().apply { isAntiAlias = true }
            val headerPaint = Paint().apply { isAntiAlias = true }
            val boldPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }

            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            val generatedDate = sdf.format(Date())
            val fileDate = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val fileName = "CLB21_Statement_$fileDate.pdf"

            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = pdfDoc.startPage(pageInfo)
            var canvas = page.canvas

            var y = 40f

            fun drawHeader() {
                // Header Background Band
                paint.color = Color.rgb(15, 23, 42) // Slate 900
                canvas.drawRect(0f, 0f, pageWidth.toFloat(), 90f, paint)

                // Title
                boldPaint.color = Color.WHITE
                boldPaint.textSize = 18f
                canvas.drawText("CLB 21 Eighteen Society Rawalpindi", 36f, 38f, boldPaint)

                // Subtitle
                paint.color = Color.rgb(245, 158, 11) // Amber
                paint.textSize = 11f
                canvas.drawText("Official Construction Cash Management Statement", 36f, 56f, paint)

                paint.color = Color.rgb(203, 213, 225) // Slate 300
                paint.textSize = 9f
                canvas.drawText("Report Generated: $generatedDate", 36f, 74f, paint)

                // Summary Box
                y = 110f
                paint.color = Color.rgb(241, 245, 249) // Slate 100
                canvas.drawRoundRect(36f, y, pageWidth - 36f, y + 60f, 8f, 8f, paint)

                paint.color = Color.rgb(30, 41, 59)
                paint.textSize = 9f
                canvas.drawText("TOTAL CASH RECEIVED", 50f, y + 20f, paint)
                boldPaint.color = Color.rgb(5, 150, 105) // Green
                boldPaint.textSize = 13f
                canvas.drawText(formatCurrency(totalCashIn), 50f, y + 42f, boldPaint)

                paint.color = Color.rgb(30, 41, 59)
                paint.textSize = 9f
                canvas.drawText("TOTAL CASH EXPENSES", 220f, y + 20f, paint)
                boldPaint.color = Color.rgb(220, 38, 38) // Red
                boldPaint.textSize = 13f
                canvas.drawText(formatCurrency(totalCashOut), 220f, y + 42f, boldPaint)

                paint.color = Color.rgb(30, 41, 59)
                paint.textSize = 9f
                canvas.drawText("NET CASH BALANCE", 400f, y + 20f, paint)
                boldPaint.color = if (availableBalance >= 0) Color.rgb(15, 23, 42) else Color.rgb(220, 38, 38)
                boldPaint.textSize = 14f
                canvas.drawText(formatCurrency(availableBalance), 400f, y + 42f, boldPaint)

                // Table Header
                y += 80f
                paint.color = Color.rgb(226, 232, 240) // Slate 200
                canvas.drawRect(36f, y, pageWidth - 36f, y + 24f, paint)

                boldPaint.color = Color.rgb(15, 23, 42)
                boldPaint.textSize = 10f
                canvas.drawText("#", 44f, y + 16f, boldPaint)
                canvas.drawText("Date", 68f, y + 16f, boldPaint)
                canvas.drawText("Type", 145f, y + 16f, boldPaint)
                canvas.drawText("Description", 215f, y + 16f, boldPaint)
                canvas.drawText("Receipt", 425f, y + 16f, boldPaint)
                canvas.drawText("Amount", 485f, y + 16f, boldPaint)

                y += 26f
            }

            drawHeader()

            var itemIndex = 1
            for (tx in transactions) {
                // Check page height boundary
                if (y > pageHeight - 50f) {
                    // Draw page number on footer
                    paint.color = Color.GRAY
                    paint.textSize = 8f
                    canvas.drawText("Page $pageNumber", pageWidth / 2f - 15f, pageHeight - 20f, paint)

                    pdfDoc.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdfDoc.startPage(pageInfo)
                    canvas = page.canvas

                    // Simplified top header on page 2+
                    paint.color = Color.rgb(15, 23, 42)
                    canvas.drawRect(0f, 0f, pageWidth.toFloat(), 40f, paint)
                    boldPaint.color = Color.WHITE
                    boldPaint.textSize = 11f
                    canvas.drawText("CLB 21 Eighteen Society Rawalpindi - Statement (Page $pageNumber)", 36f, 25f, boldPaint)

                    y = 60f
                    // Table Header on page 2+
                    paint.color = Color.rgb(226, 232, 240)
                    canvas.drawRect(36f, y, pageWidth - 36f, y + 22f, paint)
                    boldPaint.color = Color.rgb(15, 23, 42)
                    boldPaint.textSize = 9f
                    canvas.drawText("#", 44f, y + 15f, boldPaint)
                    canvas.drawText("Date", 68f, y + 15f, boldPaint)
                    canvas.drawText("Type", 145f, y + 15f, boldPaint)
                    canvas.drawText("Description", 215f, y + 15f, boldPaint)
                    canvas.drawText("Receipt", 425f, y + 15f, boldPaint)
                    canvas.drawText("Amount", 485f, y + 15f, boldPaint)
                    y += 24f
                }

                // Alternating row background
                if (itemIndex % 2 == 0) {
                    paint.color = Color.rgb(248, 250, 252) // Slate 50
                    canvas.drawRect(36f, y - 2f, pageWidth - 36f, y + 20f, paint)
                }

                paint.color = Color.rgb(51, 65, 85) // Slate 700
                paint.textSize = 9f
                canvas.drawText("$itemIndex", 44f, y + 12f, paint)
                canvas.drawText(tx.date, 68f, y + 12f, paint)

                val isCashIn = tx.type == TransactionTypes.CASH_IN
                boldPaint.textSize = 9f
                if (isCashIn) {
                    boldPaint.color = Color.rgb(5, 150, 105)
                    canvas.drawText("CASH IN", 145f, y + 12f, boldPaint)
                } else {
                    boldPaint.color = Color.rgb(220, 38, 38)
                    canvas.drawText("CASH OUT", 145f, y + 12f, boldPaint)
                }

                // Truncate description if very long
                val maxDescLength = 34
                val descText = if (tx.description.length > maxDescLength) {
                    tx.description.take(maxDescLength) + "..."
                } else {
                    tx.description
                }
                paint.color = Color.rgb(15, 23, 42)
                paint.textSize = 9f
                canvas.drawText(descText, 215f, y + 12f, paint)

                paint.color = if (tx.hasReceipt) Color.rgb(217, 119, 6) else Color.GRAY
                paint.textSize = 8f
                canvas.drawText(if (tx.hasReceipt) "Yes (Slip)" else "-", 425f, y + 12f, paint)

                boldPaint.textSize = 9.5f
                if (isCashIn) {
                    boldPaint.color = Color.rgb(5, 150, 105)
                    canvas.drawText("+ " + formatCurrency(tx.amount), 485f, y + 12f, boldPaint)
                } else {
                    boldPaint.color = Color.rgb(220, 38, 38)
                    canvas.drawText("- " + formatCurrency(tx.amount), 485f, y + 12f, boldPaint)
                }

                // Row underline
                paint.color = Color.rgb(241, 245, 249)
                canvas.drawLine(36f, y + 21f, pageWidth - 36f, y + 21f, paint)

                y += 23f
                itemIndex++
            }

            // Summary Totals bar on the last page
            if (y > pageHeight - 80f) {
                paint.color = Color.GRAY
                paint.textSize = 8f
                canvas.drawText("Page $pageNumber", pageWidth / 2f - 15f, pageHeight - 20f, paint)

                pdfDoc.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDoc.startPage(pageInfo)
                canvas = page.canvas
                y = 50f
            }

            y += 15f
            paint.color = Color.rgb(241, 245, 249)
            canvas.drawRoundRect(36f, y, pageWidth - 36f, y + 36f, 6f, 6f, paint)

            boldPaint.color = Color.rgb(15, 23, 42)
            boldPaint.textSize = 10f
            canvas.drawText("STATEMENT SUMMARY: ${transactions.size} Entries", 50f, y + 22f, boldPaint)

            boldPaint.color = if (availableBalance >= 0) Color.rgb(5, 150, 105) else Color.rgb(220, 38, 38)
            boldPaint.textSize = 11f
            val balanceStr = "NET BALANCE: " + formatCurrency(availableBalance)
            canvas.drawText(balanceStr, 380f, y + 22f, boldPaint)

            // Footer
            paint.color = Color.GRAY
            paint.textSize = 8f
            canvas.drawText("Page $pageNumber of $pageNumber • CLB 21 Eighteen Society Rawalpindi", 36f, pageHeight - 20f, paint)

            pdfDoc.finishPage(page)

            // Save PDF to cache and Downloads
            val cacheFile = File(context.cacheDir, fileName)
            FileOutputStream(cacheFile).use { out ->
                pdfDoc.writeTo(out)
            }
            pdfDoc.close()

            // Save to Public MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CLB21Eighteen_Rawalpindi")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outStream ->
                        cacheFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }

            // Launch viewer or print chooser
            val contentUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, "Open / Print Statement").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Toast.makeText(context, "Statement saved to Downloads: $fileName", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error creating statement: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatCurrency(amount: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 0
        return "Rs. " + formatter.format(amount)
    }
}

package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

object TransactionTypes {
    const val CASH_IN = "CASH_IN"
    const val CASH_OUT = "CASH_OUT"
}

@IgnoreExtraProperties
data class Transaction(
    @DocumentId val id: String = "",
    val type: String = TransactionTypes.CASH_IN,
    val date: String = "",
    val amount: Double = 0.0,
    val description: String = "",
    val hasReceipt: Boolean = false,
    val receiptFileName: String? = null,
    val receiptMimeType: String? = null,
    val createdBy: String = "",
    val createdByEmail: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Receipt(
    @DocumentId val id: String = "",
    val transactionId: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val base64Data: String = "",
    val uploadedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AdminRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class ReceiptAttachment(
    val fileName: String,
    val mimeType: String,
    val base64Data: String
)

package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AdminRecord
import com.example.data.model.Receipt
import com.example.data.model.ReceiptAttachment
import com.example.data.model.Transaction
import com.example.data.util.OperationType
import com.example.data.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CashRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google to perform this action.")
    }

    /**
     * Realtime observation of all transactions for CLB 2181 Society.
     * Both Admin and Viewer roles can observe the transactions.
     */
    fun observeTransactions(): Flow<List<Transaction>> {
        return db.collection("transactions")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Transaction::class.java)
            }
    }

    /**
     * Add a Cash In or Cash Out entry with optional receipt attachment.
     * Only Admin has write permissions in Firestore security rules.
     */
    suspend fun addTransaction(
        type: String,
        date: String,
        amount: Double,
        description: String,
        attachment: ReceiptAttachment?
    ): Result<String> {
        return try {
            val uid = requireUserId()
            val userEmail = auth.currentUser?.email
            val docRef = db.collection("transactions").document()
            val txId = docRef.id

            val payload = mutableMapOf<String, Any>(
                "type" to type,
                "date" to date,
                "amount" to amount,
                "description" to description,
                "hasReceipt" to (attachment != null),
                "createdBy" to uid,
                "createdAt" to FieldValue.serverTimestamp()
            )

            if (attachment != null) {
                payload["receiptFileName"] = attachment.fileName
                payload["receiptMimeType"] = attachment.mimeType
            }
            if (!userEmail.isNullOrBlank()) {
                payload["createdByEmail"] = userEmail
            }

            // Write transaction document
            docRef.set(payload).await()

            // If attachment is present, store receipt document
            if (attachment != null) {
                val receiptRef = db.collection("receipts").document(txId)
                val receiptPayload = mapOf(
                    "transactionId" to txId,
                    "fileName" to attachment.fileName,
                    "mimeType" to attachment.mimeType,
                    "base64Data" to attachment.base64Data,
                    "uploadedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                receiptRef.set(receiptPayload).await()
            }

            Result.success(txId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "transactions")
            Result.failure(e)
        }
    }

    /**
     * Update an existing transaction entry.
     */
    suspend fun updateTransaction(
        transactionId: String,
        type: String,
        date: String,
        amount: Double,
        description: String,
        newAttachment: ReceiptAttachment?,
        keepExistingReceipt: Boolean
    ): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("transactions").document(transactionId)
            val existingSnapshot = docRef.get().await()
            val existing = existingSnapshot.toObject(Transaction::class.java)
                ?: throw IllegalStateException("Transaction not found")

            val willHaveReceipt = when {
                newAttachment != null -> true
                keepExistingReceipt -> existing.hasReceipt
                else -> false
            }

            val fileName = when {
                newAttachment != null -> newAttachment.fileName
                keepExistingReceipt -> existing.receiptFileName
                else -> null
            }

            val mimeType = when {
                newAttachment != null -> newAttachment.mimeType
                keepExistingReceipt -> existing.receiptMimeType
                else -> null
            }

            val updatePayload = mutableMapOf<String, Any?>(
                "type" to type,
                "date" to date,
                "amount" to amount,
                "description" to description,
                "hasReceipt" to willHaveReceipt,
                "receiptFileName" to fileName,
                "receiptMimeType" to mimeType,
                "createdByEmail" to existing.createdByEmail,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            @Suppress("UNCHECKED_CAST")
            val filteredPayload = updatePayload.filterValues { it != null } as Map<String, Any>
            docRef.update(filteredPayload).await()

            if (newAttachment != null) {
                val receiptRef = db.collection("receipts").document(transactionId)
                val receiptPayload = mapOf(
                    "transactionId" to transactionId,
                    "fileName" to newAttachment.fileName,
                    "mimeType" to newAttachment.mimeType,
                    "base64Data" to newAttachment.base64Data,
                    "uploadedBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                receiptRef.set(receiptPayload).await()
            } else if (!keepExistingReceipt && existing.hasReceipt) {
                db.collection("receipts").document(transactionId).delete().await()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "transactions/$transactionId")
            Result.failure(e)
        }
    }

    /**
     * Delete transaction and its corresponding receipt if any.
     */
    suspend fun deleteTransaction(transactionId: String): Result<Unit> {
        return try {
            val docRef = db.collection("transactions").document(transactionId)
            docRef.delete().await()
            db.collection("receipts").document(transactionId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "transactions/$transactionId")
            Result.failure(e)
        }
    }

    /**
     * Fetch receipt document by transaction ID. Both Admin and Viewer can download/view receipts.
     */
    suspend fun getReceipt(transactionId: String): Receipt? {
        return try {
            val docRef = db.collection("receipts").document(transactionId)
            val snapshot = docRef.get().await()
            snapshot.toObject(Receipt::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "receipts/$transactionId")
            null
        }
    }

    /**
     * Check if user is Admin.
     * Owner email mnabeel060@gmail.com is always Admin.
     * Also checks /admins/{uid} document.
     */
    suspend fun checkIfAdmin(userId: String, email: String?): Boolean {
        if (email?.equals("mnabeel060@gmail.com", ignoreCase = true) == true) {
            return true
        }
        return try {
            val adminDoc = db.collection("admins").document(userId).get().await()
            adminDoc.exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ensure owner or designated admin is registered in /admins collection
     */
    suspend fun registerAdmin(userId: String, email: String?): Result<Unit> {
        return try {
            val adminRef = db.collection("admins").document(userId)
            val payload = mutableMapOf<String, Any>(
                "userId" to userId,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (!email.isNullOrBlank()) {
                payload["email"] = email
            }
            adminRef.set(payload).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

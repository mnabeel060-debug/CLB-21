package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Receipt
import com.example.data.model.ReceiptAttachment
import com.example.data.model.Transaction
import com.example.data.model.TransactionTypes
import com.example.data.repository.CashRepository
import com.example.util.FileUtils
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CashDashboardState(
    val transactions: List<Transaction> = emptyList(),
    val totalCashIn: Double = 0.0,
    val totalCashOut: Double = 0.0,
    val availableBalance: Double = 0.0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isAdmin: Boolean = false,
    val isPreviewingAsViewer: Boolean = false,
    val selectedFilter: String = "ALL", // "ALL", "CASH_IN", "CASH_OUT"
    val viewingReceipt: Receipt? = null,
    val isReceiptLoading: Boolean = false,
    // Add / Edit Entry form dialog
    val isEntryDialogOpen: Boolean = false,
    val editingTransaction: Transaction? = null,
    val formType: String = TransactionTypes.CASH_IN,
    val formDate: String = "",
    val formAmount: String = "",
    val formDescription: String = "",
    val formAttachment: ReceiptAttachment? = null,
    val formKeepExistingReceipt: Boolean = true,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
    val transactionToDelete: Transaction? = null,
    val showAdminPasscodeDialog: Boolean = false
)

class CashViewModel(
    private val repository: CashRepository,
    private val currentUserId: String,
    private val userEmail: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CashDashboardState(
            formDate = getTodayFormattedDate(),
            isAdmin = userEmail?.equals("mnabeel060@gmail.com", ignoreCase = true) == true
        )
    )
    val uiState: StateFlow<CashDashboardState> = _uiState.asStateFlow()

    init {
        determineAdminStatus()
        observeTransactions()
    }

    private fun determineAdminStatus() {
        viewModelScope.launch {
            val isOwner = userEmail?.equals("mnabeel060@gmail.com", ignoreCase = true) == true
            val adminInDb = repository.checkIfAdmin(currentUserId, userEmail)
            val shouldBeAdmin = isOwner || adminInDb

            _uiState.update { it.copy(isAdmin = shouldBeAdmin) }

            if (shouldBeAdmin) {
                try {
                    repository.registerAdmin(currentUserId, userEmail)
                } catch (e: Exception) {
                    Log.w("CashViewModel", "registerAdmin error: ${e.message}")
                }
            }
        }
    }

    private fun observeTransactions() {
        viewModelScope.launch {
            repository.observeTransactions()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.e("CashViewModel", "Error observing transactions", rootError)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = rootError.message ?: "Failed to load transactions."
                        )
                    }
                }
                .collect { list ->
                    // Sort in chronological order (newest date / created first)
                    val sorted = list.sortedWith(
                        compareByDescending<Transaction> { it.date }
                            .thenByDescending { it.createdAt?.seconds ?: 0L }
                    )

                    var cashInSum = 0.0
                    var cashOutSum = 0.0

                    for (tx in sorted) {
                        if (tx.type == TransactionTypes.CASH_IN) {
                            cashInSum += tx.amount
                        } else if (tx.type == TransactionTypes.CASH_OUT) {
                            cashOutSum += tx.amount
                        }
                    }

                    val balance = cashInSum - cashOutSum

                    _uiState.update {
                        it.copy(
                            transactions = sorted,
                            totalCashIn = cashInSum,
                            totalCashOut = cashOutSum,
                            availableBalance = balance,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
        }
    }

    fun setFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun togglePreviewAsViewer() {
        _uiState.update { it.copy(isPreviewingAsViewer = !it.isPreviewingAsViewer) }
    }

    fun switchToViewerMode() {
        _uiState.update {
            it.copy(
                isAdmin = false,
                isPreviewingAsViewer = false,
                successMessage = "Switched to Read-Only Viewer mode."
            )
        }
    }

    fun openAdminPasscodeDialog() {
        _uiState.update { it.copy(showAdminPasscodeDialog = true) }
    }

    fun dismissAdminPasscodeDialog() {
        _uiState.update { it.copy(showAdminPasscodeDialog = false) }
    }

    fun verifyAdminPasscode(code: String, onResult: (Boolean) -> Unit) {
        val trimmed = code.trim()
        val isValid = (trimmed == "7816" || trimmed == "2181")
        if (isValid) {
            viewModelScope.launch {
                try {
                    repository.registerAdmin(currentUserId, userEmail)
                } catch (e: Exception) {
                    Log.w("CashViewModel", "Admin register notice: ${e.message}")
                }
                _uiState.update {
                    it.copy(
                        isAdmin = true,
                        isPreviewingAsViewer = false,
                        showAdminPasscodeDialog = false,
                        successMessage = "Admin access unlocked!"
                    )
                }
                onResult(true)
            }
        } else {
            onResult(false)
        }
    }

    // --- Entry Form Actions (Admin Only) ---

    fun openNewEntryDialog(type: String) {
        if (!effectiveIsAdmin()) return

        _uiState.update {
            it.copy(
                isEntryDialogOpen = true,
                editingTransaction = null,
                formType = type,
                formDate = getTodayFormattedDate(),
                formAmount = "",
                formDescription = "",
                formAttachment = null,
                formKeepExistingReceipt = true,
                formError = null,
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun openEditEntryDialog(transaction: Transaction) {
        if (!effectiveIsAdmin()) return

        _uiState.update {
            it.copy(
                isEntryDialogOpen = true,
                editingTransaction = transaction,
                formType = transaction.type,
                formDate = transaction.date,
                formAmount = if (transaction.amount % 1.0 == 0.0) transaction.amount.toLong().toString() else transaction.amount.toString(),
                formDescription = transaction.description,
                formAttachment = null,
                formKeepExistingReceipt = transaction.hasReceipt,
                formError = null,
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun closeEntryDialog() {
        _uiState.update { it.copy(isEntryDialogOpen = false, editingTransaction = null, formError = null) }
    }

    fun updateFormFields(
        date: String? = null,
        amount: String? = null,
        description: String? = null,
        attachment: ReceiptAttachment? = null,
        keepExistingReceipt: Boolean? = null
    ) {
        _uiState.update { current ->
            current.copy(
                formDate = date ?: current.formDate,
                formAmount = amount ?: current.formAmount,
                formDescription = description ?: current.formDescription,
                formAttachment = if (attachment != null) attachment else current.formAttachment,
                formKeepExistingReceipt = keepExistingReceipt ?: current.formKeepExistingReceipt,
                formError = null
            )
        }
    }

    fun removeAttachment() {
        _uiState.update { it.copy(formAttachment = null, formKeepExistingReceipt = false) }
    }

    fun submitEntry() {
        val state = _uiState.value
        val amountVal = state.formAmount.trim().toDoubleOrNull()
        if (amountVal == null || amountVal <= 0.0) {
            _uiState.update { it.copy(formError = "Please enter a valid amount greater than 0.") }
            return
        }

        if (state.formDescription.trim().isBlank()) {
            _uiState.update { it.copy(formError = "Please enter where the cash was received or spent.") }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, formError = null, errorMessage = null) }

        viewModelScope.launch {
            try {
                // Ensure admin document exists in database
                repository.registerAdmin(currentUserId, userEmail)
            } catch (e: Exception) {
                Log.w("CashViewModel", "Admin register notice: ${e.message}")
            }

            if (state.editingTransaction == null) {
                // New transaction
                val result = repository.addTransaction(
                    type = state.formType,
                    date = state.formDate.ifBlank { getTodayFormattedDate() },
                    amount = amountVal,
                    description = state.formDescription.trim(),
                    attachment = state.formAttachment
                )
                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            isEntryDialogOpen = false,
                            formAmount = "",
                            formDescription = "",
                            formAttachment = null,
                            formError = null,
                            successMessage = "${if (state.formType == TransactionTypes.CASH_IN) "Cash In" else "Cash Out"} entry saved successfully!"
                        )
                    }
                } else {
                    val errorText = result.exceptionOrNull()?.localizedMessage ?: "Failed to save entry"
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            formError = errorText,
                            errorMessage = errorText
                        )
                    }
                }
            } else {
                // Edit transaction
                val tx = state.editingTransaction
                val result = repository.updateTransaction(
                    transactionId = tx.id,
                    type = state.formType,
                    date = state.formDate.ifBlank { tx.date },
                    amount = amountVal,
                    description = state.formDescription.trim(),
                    newAttachment = state.formAttachment,
                    keepExistingReceipt = state.formKeepExistingReceipt
                )
                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            isEntryDialogOpen = false,
                            editingTransaction = null,
                            formError = null,
                            successMessage = "Entry updated successfully!"
                        )
                    }
                } else {
                    val errorText = result.exceptionOrNull()?.localizedMessage ?: "Failed to update entry"
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            formError = errorText,
                            errorMessage = errorText
                        )
                    }
                }
            }
        }
    }

    // --- Delete Entry Actions (Admin Only) ---

    fun confirmDeleteTransaction(transaction: Transaction) {
        if (!effectiveIsAdmin()) return
        _uiState.update { it.copy(transactionToDelete = transaction) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(transactionToDelete = null) }
    }

    fun executeDelete() {
        val tx = _uiState.value.transactionToDelete ?: return
        if (!effectiveIsAdmin()) return

        viewModelScope.launch {
            val result = repository.deleteTransaction(tx.id)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        transactionToDelete = null,
                        successMessage = "Transaction deleted successfully."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        transactionToDelete = null,
                        errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to delete entry"
                    )
                }
            }
        }
    }

    // --- Receipt Download & View Actions (Admin & Viewer) ---

    fun viewReceipt(transactionId: String) {
        _uiState.update { it.copy(isReceiptLoading = true) }
        viewModelScope.launch {
            val receipt = repository.getReceipt(transactionId)
            _uiState.update {
                it.copy(
                    isReceiptLoading = false,
                    viewingReceipt = receipt,
                    errorMessage = if (receipt == null) "Receipt file could not be loaded." else null
                )
            }
        }
    }

    fun closeReceiptView() {
        _uiState.update { it.copy(viewingReceipt = null) }
    }

    fun downloadReceipt(context: Context, receipt: Receipt) {
        FileUtils.downloadAndOpenReceipt(
            context = context,
            fileName = receipt.fileName,
            mimeType = receipt.mimeType,
            base64Data = receipt.base64Data
        )
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    /**
     * Whether user has active Admin capabilities (respecting preview toggle).
     */
    fun effectiveIsAdmin(): Boolean {
        val state = _uiState.value
        return state.isAdmin && !state.isPreviewingAsViewer
    }

    private fun getTodayFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}

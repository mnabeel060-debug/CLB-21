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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
            formDate = getTodayFormattedDate()
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
            val isAdmin = isOwner || adminInDb

            _uiState.update { it.copy(isAdmin = isAdmin) }

            if (isOwner && !adminInDb && userEmail != null) {
                repository.registerAdmin(currentUserId, userEmail)
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

    fun openAdminPasscodeDialog() {
        _uiState.update { it.copy(showAdminPasscodeDialog = true) }
    }

    fun dismissAdminPasscodeDialog() {
        _uiState.update { it.copy(showAdminPasscodeDialog = false) }
    }

    fun verifyAdminPasscode(code: String): Boolean {
        if (code.trim() == "2181") {
            viewModelScope.launch {
                userEmail?.let { repository.registerAdmin(currentUserId, it) }
                _uiState.update { it.copy(isAdmin = true, showAdminPasscodeDialog = false) }
            }
            return true
        }
        return false
    }

    // --- Entry Form Actions (Admin Only) ---

    fun openNewEntryDialog(type: String) {
        // Enforce role check
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
                errorMessage = null
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
                errorMessage = null
            )
        }
    }

    fun closeEntryDialog() {
        _uiState.update { it.copy(isEntryDialogOpen = false, editingTransaction = null) }
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
                formKeepExistingReceipt = keepExistingReceipt ?: current.formKeepExistingReceipt
            )
        }
    }

    fun removeAttachment() {
        _uiState.update { it.copy(formAttachment = null, formKeepExistingReceipt = false) }
    }

    fun submitEntry() {
        if (!effectiveIsAdmin()) return

        val state = _uiState.value
        val amountVal = state.formAmount.toDoubleOrNull()
        if (amountVal == null || amountVal <= 0.0) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid amount greater than 0.") }
            return
        }

        if (state.formDescription.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a short description.") }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
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
                    _uiState.update { it.copy(isSubmitting = false, isEntryDialogOpen = false) }
                } else {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to save entry"
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
                    _uiState.update { it.copy(isSubmitting = false, isEntryDialogOpen = false, editingTransaction = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to update entry"
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
                _uiState.update { it.copy(transactionToDelete = null) }
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
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

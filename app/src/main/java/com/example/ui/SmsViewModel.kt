package com.example.ui

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SmsLogEntry
import com.example.data.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SendStatus {
    object Idle : SendStatus
    object Success : SendStatus
    data class Error(val message: String) : SendStatus
}

sealed interface OtpVerificationStatus {
    object Idle : OtpVerificationStatus
    object Valid : OtpVerificationStatus
    object Invalid : OtpVerificationStatus
}

data class SmsUiState(
    val phoneNumber: String = "",
    val messageText: String = "",
    val isSending: Boolean = false,
    val sendStatus: SendStatus = SendStatus.Idle,
    val isPermissionGranted: Boolean = false,
    val otpCode: String = "",
    val otpLength: Int = 6,
    val otpAttemptCode: String = "",
    val otpVerificationStatus: OtpVerificationStatus = OtpVerificationStatus.Idle,
    val isSimulationMode: Boolean = true, // Default to true so users can try free OTPs instantly without carrier limits
    val simulatedNotification: String? = null
)

class SmsViewModel(private val repository: SmsRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsUiState())
    val uiState: StateFlow<SmsUiState> = _uiState.asStateFlow()

    // Observe sent logs directly from repository
    val smsLogs: StateFlow<List<SmsLogEntry>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val presetMessages = listOf(
        "On my way! 🚗",
        "Got it, thanks! 👍",
        "Can you call me back? 📞",
        "Running a bit late! ⏳",
        "Please send me the details. 📝",
        "Sure, let's do it! ✨",
        "Happy Birthday! 🎉",
        "I'm in a meeting, will text you later. 🤫"
    )

    private var sendJob: kotlinx.coroutines.Job? = null

    fun onPhoneNumberChange(number: String) {
        _uiState.update { it.copy(phoneNumber = number, sendStatus = SendStatus.Idle) }
    }

    fun onMessageChange(text: String) {
        _uiState.update { it.copy(messageText = text, sendStatus = SendStatus.Idle) }
    }

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(isPermissionGranted = granted) }
    }

    fun selectPresetMessage(preset: String) {
        _uiState.update { it.copy(messageText = preset, sendStatus = SendStatus.Idle) }
    }

    fun clearInputs() {
        _uiState.update { it.copy(phoneNumber = "", messageText = "", sendStatus = SendStatus.Idle) }
    }

    fun clearStatus() {
        _uiState.update { it.copy(sendStatus = SendStatus.Idle) }
    }

    fun sendSms(context: Context) {
        val state = _uiState.value
        val number = state.phoneNumber.trim()
        val text = state.messageText.trim()

        if (number.isEmpty()) {
            _uiState.update { it.copy(sendStatus = SendStatus.Error("Please enter a phone number")) }
            return
        }

        if (text.isEmpty()) {
            _uiState.update { it.copy(sendStatus = SendStatus.Error("Please write a message to send")) }
            return
        }

        _uiState.update { it.copy(isSending = true, sendStatus = SendStatus.Idle) }

        sendJob = viewModelScope.launch {
            try {
                if (state.isSimulationMode) {
                    // Simulate OTP/SMS sending - completely free, unlimited, and runs anywhere!
                    kotlinx.coroutines.delay(2500) // Realistic networks duration with window to stop

                    // Log into local Room Database
                    val newLog = SmsLogEntry(
                        phoneNumber = number,
                        message = text,
                        timestamp = System.currentTimeMillis(),
                        status = "SIMULATED"
                    )
                    repository.insertLog(newLog)

                    // Update UI state with receipt notification
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            sendStatus = SendStatus.Success,
                            simulatedNotification = text
                        )
                    }
                } else {
                    // Initialize SmsManager
                    val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java) ?: throw Exception("SMS Service not available on this device")
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault() ?: throw Exception("SMS Manager is not loaded")
                    }

                    // Split message if it's too long
                    val parts = smsManager.divideMessage(text)
                    if (parts.size > 1) {
                        smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                    } else {
                        smsManager.sendTextMessage(number, null, text, null, null)
                    }

                    // Log into Room Database
                    val newLog = SmsLogEntry(
                        phoneNumber = number,
                        message = text,
                        timestamp = System.currentTimeMillis(),
                        status = "SENT"
                    )
                    repository.insertLog(newLog)

                    // Update UI state
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            sendStatus = SendStatus.Success,
                            messageText = "" // keep phone number for potential successive texts, clear message body
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Failed to send SMS", e)
                
                // Even on exception, log the failure to database
                val failedLog = SmsLogEntry(
                    phoneNumber = number,
                    message = text,
                    timestamp = System.currentTimeMillis(),
                    status = "FAILED"
                )
                repository.insertLog(failedLog)

                _uiState.update {
                    it.copy(
                        isSending = false,
                        sendStatus = SendStatus.Error(e.localizedMessage ?: "Unknown hardware / permission error")
                    )
                }
            }
        }
    }

    fun generateOtp() {
        val length = _uiState.value.otpLength
        val digits = "0123456789"
        val code = (1..length).map { digits.random() }.joinToString("")
        val currentPhone = _uiState.value.phoneNumber
        val phoneToUse = if (currentPhone.isBlank()) "+91 98765 43210" else currentPhone
        _uiState.update { 
            it.copy(
                phoneNumber = phoneToUse,
                otpCode = code,
                messageText = "[QuickSMS] Your secure OTP is: $code. Valid for 5 minutes.",
                otpVerificationStatus = OtpVerificationStatus.Idle,
                otpAttemptCode = ""
            ) 
        }
    }

    fun updateOtpLength(length: Int) {
        _uiState.update { it.copy(otpLength = length) }
        generateOtp()
    }

    fun updateOtpAttemptCode(attempt: String) {
        _uiState.update { it.copy(otpAttemptCode = attempt, otpVerificationStatus = OtpVerificationStatus.Idle) }
    }

    fun verifyOtp() {
        val state = _uiState.value
        if (state.otpCode.isNotEmpty() && state.otpAttemptCode == state.otpCode) {
            _uiState.update { it.copy(otpVerificationStatus = OtpVerificationStatus.Valid) }
        } else {
            _uiState.update { it.copy(otpVerificationStatus = OtpVerificationStatus.Invalid) }
        }
    }

    fun updateSimulationMode(enabled: Boolean) {
        _uiState.update { it.copy(isSimulationMode = enabled) }
    }

    fun dismissSimulatedNotification() {
        _uiState.update { it.copy(simulatedNotification = null) }
    }

    fun stopSending() {
        sendJob?.cancel()
        _uiState.update { 
            it.copy(
                isSending = false,
                sendStatus = SendStatus.Idle
            )
        }
    }

    fun deleteLog(log: SmsLogEntry) {
        viewModelScope.launch {
            repository.deleteLog(log)
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }
}

class SmsViewModelFactory(private val repository: SmsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SmsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SmsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

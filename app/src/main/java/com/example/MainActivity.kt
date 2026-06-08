package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.SmsDatabase
import com.example.data.SmsLogEntry
import com.example.data.SmsRepository
import com.example.ui.SendStatus
import com.example.ui.OtpVerificationStatus
import com.example.ui.SmsUiState
import com.example.ui.SmsViewModel
import com.example.ui.SmsViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Database & Repository
        val database = SmsDatabase.getDatabase(applicationContext)
        val repository = SmsRepository(database.smsLogDao)

        setContent {
            MyApplicationTheme {
                // Instantiating elements
                val viewModel: SmsViewModel = viewModel(
                    factory = SmsViewModelFactory(repository)
                )
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmsMainScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SmsMainScreen(viewModel: SmsViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val uiState by viewModel.uiState.collectAsState()
    val smsLogs by viewModel.smsLogs.collectAsState()

    // Setup permission launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setPermissionGranted(isGranted)
        if (isGranted) {
            Toast.makeText(context, "SMS Permission Granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context, 
                "SMS Permission is required to send direct messages.", 
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Setup contact picker launcher
    val pickContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val contactUri = result.data?.data
            if (contactUri != null) {
                val (name, number) = getContactDetailsFromUri(context, contactUri)
                if (number != null) {
                    viewModel.onPhoneNumberChange(number)
                    if (name != null) {
                        Toast.makeText(context, "Selected: $name", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No phone number found for this contact.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val launchContactPicker = {
        try {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContactLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open contacts list", Toast.LENGTH_SHORT).show()
        }
    }

    // Check permission state in lifecycle
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermissionGranted(hasPermission)
    }

    // Handle toast emissions for Send Status changes
    LaunchedEffect(uiState.sendStatus) {
        when (val status = uiState.sendStatus) {
            is SendStatus.Success -> {
                Toast.makeText(context, "Message Sent Successfully!", Toast.LENGTH_SHORT).show()
                viewModel.clearStatus()
            }
            is SendStatus.Error -> {
                Toast.makeText(context, "Error: ${status.message}", Toast.LENGTH_LONG).show()
                viewModel.clearStatus()
            }
            SendStatus.Idle -> { /* do nothing */ }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "QuickSMS",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (smsLogs.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearLogHistory() },
                            modifier = Modifier.testTag("clear_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Sent History",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Simulated HUD Notification Overlay (Free Sandbox incoming messages animation)
            AnimatedVisibility(
                visible = uiState.simulatedNotification != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                uiState.simulatedNotification?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag("simulation_notification")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Sms,
                                        contentDescription = "Simulated Received SMS",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Simulated Received SMS",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.dismissSimulatedNotification() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    onClick = {
                                        viewModel.updateOtpAttemptCode(uiState.otpCode)
                                        viewModel.dismissSimulatedNotification()
                                        Toast.makeText(context, "OTP Code Auto-filled!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("autofill_otp_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DoneAll,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Auto-fill OTP Code", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Permission Card (Show only if permission is not granted and NOT in simulation mode)
                if (!uiState.isPermissionGranted && !uiState.isSimulationMode) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("permission_rationale_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.SmsFailed,
                                    contentDescription = "Permission Denied Icon",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "SMS Permission Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "To send direct text messages, Android requires runtime permission. Click below to grant permission.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { requestPermissionLauncher.launch(Manifest.permission.SEND_SMS) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.testTag("grant_permission_button")
                                ) {
                                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // SMS Composer Editor Panel
                item {
                    ComposerPanel(
                        uiState = uiState,
                        onPhoneNumberChange = viewModel::onPhoneNumberChange,
                        onMessageChange = viewModel::onMessageChange,
                        onPresetSelect = viewModel::selectPresetMessage,
                        onSendClick = {
                            keyboardController?.hide()
                            if (uiState.isSimulationMode || uiState.isPermissionGranted) {
                                viewModel.sendSms(context)
                            } else {
                                requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            }
                        },
                        presetMessages = viewModel.presetMessages,
                        onClearClick = viewModel::clearInputs,
                        onPickContactClick = launchContactPicker,
                        onStopClick = viewModel::stopSending
                    )
                }

                // OTP Sandbox Panel - Unlimited free simulation
                item {
                    OtpSandboxPanel(
                        uiState = uiState,
                        onGenerateOtp = { viewModel.generateOtp() },
                        onVerifyOtp = { viewModel.verifyOtp() },
                        onOtpAttemptChange = { viewModel.updateOtpAttemptCode(it) },
                        onOtpLengthChange = { viewModel.updateOtpLength(it) },
                        onSimulationModeChange = { viewModel.updateSimulationMode(it) }
                    )
                }

                // Sent History Title
                item {
                    Text(
                        text = "Sent History Log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                // Sent History Log List
                if (smsLogs.isEmpty()) {
                    item {
                        EmptyStateCard()
                    }
                } else {
                    items(
                        items = smsLogs,
                        key = { it.id }
                    ) { logEntry ->
                        LogItemRow(
                            logEntry = logEntry,
                            onDeleteClick = { viewModel.deleteLog(logEntry) },
                            onItemClick = {
                                viewModel.onPhoneNumberChange(logEntry.phoneNumber)
                                viewModel.onMessageChange(logEntry.message)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerPanel(
    uiState: SmsUiState,
    onPhoneNumberChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onPresetSelect: (String) -> Unit,
    onSendClick: () -> Unit,
    presetMessages: List<String>,
    onClearClick: () -> Unit,
    onPickContactClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("composer_panel")
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New Message",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                if (uiState.phoneNumber.isNotEmpty() || uiState.messageText.isNotEmpty()) {
                    TextButton(
                        onClick = onClearClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.testTag("clear_inputs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "Clear Inputs",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Phone Field
            OutlinedTextField(
                value = uiState.phoneNumber,
                onValueChange = onPhoneNumberChange,
                label = { Text("Recipient Phone Number") },
                placeholder = { Text("e.g. +91 98765 43210") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Phone,
                        contentDescription = "Contact Phone",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (uiState.phoneNumber.isNotEmpty()) {
                            IconButton(
                                onClick = { onPhoneNumberChange("") },
                                modifier = Modifier.testTag("clear_phone_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear Number",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        IconButton(
                            onClick = onPickContactClick,
                            modifier = Modifier.testTag("pick_contact_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Contacts,
                                contentDescription = "Pick Contact from address book",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone_input")
            )

            // Indian Numbers Validation & Auto-Formatting Actions
            val digitsOnly = uiState.phoneNumber.replace(Regex("[^0-9]"), "")
            val isUnformattedIndian = digitsOnly.length == 10 && digitsOnly.first() in '6'..'9' && !uiState.phoneNumber.startsWith("+91")
            
            if (isUnformattedIndian) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .clickable { onPhoneNumberChange(formatToIndianNumber(uiState.phoneNumber)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🇮🇳", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Indian mobile number detected. Format to +91?",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Tap to Format",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else if (isIndianPhoneNumber(uiState.phoneNumber)) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text("🇮🇳", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Valid Indian phone number format",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Quick Indian Demo Contacts Suggestion Chips
            if (uiState.phoneNumber.isEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Try Indian Sandbox Demo Contacts:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val indiaPresets = listOf(
                        Pair("Vijay (Mumbai)", "+91 98200 12345"),
                        Pair("Ananya (Bangalore)", "+91 98450 67890"),
                        Pair("Rajesh (Delhi)", "+91 98110 54321")
                    )
                    items(indiaPresets) { preset ->
                        SuggestionChip(
                            onClick = { onPhoneNumberChange(preset.second) },
                            label = { Text(preset.first, style = MaterialTheme.typography.labelSmall) },
                            icon = { Text("🇮🇳", fontSize = 12.sp) },
                            modifier = Modifier.testTag("indian_contact_preset_${preset.first.take(3).lowercase(Locale.ROOT)}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Message Body Field
            val charCount = uiState.messageText.length
            // Calculate standard SMS parts (160 characters per part)
            val smsParts = if (charCount <= 160) 1 else (charCount + 152) / 153 // 153 characters for multi-part messages due to headers
            val maxCharsInPart = if (smsParts == 1) 160 else 153
            val remainingInPart = if (smsParts == 1) {
                160 - charCount
            } else {
                (smsParts * 153) - charCount
            }
            
            OutlinedTextField(
                value = uiState.messageText,
                onValueChange = onMessageChange,
                label = { Text("Message Body") },
                placeholder = { Text("Type SMS text here...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "Message icon",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (smsParts > 1) "Multi-part SMS warning" else "Standard rate applies",
                            color = if (smsParts > 1) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                            fontWeight = if (smsParts > 1) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "$charCount chars • $smsParts SMS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("message_input")
            )

            if (charCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("sms_split_indicator"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val progress = (maxCharsInPart - remainingInPart).toFloat() / maxCharsInPart
                    val trackerColor = if (remainingInPart <= 10) {
                        MaterialTheme.colorScheme.error
                    } else if (remainingInPart <= 30) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Segment Info",
                            tint = trackerColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (remainingInPart == 0) {
                                "Next char splits to Part ${smsParts + 1}"
                            } else {
                                "$remainingInPart characters left before Part ${smsParts + 1} split"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = trackerColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .clip(CircleShape),
                        color = trackerColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Preset Titles
            Text(
                text = "Preset Quick Texts",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(presetMessages) { preset ->
                    SuggestionChip(
                        onClick = { onPresetSelect(preset) },
                        label = { Text(preset, fontWeight = FontWeight.Medium) },
                        shape = RoundedCornerShape(10.dp),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = null,
                        modifier = Modifier.testTag("preset_chip_$preset")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions Row (Send / Stop when transmitting)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isSending) {
                    // Transmitting status button
                    Button(
                        onClick = {},
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            disabledContentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 52.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Transmitting...",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Stop Send Button with Stop Icon
                    Button(
                        onClick = onStopClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        modifier = Modifier
                            .weight(0.7f)
                            .heightIn(min = 52.dp)
                            .testTag("stop_send_button")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Transmitting",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Stop",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Standard Send Button
                    Button(
                        onClick = onSendClick,
                        enabled = uiState.phoneNumber.isNotBlank() && uiState.messageText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("send_button")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isSimulationMode) "Send (Simulation Mode)" else if (uiState.isPermissionGranted) "Send SMS Now" else "Request & Send SMS",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("empty_state_card")
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubble,
                contentDescription = "Chat Bubble",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No Messages Sent Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your direct text logs from this app will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LogItemRow(
    logEntry: SmsLogEntry,
    onDeleteClick: () -> Unit,
    onItemClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault()) }
    val formattedTime = remember(logEntry.timestamp) { dateFormat.format(Date(logEntry.timestamp)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .testTag("log_row_${logEntry.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Recipient Avatar",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = logEntry.phoneNumber,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // Status Badge & Simple Delete Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isSuccess = logEntry.status == "SENT" || logEntry.status == "SIMULATED"
                    val statusColor = if (isSuccess) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                    val statusTextColor = if (isSuccess) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(statusColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = logEntry.status,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusTextColor
                        )
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_log_button_${logEntry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Sent Log",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body Message
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = logEntry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpSandboxPanel(
    uiState: SmsUiState,
    onGenerateOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onOtpAttemptChange: (String) -> Unit,
    onOtpLengthChange: (Int) -> Unit,
    onSimulationModeChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("otp_sandbox_panel")
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Section Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Unlimited Free OTP Sandbox",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Informative tag
            Text(
                text = "Use Sandbox Simulation Mode to test OTP workflows completely free and without device hardware limitations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action: Simulation Mode Switch Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SMS Simulation Mode",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (uiState.isSimulationMode) "100% Free & Unlimited sends" else "Requires standard cellular carrier charges",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.isSimulationMode,
                    onCheckedChange = onSimulationModeChange,
                    modifier = Modifier.testTag("otp_simulation_switch")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // OTP Configuration (Select Length: 4, 6, 8 numbers)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "OTP Code Length",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        listOf(4, 6, 8).forEach { length ->
                            FilterChip(
                                selected = uiState.otpLength == length,
                                onClick = { onOtpLengthChange(length) },
                                label = { Text("$length Digits") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("otp_length_$length")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action: Generate Code
            Button(
                onClick = onGenerateOtp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("generate_otp_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Autorenew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Secure Code", fontWeight = FontWeight.Bold)
            }

            // Interactive Verification Sandbox Terminal
            if (uiState.otpCode.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Verify Code Entry Terminal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Input the generated code here to simulate receiver-side OTP validation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = uiState.otpAttemptCode,
                        onValueChange = onOtpAttemptChange,
                        placeholder = { Text("Code: e.g. ${uiState.otpCode}") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("otp_verification_input"),
                        trailingIcon = {
                            if (uiState.otpAttemptCode.isNotEmpty()) {
                                IconButton(onClick = { onOtpAttemptChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear Input",
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    )

                    Button(
                        onClick = onVerifyOtp,
                        enabled = uiState.otpAttemptCode.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("verify_otp_button")
                    ) {
                        Text("Verify", fontWeight = FontWeight.Bold)
                    }
                }

                // Verification Feedback Box
                if (uiState.otpVerificationStatus != OtpVerificationStatus.Idle) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val cardBg = when (uiState.otpVerificationStatus) {
                        OtpVerificationStatus.Valid -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    }
                    val textColor = when (uiState.otpVerificationStatus) {
                        OtpVerificationStatus.Valid -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }
                    val resultIcon = when (uiState.otpVerificationStatus) {
                        OtpVerificationStatus.Valid -> Icons.Default.CheckCircle
                        else -> Icons.Default.Error
                    }
                    val feedbackText = when (uiState.otpVerificationStatus) {
                        OtpVerificationStatus.Valid -> "ACCESS APPROVED • Code is correct & verified!"
                        else -> "ACCESS REJECTED • Invalid OTP. Check code & retry."
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardBg)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = resultIcon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = feedbackText,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun getContactDetailsFromUri(context: Context, contactUri: android.net.Uri): Pair<String?, String?> {
    var name: String? = null
    var phoneNumber: String? = null
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    try {
        context.contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
                if (numberIndex != -1) {
                    phoneNumber = cursor.getString(numberIndex)
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error querying contact details", e)
    }
    return Pair(name, phoneNumber)
}

private fun isIndianPhoneNumber(phone: String): Boolean {
    val clean = phone.replace(Regex("[^0-9]"), "")
    return when {
        clean.length == 10 && clean.first() in '6'..'9' -> true
        clean.length == 12 && clean.startsWith("91") && clean[2] in '6'..'9' -> true
        clean.length == 11 && clean.startsWith("0") && clean[1] in '6'..'9' -> true
        else -> false
    }
}

private fun formatToIndianNumber(phone: String): String {
    val clean = phone.replace(Regex("[^0-9]"), "")
    return when {
        clean.length == 10 && clean.first() in '6'..'9' -> "+91 " + clean.take(5) + " " + clean.drop(5)
        clean.length == 12 && clean.startsWith("91") && clean[2] in '6'..'9' -> "+91 " + clean.drop(2).take(5) + " " + clean.drop(7)
        clean.length == 11 && clean.startsWith("0") && clean[1] in '6'..'9' -> "+91 " + clean.drop(1).take(5) + " " + clean.drop(6)
        else -> phone
    }
}

package cloud.kl8techgroup.kl8wall.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import cloud.kl8techgroup.kl8wall.mqtt.MqttConnectionState
import cloud.kl8techgroup.kl8wall.server.HaDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val PORT_RANGE = 1024..65535
private const val BEARER_MASK_LENGTH = 12
private const val MIN_PIN_LENGTH = 4
private const val MAX_SETTINGS_PIN_LENGTH = 8
private const val SCAN_TIMEOUT_MS = 8000L

/**
 * Reusable Card shell for grouping related options with a primary-accented title.
 */
@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

/**
 * Reusable row layout containing a toggle switch, title, and descriptive subtitle.
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Reusable row containing a value slider, label, and dynamic value indicator.
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Compose bottom sheet for configuring KL8Wall settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember { mutableStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "KL8Wall Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Premium custom Pill-shaped scrollable tabs switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf("General", "Hardware", "MQTT", "Mesh / P2P")
                tabs.forEachIndexed { index, title ->
                    val isSelected = activeTab == index
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { activeTab = index }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                when (activeTab) {
                    0 -> { // General
                        ConnectionCard(viewModel)
                        NetworkSecurityCard(viewModel)
                        SystemMaintenanceCard()
                        DeveloperSection()
                    }
                    1 -> { // Hardware
                        DisplaySleepCard(viewModel)
                        BatterySaverCard(viewModel)
                        IntercomCard(viewModel)
                        VoiceAssistantCard(viewModel)
                    }
                    2 -> { // MQTT
                        MqttIdentityCard(viewModel)
                    }
                    3 -> { // Mesh / P2P
                        MeshNetworkCard(viewModel)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ConnectionCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val startUrl by viewModel.startUrl.collectAsState()
    val haTokenSet by viewModel.haTokenSet.collectAsState()
    var editUrl by remember { mutableStateOf(startUrl) }
    var editToken by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    SettingsCard(title = "Home Assistant Connection") {
        OutlinedTextField(
            value = editUrl,
            onValueChange = { editUrl = it },
            label = { Text("Home Assistant URL") },
            placeholder = { Text("https://homeassistant.local:8123") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        HaDiscoveryPicker(onInstanceSelected = { editUrl = it })

        OutlinedTextField(
            value = editToken,
            onValueChange = { editToken = it },
            label = { Text(if (haTokenSet) "HA Token (saved)" else "HA Long-Lived Access Token") },
            placeholder = { Text(if (haTokenSet) "••••••••" else "Paste token here") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TokenVisibilityToggle(showToken) { showToken = !showToken } },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (editUrl.isNotBlank()) viewModel.setStartUrl(editUrl.trim())
                if (editToken.isNotBlank()) {
                    viewModel.setHaToken(editToken.trim())
                    editToken = ""
                }
                Toast.makeText(context, "Connection settings saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Connection") }
    }
}

@Composable
private fun DisplaySleepCard(viewModel: SettingsViewModel) {
    val screenAlwaysOn by viewModel.screenAlwaysOn.collectAsState()
    val autoWakeOnPower by viewModel.autoWakeOnPower.collectAsState()
    val autoBrightnessEnabled by viewModel.autoBrightnessEnabled.collectAsState()
    val minBrightnessPercent by viewModel.minBrightnessPercent.collectAsState()
    val manualBrightnessPercent by viewModel.manualBrightnessPercent.collectAsState()
    val lowPowerModeEnabled by viewModel.lowPowerModeEnabled.collectAsState()
    val presenceSensorEnabled by viewModel.presenceSensorEnabled.collectAsState()
    val presenceTimeoutSeconds by viewModel.presenceTimeoutSeconds.collectAsState()
    val mediaPlaybackRequiresGesture by viewModel.mediaPlaybackRequiresGesture.collectAsState()

    SettingsCard(title = "Display & Sleep") {
        SettingsToggleRow(
            title = "Keep Screen Always On",
            description = "Bypass standard Android system display timeouts",
            checked = screenAlwaysOn,
            onCheckedChange = viewModel::setScreenAlwaysOn
        )
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Auto-Wake on Power",
            description = "Automatically wake display on power supply changes",
            checked = autoWakeOnPower,
            onCheckedChange = viewModel::setAutoWakeOnPower
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Ambient Auto-Brightness",
            description = "Adjust screen brightness dynamically based on light sensor levels",
            checked = autoBrightnessEnabled,
            onCheckedChange = viewModel::setAutoBrightnessEnabled
        )

        if (autoBrightnessEnabled) {
            SettingsSliderRow(
                title = "Minimum Brightness Floor",
                valueText = "$minBrightnessPercent%",
                value = minBrightnessPercent.toFloat(),
                onValueChange = { viewModel.setMinBrightnessPercent(it.toInt()) },
                valueRange = 0f..100f
            )
        } else {
            SettingsSliderRow(
                title = "Static Manual Brightness",
                valueText = "$manualBrightnessPercent%",
                value = manualBrightnessPercent.toFloat(),
                onValueChange = { viewModel.setManualBrightnessPercent(it.toInt()) },
                valueRange = 0f..100f
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Low-Power Sleep Mode",
            description = "Pause WebView and dim display completely when absent AND dark",
            checked = lowPowerModeEnabled,
            onCheckedChange = viewModel::setLowPowerModeEnabled
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Blended Presence Sensor",
            description = "Detect user presence via light, proximity, and screen touch",
            checked = presenceSensorEnabled,
            onCheckedChange = viewModel::setPresenceSensorEnabled
        )

        if (presenceSensorEnabled) {
            SettingsSliderRow(
                title = "Presence Timeout Cooldown",
                valueText = "${presenceTimeoutSeconds}s",
                value = presenceTimeoutSeconds.toFloat(),
                onValueChange = { viewModel.setPresenceTimeoutSeconds(it.toInt()) },
                valueRange = 10f..600f
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Require Gesture for Media",
            description = "Require explicit user touch for WebView media autoplay",
            checked = mediaPlaybackRequiresGesture,
            onCheckedChange = viewModel::setMediaPlaybackRequiresGesture
        )
    }
}

@Composable
private fun BatterySaverCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val batterySaverEnabled by viewModel.batterySaverEnabled.collectAsState()
    val batterySaverEntityId by viewModel.batterySaverEntityId.collectAsState()
    val batterySaverMin by viewModel.batterySaverMin.collectAsState()
    val batterySaverMax by viewModel.batterySaverMax.collectAsState()

    val app = context.applicationContext as? KL8WallApplication
    val chargerOnState = app?.batterySaverManager?.chargerState
    val chargerOn = chargerOnState?.collectAsState()?.value ?: false

    var editEntityId by remember(batterySaverEntityId) { mutableStateOf(batterySaverEntityId) }

    SettingsCard(title = "Battery Saver (Smart Charging)") {
        SettingsToggleRow(
            title = "Enable Smart Charging",
            description = "Periodically disconnects the charger to preserve battery health",
            checked = batterySaverEnabled,
            onCheckedChange = viewModel::setBatterySaverEnabled
        )

        if (batterySaverEnabled) {
            OutlinedTextField(
                value = editEntityId,
                onValueChange = { editEntityId = it },
                label = { Text("Home Assistant Switch Entity ID") },
                placeholder = { Text("switch.tablet_charger") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    viewModel.setBatterySaverEntityId(editEntityId.trim())
                    Toast.makeText(context, "Entity ID saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Entity ID") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            SettingsSliderRow(
                title = "Minimum Charge Threshold",
                valueText = "$batterySaverMin%",
                value = batterySaverMin.toFloat(),
                onValueChange = { viewModel.setBatterySaverMin(it.toInt()) },
                valueRange = 10f..50f
            )

            SettingsSliderRow(
                title = "Maximum Charge Threshold",
                valueText = "$batterySaverMax%",
                value = batterySaverMax.toFloat(),
                onValueChange = { viewModel.setBatterySaverMax(it.toInt()) },
                valueRange = 60f..95f
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Charger Plug State", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (chargerOn) "ON (Charging)" else "OFF (Not Charging)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chargerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        app?.batterySaverManager?.setChargerStateOverride(!chargerOn)
                    }
                ) {
                    Text(if (chargerOn) "Turn Plug OFF" else "Turn Plug ON")
                }
            }
        }
    }
}

@Composable
private fun CopyableYamlCard(title: String, yamlText: String) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(yamlText))
                    Toast.makeText(context, "YAML copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy YAML",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF1E1E2E),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = yamlText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = Color(0xFFCDD6F4)
            )
        }
    }
}

@Composable
private fun IntercomCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val intercomTarget by viewModel.intercomTarget.collectAsState()
    var editTarget by remember(intercomTarget) { mutableStateOf(intercomTarget) }
    val deviceName by viewModel.deviceName.collectAsState()

    val app = context.applicationContext as? KL8WallApplication
    val intercomManager = app?.intercomManager

    var isRecording by remember { mutableStateOf(intercomManager?.isRecordingActive ?: false) }

    DisposableEffect(intercomManager) {
        val originalListener = intercomManager?.onStateChanged
        intercomManager?.onStateChanged = { recording ->
            isRecording = recording
            originalListener?.invoke(recording)
        }
        onDispose {
            intercomManager?.onStateChanged = originalListener
        }
    }

    var isLoopbackTesting by remember { mutableStateOf(false) }
    var loopbackCountdown by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    SettingsCard(title = "Intercom Settings") {
        OutlinedTextField(
            value = editTarget,
            onValueChange = { editTarget = it },
            label = { Text("Default Target Device Name") },
            placeholder = { Text("living_room") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                viewModel.setIntercomTarget(editTarget.trim())
                app?.mqttManager?.publishIntercomTargetState(editTarget.trim())
                Toast.makeText(context, "Intercom target saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Target Device") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Microphone Recording", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (isRecording) "Recording..." else "Idle",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isRecording,
                onCheckedChange = { start ->
                    if (start) {
                        intercomManager?.startRecording(editTarget.trim())
                    } else {
                        intercomManager?.stopRecording()
                    }
                }
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Audio Loopback Test",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Record 3 seconds of audio from the microphone and play it back locally to test mic and speaker hardware.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = {
                    if (isLoopbackTesting) return@Button
                    isLoopbackTesting = true
                    scope.launch {
                        val tempAudioChunks = mutableListOf<ByteArray>()
                        val testIntercom = cloud.kl8techgroup.kl8wall.intercom.IntercomManager(context) { _, bytes ->
                            tempAudioChunks.add(bytes)
                        }
                        
                        testIntercom.startRecording("loopback_test")
                        for (i in 3 downTo 1) {
                            loopbackCountdown = i
                            delay(1000)
                        }
                        testIntercom.stopRecording()
                        
                        loopbackCountdown = 0
                        Toast.makeText(context, "Playing back recorded audio...", Toast.LENGTH_SHORT).show()
                        tempAudioChunks.forEach { chunk ->
                            testIntercom.handleIncomingAudio(chunk)
                            delay(40)
                        }
                        delay(1000)
                        testIntercom.stopPlayback()
                        isLoopbackTesting = false
                    }
                },
                enabled = !isLoopbackTesting && !isRecording,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLoopbackTesting) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary
                )
            ) {
                if (isLoopbackTesting) {
                    Text("Recording: ${loopbackCountdown}s...")
                } else {
                    Text("Start Loopback Test")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Home Assistant Lovelace Cards",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Use the copyable YAML configurations below to add intercom buttons to your Home Assistant dashboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val toggleActiveYaml = """
                    type: button
                    name: Broadcast from $deviceName
                    icon: mdi:microphone
                    tap_action:
                      action: toggle
                    entity: switch.kl8wall_${deviceName}_intercom_active
                """.trimIndent()

                val broadcastToThisYaml = """
                    type: button
                    name: Talk to $deviceName
                    icon: mdi:microphone
                    tap_action:
                      action: call-service
                      service: mqtt.publish
                      data:
                        topic: kl8wall/<source_device_name>/intercom/cmd
                        payload: start:$deviceName
                """.trimIndent()

                val stopIntercomYaml = """
                    type: button
                    name: Stop Intercom
                    icon: mdi:microphone-off
                    tap_action:
                      action: call-service
                      service: mqtt.publish
                      data:
                        topic: kl8wall/<source_device_name>/intercom/cmd
                        payload: stop
                """.trimIndent()

                CopyableYamlCard(
                    title = "Toggle $deviceName Intercom",
                    yamlText = toggleActiveYaml
                )

                CopyableYamlCard(
                    title = "Broadcast to $deviceName",
                    yamlText = broadcastToThisYaml
                )

                CopyableYamlCard(
                    title = "Stop Intercom Stream",
                    yamlText = stopIntercomYaml
                )
            }
        }
    }
}

@Composable
private fun VoiceAssistantCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val voiceAssistantEnabled by viewModel.voiceAssistantEnabled.collectAsState()
    val voiceWakeWord by viewModel.voiceWakeWord.collectAsState()
    
    var editWakeWord by remember(voiceWakeWord) { mutableStateOf(voiceWakeWord) }
    
    SettingsCard(title = "Local Voice Assistant") {
        SettingsToggleRow(
            title = "Enable Voice Assistant",
            description = "Periodically listen offline for the wake word to trigger voice commands",
            checked = voiceAssistantEnabled,
            onCheckedChange = { enabled ->
                viewModel.setVoiceAssistantEnabled(enabled)
                val app = context.applicationContext as? KL8WallApplication
                if (enabled) {
                    app?.voiceAssistantManager?.start()
                } else {
                    app?.voiceAssistantManager?.stop()
                }
            }
        )
        
        if (voiceAssistantEnabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            
            OutlinedTextField(
                value = editWakeWord,
                onValueChange = { editWakeWord = it },
                label = { Text("Wake Word") },
                placeholder = { Text("hey wall") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            Button(
                onClick = {
                    viewModel.setVoiceWakeWord(editWakeWord.trim())
                    Toast.makeText(context, "Wake word saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Wake Word") }
        }
    }
}

@Composable
private fun NetworkSecurityCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    val ignoreSslErrors by viewModel.ignoreSslErrors.collectAsState()
    val micShimEnabled by viewModel.micShimEnabled.collectAsState()
    val mdnsEnabled by viewModel.mdnsEnabled.collectAsState()
    val httpPort by viewModel.httpPort.collectAsState()
    val httpBearerToken by viewModel.httpBearerToken.collectAsState()
    val hotCorner by viewModel.hotCorner.collectAsState()
    val isPinSet by viewModel.isPinSet.collectAsState()
    val allowedHosts by viewModel.allowedHosts.collectAsState()

    var editPort by remember { mutableStateOf(httpPort.toString()) }
    var showBearerToken by remember { mutableStateOf(false) }

    SettingsCard(title = "Network, API & Security") {
        SettingsToggleRow(
            title = "Bypass WebView SSL Errors",
            description = "Ignore self-signed SSL/TLS certificate warnings",
            checked = ignoreSslErrors,
            onCheckedChange = viewModel::setIgnoreSslErrors
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Enable Insecure Microphone Shim",
            description = "Enables voice assistant microphone captures over local HTTP",
            checked = micShimEnabled,
            onCheckedChange = viewModel::setMicShimEnabled
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Enable Local mDNS Advertising",
            description = "Publish local network service discovery records",
            checked = mdnsEnabled,
            onCheckedChange = viewModel::setMdnsEnabled
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HTTP Server Port", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = editPort,
                    onValueChange = { editPort = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val port = editPort.toIntOrNull()
                        if (port != null && port in PORT_RANGE) {
                            viewModel.setHttpPort(port)
                            Toast.makeText(context, "Server port updated to $port", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid port (1024-65535)", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Save Port") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("API Bearer Token", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = if (showBearerToken) httpBearerToken else "•".repeat(BEARER_MASK_LENGTH),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showBearerToken = !showBearerToken }) {
                            Icon(
                                if (showBearerToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility"
                            )
                        }
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(httpBearerToken))
                            Toast.makeText(context, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy token") }
                        IconButton(onClick = {
                            viewModel.rotateHttpBearerToken()
                            Toast.makeText(context, "Token rotated", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.Refresh, contentDescription = "Rotate token") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings Access Hot Corner", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            HotCornerSelector(hotCorner = hotCorner, onSelected = viewModel::setHotCorner)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PIN Access Protection", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            PinManagement(
                isPinSet = isPinSet,
                onSetPin = viewModel::setPin,
                onClearPin = viewModel::clearPin
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Allowed Navigation Hosts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (allowedHosts.isEmpty()) {
                Text(
                    text = "No restrictions. All navigation permitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                allowedHosts.forEach { host ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { viewModel.removeAllowedHost(host) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MqttIdentityCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsState()
    val mqttEnabled by viewModel.mqttEnabled.collectAsState()
    val mqttBroker by viewModel.mqttBroker.collectAsState()
    val mqttPort by viewModel.mqttPort.collectAsState()
    val mqttUsername by viewModel.mqttUsername.collectAsState()
    val mqttPassword by viewModel.mqttPassword.collectAsState()
    val sensorIntervalSeconds by viewModel.sensorIntervalSeconds.collectAsState()
    val bluetoothProxyEnabled by viewModel.bluetoothProxyEnabled.collectAsState()
    val cameraIntervalMinutes by viewModel.cameraIntervalMinutes.collectAsState()

    var editDeviceName by remember(deviceName) { mutableStateOf(deviceName) }
    var editEnabled by remember(mqttEnabled) { mutableStateOf(mqttEnabled) }
    var editBroker by remember(mqttBroker) { mutableStateOf(mqttBroker) }
    var editPort by remember(mqttPort) { mutableStateOf(mqttPort.toString()) }
    var editUsername by remember(mqttUsername) { mutableStateOf(mqttUsername) }
    var editPassword by remember(mqttPassword) { mutableStateOf(mqttPassword) }
    var showPassword by remember { mutableStateOf(false) }

    SettingsCard(title = "MQTT & Device Identity") {
        OutlinedTextField(
            value = editDeviceName,
            onValueChange = { editDeviceName = it },
            label = { Text("Device Name") },
            placeholder = { Text("e.g. living_room_wall") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsToggleRow(
            title = "Enable MQTT Broker Integration",
            description = "Connects to broker to publish sensor feeds and fetch remote commands",
            checked = editEnabled,
            onCheckedChange = { editEnabled = it }
        )

        if (editEnabled) {
            OutlinedTextField(
                value = editBroker,
                onValueChange = { editBroker = it },
                label = { Text("MQTT Broker Address") },
                placeholder = { Text("192.168.1.5") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = editPort,
                onValueChange = { editPort = it },
                label = { Text("MQTT Broker Port") },
                placeholder = { Text("1883") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = editUsername,
                onValueChange = { editUsername = it },
                label = { Text("MQTT Username (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = editPassword,
                onValueChange = { editPassword = it },
                label = { Text("MQTT Password (optional)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TokenVisibilityToggle(showPassword) { showPassword = !showPassword } },
                modifier = Modifier.fillMaxWidth()
            )

            val mqttManager = remember { (context.applicationContext as? KL8WallApplication)?.mqttManager }
            val connectionState by (mqttManager?.connectionState ?: MutableStateFlow(MqttConnectionState.DISCONNECTED)).collectAsState()
            val lastError by (mqttManager?.lastError ?: MutableStateFlow(null)).collectAsState()

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MQTT Connection Status",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val (statusText, statusColor) = when (connectionState) {
                            MqttConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
                            MqttConnectionState.CONNECTING -> "Connecting..." to Color(0xFFFF9800)
                            MqttConnectionState.DISCONNECTED -> "Disconnected" to Color(0xFFF44336)
                        }

                        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = statusColor)
                        }

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    lastError?.let { error ->
                        if (error.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { mqttManager?.reconnect() },
                    enabled = connectionState != MqttConnectionState.CONNECTING
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Force Reconnect"
                    )
                }
            }
        }

        Button(
            onClick = {
                if (editDeviceName.isBlank()) {
                    Toast.makeText(context, "Device Name cannot be blank", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.setDeviceName(editDeviceName.trim())
                viewModel.setMqttEnabled(editEnabled)
                viewModel.setMqttBroker(editBroker.trim())
                val port = editPort.toIntOrNull() ?: 1883
                viewModel.setMqttPort(port)
                viewModel.setMqttUsername(editUsername.trim())
                viewModel.setMqttPassword(editPassword.trim())
                Toast.makeText(context, "MQTT settings saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save MQTT Configuration") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsSliderRow(
            title = "Sensor Update Interval",
            valueText = "${sensorIntervalSeconds}s",
            value = sensorIntervalSeconds.toFloat(),
            onValueChange = { viewModel.setSensorIntervalSeconds(it.toInt()) },
            valueRange = 5f..300f
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsToggleRow(
            title = "Bluetooth BLE Proxy",
            description = "Forward local Bluetooth advertisement packets to Home Assistant",
            checked = bluetoothProxyEnabled,
            onCheckedChange = viewModel::setBluetoothProxyEnabled
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        SettingsSliderRow(
            title = "Periodic Camera Snapshot",
            valueText = if (cameraIntervalMinutes == 0) "Disabled" else "${cameraIntervalMinutes}m",
            value = cameraIntervalMinutes.toFloat(),
            onValueChange = { viewModel.setCameraIntervalMinutes(it.toInt()) },
            valueRange = 0f..120f
        )
    }
}

@Composable
private fun MeshNetworkCard(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as? KL8WallApplication
    val peerManager = app?.peerManager
    
    val manualPeers by viewModel.manualPeers.collectAsState()
    var editManualPeers by remember(manualPeers) { mutableStateOf(manualPeers) }
    
    // Live Discovered Peers state
    var peerList by remember { mutableStateOf(emptyList<cloud.kl8techgroup.kl8wall.peer.PeerManager.PeerInfo>()) }
    LaunchedEffect(peerManager) {
        while (true) {
            peerList = peerManager?.peers?.values?.toList() ?: emptyList()
            delay(2000)
        }
    }
    
    // Sync Code Generation state
    var generatedCode by remember { mutableStateOf("") }
    var codeTimeRemaining by remember { mutableStateOf(0) }
    LaunchedEffect(generatedCode) {
        if (generatedCode.isNotEmpty()) {
            codeTimeRemaining = 120
            while (codeTimeRemaining > 0) {
                delay(1000)
                codeTimeRemaining--
            }
            generatedCode = ""
        }
    }
    
    // Config Sync state
    var selectedPeer by remember { mutableStateOf<cloud.kl8techgroup.kl8wall.peer.PeerManager.PeerInfo?>(null) }
    var syncInProgress by remember { mutableStateOf(false) }

    SettingsCard(title = "Mesh & P2P Network") {
        // 1. Live Discovered Peers
        Text(
            text = "Discovered Mesh Panels",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        
        if (peerList.isEmpty()) {
            Text(
                text = "No other mesh panels discovered yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            peerList.forEach { peer ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("${peer.ip}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val ago = (System.currentTimeMillis() - peer.lastSeen) / 1000
                            Text("Last seen: ${ago}s ago", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Mesh Status
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                                    drawCircle(color = Color(0xFF4CAF50))
                                }
                                Text(
                                    text = "Mesh Online",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            // MQTT Status
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val color = if (peer.mqttConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                                androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                                    drawCircle(color = color)
                                }
                                Text(
                                    text = if (peer.mqttConnected) "MQTT Online" else "MQTT Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = color,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
        
        // 2. Share Settings (Generate Sync Code)
        Text(
            text = "Share Settings to New Panel",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Generate a temporary 6-digit sync code that another panel can use to securely download your settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (generatedCode.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Temporary Sync Code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        text = "${generatedCode.take(3)} ${generatedCode.drop(3)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Expires in ${codeTimeRemaining}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else {
            Button(
                onClick = {
                    generatedCode = peerManager?.generateSyncCode() ?: ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Sync Code")
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
        
        // 3. Receive Settings (Auto-Fill & Secure Sync)
        Text(
            text = "Import Settings from Peer",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        
        val onlinePeers = peerList
        if (onlinePeers.isEmpty()) {
            Text(
                text = "No peers available to import from.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select Peer:", style = MaterialTheme.typography.bodySmall)
                
                // Render a simple Row list of peers to tap and select
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    onlinePeers.forEach { peer ->
                        val isSel = selectedPeer?.name == peer.name
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedPeer = peer }
                                .padding(8.dp)
                                .width(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                selectedPeer?.let { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                syncInProgress = true
                                viewModel.syncPublicConfig(
                                    peerIp = peer.ip,
                                    peerPort = peer.port,
                                    onSuccess = {
                                        syncInProgress = false
                                        Toast.makeText(context, "Public settings synced!", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        syncInProgress = false
                                        Toast.makeText(context, "Sync failed: $err", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            enabled = !syncInProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sync Preferences")
                        }
                        
                        Button(
                            onClick = {
                                syncInProgress = true
                                viewModel.syncSecureConfig(
                                    peerIp = peer.ip,
                                    peerPort = peer.port,
                                    onSuccess = {
                                        syncInProgress = false
                                        Toast.makeText(context, "All settings & credentials synced!", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        syncInProgress = false
                                        Toast.makeText(context, "Credentials sync failed: $err", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            enabled = !syncInProgress,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (syncInProgress) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onSecondary)
                            } else {
                                Text("Sync All (Credentials)")
                            }
                        }
                    }
                    Text(
                        text = "Note: 'Sync All' triggers a 'Tap-to-Approve' prompt on the other panel's screen to safely transfer passwords.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            
            Text(
                text = "Manual Mesh Peer Connections",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "For panels on separate subnets/routers where auto-discovery is blocked, enter their IP addresses (and optional port) separated by commas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            OutlinedTextField(
                value = editManualPeers,
                onValueChange = { editManualPeers = it },
                label = { Text("Manual Peer IPs") },
                placeholder = { Text("192.168.1.50, 192.168.2.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            Button(
                onClick = {
                    viewModel.setManualPeers(editManualPeers.trim())
                    Toast.makeText(context, "Manual peer list saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Manual Peers")
            }
        }
    }
}

@Composable
private fun SystemMaintenanceCard() {
    val context = LocalContext.current
    val app = context.applicationContext as? KL8WallApplication
    val ota = app?.otaManager ?: return

    val updateAvailable by ota.updateAvailable.collectAsState()
    val latestVersion by ota.latestVersion.collectAsState()
    val latestVersionCode by ota.latestVersionCode.collectAsState()
    val isUpdating by ota.isUpdating.collectAsState()
    val updateError by ota.updateError.collectAsState()

    val scope = rememberCoroutineScope()

    SettingsCard(title = "System Maintenance") {
        Text(
            text = "OTA Updates",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Current Version: ${ota.currentVersionName} (code ${ota.currentVersionCode})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (updateAvailable) {
            Text(
                text = "Update Available: v$latestVersion (code $latestVersionCode)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                text = "Your app is up to date.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (updateError != null) {
            Text(
                text = "Error: $updateError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        ota.checkForUpdates(false)
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.weight(1f)
            ) { Text("Check Updates") }

            if (updateAvailable) {
                Button(
                    onClick = {
                        scope.launch {
                            ota.triggerUpdate()
                        }
                    },
                    enabled = !isUpdating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Install Update")
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

        Text(
            text = "App Cache Management",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )

        Button(
            onClick = {
                app?.deviceController?.clearCache()
                Toast.makeText(context, "Cache and storage cleared successfully", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear Cache & Local Storage")
        }
    }
}

/**
 * First-run setup screen shown on initial launch.
 * Minimal flow: enter HA URL and token, then drop into kiosk mode.
 */
@Composable
fun FirstRunSetup(viewModel: SettingsViewModel, onComplete: () -> Unit) {
    val context = LocalContext.current
    
    // HA states
    var url by remember { mutableStateOf(viewModel.startUrl.value) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    // MQTT & Identity states
    val deviceNameDefault by viewModel.deviceName.collectAsState()
    val mqttEnabledDefault by viewModel.mqttEnabled.collectAsState()
    val mqttBrokerDefault by viewModel.mqttBroker.collectAsState()
    val mqttPortDefault by viewModel.mqttPort.collectAsState()
    val mqttUsernameDefault by viewModel.mqttUsername.collectAsState()
    val mqttPasswordDefault by viewModel.mqttPassword.collectAsState()

    var editDeviceName by remember(deviceNameDefault) { mutableStateOf(deviceNameDefault.ifBlank { "kl8wall" }) }
    var editMqttEnabled by remember(mqttEnabledDefault) { mutableStateOf(mqttEnabledDefault) }
    var editMqttBroker by remember(mqttBrokerDefault) { mutableStateOf(mqttBrokerDefault) }
    var editMqttPort by remember(mqttPortDefault) { mutableStateOf(mqttPortDefault.toString()) }
    var editMqttUsername by remember(mqttUsernameDefault) { mutableStateOf(mqttUsernameDefault) }
    var editMqttPassword by remember(mqttPasswordDefault) { mutableStateOf(mqttPasswordDefault) }
    var showMqttPassword by remember { mutableStateOf(false) }

    // Sensors & Proxy states
    val bluetoothProxyEnabledDefault by viewModel.bluetoothProxyEnabled.collectAsState()
    val presenceSensorEnabledDefault by viewModel.presenceSensorEnabled.collectAsState()
    val presenceTimeoutSecondsDefault by viewModel.presenceTimeoutSeconds.collectAsState()
    val cameraIntervalMinutesDefault by viewModel.cameraIntervalMinutes.collectAsState()

    var editBleProxy by remember(bluetoothProxyEnabledDefault) { mutableStateOf(bluetoothProxyEnabledDefault) }
    var editPresence by remember(presenceSensorEnabledDefault) { mutableStateOf(presenceSensorEnabledDefault) }
    var editTimeout by remember(presenceTimeoutSecondsDefault) { mutableStateOf(presenceTimeoutSecondsDefault.toString()) }
    var editCameraInterval by remember(cameraIntervalMinutesDefault) { mutableStateOf(cameraIntervalMinutesDefault.toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Welcome to KL8Wall",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Configure your Home Assistant connection and sensor settings to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        // Easy Setup / Config Sync from other panels
        val peerManager = (context.applicationContext as? KL8WallApplication)?.peerManager
        var peerList by remember { mutableStateOf(emptyList<cloud.kl8techgroup.kl8wall.peer.PeerManager.PeerInfo>()) }
        LaunchedEffect(peerManager) {
            while (true) {
                peerList = peerManager?.peers?.values?.toList() ?: emptyList()
                delay(2000)
            }
        }
        
        var selectedPeerForSetup by remember { mutableStateOf<cloud.kl8techgroup.kl8wall.peer.PeerManager.PeerInfo?>(null) }
        var setupSyncInProgress by remember { mutableStateOf(false) }

        // Home Assistant Server Auto-Discovery Pre-fill
        var resolvedHaUrl by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(peerManager) {
            while (resolvedHaUrl == null) {
                val resolved = peerManager?.localHaUrl
                if (resolved != null && resolved.isNotEmpty()) {
                    resolvedHaUrl = resolved
                    if (url.isBlank()) {
                        url = resolved
                        val uri = java.net.URI(resolved)
                        val host = uri.host
                        if (host != null && host.isNotEmpty() && editMqttBroker.isBlank()) {
                            editMqttBroker = host
                        }
                    }
                }
                delay(1000)
            }
        }

        if (peerList.isNotEmpty()) {
            SettingsCard(title = "Easy Setup: Sync from Peer") {
                Text(
                    text = "Discovered panels on your local network. You can import all settings from them automatically instead of typing them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    peerList.forEach { peer ->
                        val isSel = selectedPeerForSetup?.name == peer.name
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedPeerForSetup = peer }
                                .padding(8.dp)
                                .width(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                selectedPeerForSetup?.let { peer ->
                    Button(
                        onClick = {
                            setupSyncInProgress = true
                            viewModel.syncPublicConfig(
                                peerIp = peer.ip,
                                peerPort = peer.port,
                                onSuccess = {
                                    url = viewModel.startUrl.value
                                    editMqttBroker = viewModel.mqttBroker.value
                                    editMqttPort = viewModel.mqttPort.value.toString()
                                    editMqttUsername = viewModel.mqttUsername.value
                                    editBleProxy = viewModel.bluetoothProxyEnabled.value
                                    editPresence = viewModel.presenceSensorEnabled.value
                                    editTimeout = viewModel.presenceTimeoutSeconds.value.toString()
                                    editCameraInterval = viewModel.cameraIntervalMinutes.value.toString()
                                    
                                    viewModel.syncSecureConfig(
                                        peerIp = peer.ip,
                                        peerPort = peer.port,
                                        onSuccess = {
                                            setupSyncInProgress = false
                                            token = "Saved from sync"
                                            editMqttPassword = "Saved from sync"
                                            Toast.makeText(context, "Full setup credentials imported!", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            setupSyncInProgress = false
                                            Toast.makeText(context, "Imported preferences; credentials failed: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                onError = { err ->
                                    setupSyncInProgress = false
                                    Toast.makeText(context, "Import failed: $err", Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        enabled = !setupSyncInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (setupSyncInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Import Setup from ${peer.name}")
                        }
                    }
                }
            }
        }
        
        // 1. Home Assistant URL Card
        SettingsCard(title = "1. Home Assistant Connection") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Home Assistant URL") },
                placeholder = { Text("https://homeassistant.local:8123") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            
            HaDiscoveryPicker(onInstanceSelected = { url = it })
            
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Long-Lived Access Token (optional)") },
                placeholder = { Text("Paste token from HA profile page") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TokenVisibilityToggle(showToken) { showToken = !showToken } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // 2. MQTT & Device Identity Card
        SettingsCard(title = "2. MQTT & Device Identity") {
            OutlinedTextField(
                value = editDeviceName,
                onValueChange = { editDeviceName = it },
                label = { Text("Device Name") },
                placeholder = { Text("e.g. living_room_wall") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SettingsToggleRow(
                title = "Enable MQTT Integration",
                description = "Forward sensor values and receive remote screen/kiosk commands",
                checked = editMqttEnabled,
                onCheckedChange = { editMqttEnabled = it }
            )

            if (editMqttEnabled) {
                OutlinedTextField(
                    value = editMqttBroker,
                    onValueChange = { editMqttBroker = it },
                    label = { Text("MQTT Broker IP/Host") },
                    placeholder = { Text("192.168.1.5") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = editMqttPort,
                    onValueChange = { editMqttPort = it },
                    label = { Text("MQTT Port") },
                    placeholder = { Text("1883") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = editMqttUsername,
                    onValueChange = { editMqttUsername = it },
                    label = { Text("Username (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = editMqttPassword,
                    onValueChange = { editMqttPassword = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    visualTransformation = if (showMqttPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TokenVisibilityToggle(showMqttPassword) { showMqttPassword = !showMqttPassword } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        // 3. Sensors & Proxy Card
        SettingsCard(title = "3. Sensors & Proxy Preferences") {
            SettingsToggleRow(
                title = "ESPHome Bluetooth Proxy",
                description = "Forward local BLE advertisements to HA via port 6053",
                checked = editBleProxy,
                onCheckedChange = { editBleProxy = it }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            SettingsToggleRow(
                title = "Blended Presence Sensor",
                description = "Detect user presence via light, proximity, and touch to toggle screen power",
                checked = editPresence,
                onCheckedChange = { editPresence = it }
            )

            if (editPresence) {
                val currentTimeout = editTimeout.toIntOrNull() ?: 60
                SettingsSliderRow(
                    title = "Presence Timeout Cooldown",
                    valueText = "${currentTimeout}s",
                    value = currentTimeout.toFloat().coerceIn(10f, 600f),
                    onValueChange = { editTimeout = it.toInt().toString() },
                    valueRange = 10f..600f
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            val currentInterval = editCameraInterval.toIntOrNull() ?: 60
            SettingsSliderRow(
                title = "Periodic Photo Capture",
                valueText = if (currentInterval == 0) "Disabled" else "${currentInterval}m",
                value = currentInterval.toFloat().coerceIn(0f, 120f),
                onValueChange = { editCameraInterval = it.toInt().toString() },
                valueRange = 0f..120f
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        SetupButtons(
            onConnect = {
                if (url.isBlank()) {
                    Toast.makeText(context, "Please enter your HA URL", Toast.LENGTH_SHORT).show()
                    return@SetupButtons
                }
                if (editDeviceName.isBlank()) {
                    Toast.makeText(context, "Device Name cannot be blank", Toast.LENGTH_SHORT).show()
                    return@SetupButtons
                }
                
                // Save HA settings
                viewModel.setStartUrl(url.trim())
                if (token.isNotBlank() && token != "Saved from sync") viewModel.setHaToken(token.trim())
                
                // Save Device Identity & MQTT settings
                viewModel.setDeviceName(editDeviceName.trim())
                viewModel.setMqttEnabled(editMqttEnabled)
                viewModel.setMqttBroker(editMqttBroker.trim())
                val port = editMqttPort.toIntOrNull() ?: 1883
                viewModel.setMqttPort(port)
                viewModel.setMqttUsername(editMqttUsername.trim())
                if (editMqttPassword != "Saved from sync") viewModel.setMqttPassword(editMqttPassword.trim())
                
                // Save Sensors & Proxy settings
                viewModel.setBluetoothProxyEnabled(editBleProxy)
                viewModel.setPresenceSensorEnabled(editPresence)
                val timeout = editTimeout.toIntOrNull() ?: 60
                viewModel.setPresenceTimeoutSeconds(timeout)
                val cameraInterval = editCameraInterval.toIntOrNull() ?: 60
                viewModel.setCameraIntervalMinutes(cameraInterval)
                
                viewModel.completeFirstRun()
                onComplete()
            },
            onSkip = {
                viewModel.completeFirstRun()
                onComplete()
            }
        )
        
        val hotCorner by viewModel.hotCorner.collectAsState()
        Text(
            text = "You can update these preferences anytime by long-pressing the ${hotCorner.displayName.lowercase()} corner.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        DeveloperSection()
    }
}

@Composable
private fun SetupButtons(onConnect: () -> Unit, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onConnect, modifier = Modifier.weight(1f)) { Text("Connect") }
        OutlinedButton(onClick = onSkip, modifier = Modifier.width(120.dp)) { Text("Skip") }
    }
}

@Composable
private fun TokenVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (visible) "Hide token" else "Show token"
        )
    }
}

@Composable
private fun HotCornerSelector(hotCorner: HotCorner, onSelected: (HotCorner) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HotCorner.entries.forEach { corner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(corner) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = corner == hotCorner, onClick = { onSelected(corner) })
                Text(corner.displayName, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PinManagement(isPinSet: Boolean, onSetPin: (String) -> Unit, onClearPin: () -> Unit) {
    if (isPinSet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PIN Protection: Enabled", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onClearPin,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Remove PIN") }
        }
    } else {
        PinSetupFields(onSetPin = onSetPin)
    }
}

@Composable
private fun PinSetupFields(onSetPin: (String) -> Unit) {
    val context = LocalContext.current
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "PIN protection: Disabled (optional protection for access to settings)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = newPin,
            onValueChange = { if (it.length <= MAX_SETTINGS_PIN_LENGTH) newPin = it },
            label = { Text("New settings PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= MAX_SETTINGS_PIN_LENGTH) confirmPin = it },
            label = { Text("Confirm settings PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                when {
                    newPin.length < MIN_PIN_LENGTH ->
                        Toast.makeText(context, "PIN must be at least $MIN_PIN_LENGTH digits", Toast.LENGTH_SHORT).show()
                    newPin != confirmPin ->
                        Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                    else -> {
                        onSetPin(newPin)
                        newPin = ""
                        confirmPin = ""
                        Toast.makeText(context, "PIN protection enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = newPin.isNotEmpty() && confirmPin.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Set PIN") }
    }
}

@Composable
private fun HaDiscoveryPicker(onInstanceSelected: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instances = remember { mutableStateListOf<HaDiscovery.HaInstance>() }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { scanJob?.cancel() }
    }

    OutlinedButton(
        onClick = {
            if (scanning) return@OutlinedButton
            instances.clear()
            scanning = true
            scanJob = scope.launch {
                val seen = mutableSetOf<String>()
                val collector = launch {
                    HaDiscovery.discover(context).collect { instance ->
                        if (seen.add(instance.url)) {
                            instances.add(instance)
                        }
                    }
                }
                delay(SCAN_TIMEOUT_MS)
                collector.cancel()
                scanning = false
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scanning network...")
        } else {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search Local Network")
        }
    }

    instances.forEach { instance ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInstanceSelected(instance.url) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    instance.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (!scanning && instances.isEmpty() && scanJob != null) {
        Text(
            text = "No Home Assistant instances discovered on your local network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeveloperSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Developed by kl8tech.group",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "For support or inquiries, please visit kl8tech.group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

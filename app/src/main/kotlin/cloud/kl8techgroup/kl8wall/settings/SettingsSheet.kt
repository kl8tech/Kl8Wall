package cloud.kl8techgroup.kl8wall.settings

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "KL8Wall Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            ConnectionCard(viewModel)
            DisplaySleepCard(viewModel)
            NetworkSecurityCard(viewModel)
            MqttIdentityCard(viewModel)
            SystemMaintenanceCard()
            DeveloperSection()
            Spacer(modifier = Modifier.height(16.dp))
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
    var url by remember { mutableStateOf("") }
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
                if (token.isNotBlank()) viewModel.setHaToken(token.trim())
                
                // Save Device Identity & MQTT settings
                viewModel.setDeviceName(editDeviceName.trim())
                viewModel.setMqttEnabled(editMqttEnabled)
                viewModel.setMqttBroker(editMqttBroker.trim())
                val port = editMqttPort.toIntOrNull() ?: 1883
                viewModel.setMqttPort(port)
                viewModel.setMqttUsername(editMqttUsername.trim())
                viewModel.setMqttPassword(editMqttPassword.trim())
                
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

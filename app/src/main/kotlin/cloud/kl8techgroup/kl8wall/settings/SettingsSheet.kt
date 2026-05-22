package cloud.kl8techgroup.kl8wall.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cloud.kl8techgroup.kl8wall.server.HaDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PORT_RANGE = 1024..65535
private const val BEARER_MASK_LENGTH = 12
private const val MIN_PIN_LENGTH = 4
private const val MAX_SETTINGS_PIN_LENGTH = 8
private const val SCAN_TIMEOUT_MS = 8000L

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
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "KL8Wall Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            ConnectionSection(viewModel)
            SettingsDivider()
            ServerSection(viewModel)
            SettingsDivider()
            DisplaySection(viewModel)
            SettingsDivider()
            AllowedHostsSection(viewModel)
            SettingsDivider()
            SecuritySection(viewModel)
            SettingsDivider()
            MqttSection(viewModel)
            SettingsDivider()
            SensorsProxySection(viewModel)
            SettingsDivider()
            DeveloperSection()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}


@Composable
private fun ConnectionSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val startUrl by viewModel.startUrl.collectAsState()
    val haTokenSet by viewModel.haTokenSet.collectAsState()
    var editUrl by remember { mutableStateOf(startUrl) }
    var editToken by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    SectionHeader("Connection")

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
            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save Connection") }
}

@Composable
private fun ServerSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val httpPort by viewModel.httpPort.collectAsState()
    val httpBearerToken by viewModel.httpBearerToken.collectAsState()
    var editPort by remember { mutableStateOf(httpPort.toString()) }
    var showBearerToken by remember { mutableStateOf(false) }

    SectionHeader("HTTP Server")

    OutlinedTextField(
        value = editPort,
        onValueChange = { editPort = it },
        label = { Text("Server Port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = {
            val port = editPort.toIntOrNull()
            if (port != null && port in PORT_RANGE) {
                viewModel.setHttpPort(port)
                Toast.makeText(context, "Port updated to $port", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Invalid port (1024-65535)", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save Port") }

    OutlinedTextField(
        value = if (showBearerToken) httpBearerToken else "•".repeat(BEARER_MASK_LENGTH),
        onValueChange = {},
        label = { Text("API Bearer Token") },
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
                    Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
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

@Composable
private fun AllowedHostsSection(viewModel: SettingsViewModel) {
    val allowedHosts by viewModel.allowedHosts.collectAsState()

    SectionHeader("Allowed Hosts")

    if (allowedHosts.isEmpty()) {
        Text(
            text = "No hosts configured. All navigation allowed.",
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
                Button(onClick = { viewModel.removeAllowedHost(host) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun SecuritySection(viewModel: SettingsViewModel) {
    val isPinSet by viewModel.isPinSet.collectAsState()
    val hotCorner by viewModel.hotCorner.collectAsState()

    SectionHeader("Security")
    HotCornerSelector(hotCorner = hotCorner, onSelected = viewModel::setHotCorner)
    Spacer(modifier = Modifier.height(8.dp))
    PinManagement(
        isPinSet = isPinSet,
        onSetPin = viewModel::setPin,
        onClearPin = viewModel::clearPin
    )
}

@Composable
private fun HotCornerSelector(hotCorner: HotCorner, onSelected: (HotCorner) -> Unit) {
    Text("Settings access corner", style = MaterialTheme.typography.bodyMedium)
    Column {
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
            Text("PIN protection: Enabled", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClearPin) { Text("Remove PIN") }
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

    Text(
        text = "PIN protection: Disabled (optional)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedTextField(
        value = newPin,
        onValueChange = { if (it.length <= MAX_SETTINGS_PIN_LENGTH) newPin = it },
        label = { Text("New PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = confirmPin,
        onValueChange = { if (it.length <= MAX_SETTINGS_PIN_LENGTH) confirmPin = it },
        label = { Text("Confirm PIN") },
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
                    Toast.makeText(context, "PINs don't match", Toast.LENGTH_SHORT).show()
                else -> {
                    onSetPin(newPin)
                    newPin = ""
                    confirmPin = ""
                    Toast.makeText(context, "PIN set", Toast.LENGTH_SHORT).show()
                }
            }
        },
        enabled = newPin.isNotEmpty() && confirmPin.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Set PIN") }
}

@Composable
private fun DisplaySection(viewModel: SettingsViewModel) {
    val mediaGesture by viewModel.mediaPlaybackRequiresGesture.collectAsState()

    SectionHeader("Display")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Require gesture for media", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "When off, camera feeds auto-play (e.g. Frigate)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = mediaGesture,
            onCheckedChange = { viewModel.setMediaPlaybackRequiresGesture(it) }
        )
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
            Text("Scanning...")
        } else {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search Network")
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
                Text(instance.name, style = MaterialTheme.typography.bodyMedium)
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
            text = "No Home Assistant instances found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MqttSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsState()
    val mqttEnabled by viewModel.mqttEnabled.collectAsState()
    val mqttBroker by viewModel.mqttBroker.collectAsState()
    val mqttPort by viewModel.mqttPort.collectAsState()
    val mqttUsername by viewModel.mqttUsername.collectAsState()
    val mqttPassword by viewModel.mqttPassword.collectAsState()

    var editDeviceName by remember(deviceName) { mutableStateOf(deviceName) }
    var editEnabled by remember(mqttEnabled) { mutableStateOf(mqttEnabled) }
    var editBroker by remember(mqttBroker) { mutableStateOf(mqttBroker) }
    var editPort by remember(mqttPort) { mutableStateOf(mqttPort.toString()) }
    var editUsername by remember(mqttUsername) { mutableStateOf(mqttUsername) }
    var editPassword by remember(mqttPassword) { mutableStateOf(mqttPassword) }
    var showPassword by remember { mutableStateOf(false) }

    SectionHeader("MQTT & Device Identity")

    OutlinedTextField(
        value = editDeviceName,
        onValueChange = { editDeviceName = it },
        label = { Text("Device Name") },
        placeholder = { Text("e.g. living_room_wall") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Enable MQTT Client", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Publishes sensors and receives control commands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = editEnabled, onCheckedChange = { editEnabled = it })
    }

    if (editEnabled) {
        OutlinedTextField(
            value = editBroker,
            onValueChange = { editBroker = it },
            label = { Text("MQTT Broker IP/Host") },
            placeholder = { Text("192.168.1.5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = editPort,
            onValueChange = { editPort = it },
            label = { Text("MQTT Port") },
            placeholder = { Text("1883") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = editUsername,
            onValueChange = { editUsername = it },
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = editPassword,
            onValueChange = { editPassword = it },
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TokenVisibilityToggle(showPassword) { showPassword = !showPassword } },
            modifier = Modifier.fillMaxWidth()
        )
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
    ) { Text("Save MQTT Settings") }
}

@Composable
private fun SensorsProxySection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val bluetoothProxyEnabled by viewModel.bluetoothProxyEnabled.collectAsState()
    val presenceSensorEnabled by viewModel.presenceSensorEnabled.collectAsState()
    val presenceTimeoutSeconds by viewModel.presenceTimeoutSeconds.collectAsState()
    val cameraIntervalMinutes by viewModel.cameraIntervalMinutes.collectAsState()

    var editBleProxy by remember(bluetoothProxyEnabled) { mutableStateOf(bluetoothProxyEnabled) }
    var editPresence by remember(presenceSensorEnabled) { mutableStateOf(presenceSensorEnabled) }
    var editTimeout by remember(presenceTimeoutSeconds) { mutableStateOf(presenceTimeoutSeconds.toString()) }
    var editCameraInterval by remember(cameraIntervalMinutes) { mutableStateOf(cameraIntervalMinutes.toString()) }

    SectionHeader("Sensors & Proxy")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("ESPHome Bluetooth Proxy", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Forward BLE advertisements to HA via port 6053",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = editBleProxy, onCheckedChange = { editBleProxy = it })
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Blended Presence Sensor", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Detect presence via light, proximity, and touch sensors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = editPresence, onCheckedChange = { editPresence = it })
    }

    if (editPresence) {
        OutlinedTextField(
            value = editTimeout,
            onValueChange = { editTimeout = it },
            label = { Text("Presence Timeout (seconds)") },
            placeholder = { Text("30") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }

    OutlinedTextField(
        value = editCameraInterval,
        onValueChange = { editCameraInterval = it },
        label = { Text("Camera Capture Interval (minutes)") },
        placeholder = { Text("60 (0 to disable)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = {
            viewModel.setBluetoothProxyEnabled(editBleProxy)
            viewModel.setPresenceSensorEnabled(editPresence)
            val timeout = editTimeout.toIntOrNull() ?: 30
            viewModel.setPresenceTimeoutSeconds(timeout)
            val camInterval = editCameraInterval.toIntOrNull() ?: 60
            viewModel.setCameraIntervalMinutes(camInterval)
            Toast.makeText(context, "Sensors & Proxy settings saved", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save Sensors & Proxy") }
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
            text = "Set up your connection, sensor, and proxy preferences.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        
        // 1. Home Assistant URL
        SectionHeader("Home Assistant Connection")
        
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
        
        SettingsDivider()
        
        // 2. MQTT & Device Identity
        SectionHeader("MQTT & Device Identity")
        
        OutlinedTextField(
            value = editDeviceName,
            onValueChange = { editDeviceName = it },
            label = { Text("Device Name") },
            placeholder = { Text("e.g. living_room_wall") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable MQTT Client", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Publishes sensors and receives control commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = editMqttEnabled, onCheckedChange = { editMqttEnabled = it })
        }

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
        
        SettingsDivider()
        
        // 3. Sensors & Proxy
        SectionHeader("Sensors & Proxy")
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ESPHome Bluetooth Proxy", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Forward BLE advertisements to HA via port 6053",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = editBleProxy, onCheckedChange = { editBleProxy = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Blended Presence Sensor", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Uses light, proximity, touch, and camera face detection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = editPresence, onCheckedChange = { editPresence = it })
        }

        if (editPresence) {
            OutlinedTextField(
                value = editTimeout,
                onValueChange = { editTimeout = it },
                label = { Text("Presence Cooldown (seconds)") },
                placeholder = { Text("e.g. 60") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = editCameraInterval,
            onValueChange = { editCameraInterval = it },
            label = { Text("Periodic Photo Interval (minutes)") },
            placeholder = { Text("60 (0 to disable)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        SetupButtons(
            onConnect = {
                if (url.isBlank()) {
                    Toast.makeText(context, "Enter your HA URL", Toast.LENGTH_SHORT).show()
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
            text = "You can update these settings later by long-pressing the ${hotCorner.name.replace('_', ' ').lowercase()} corner.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
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
        Button(onClick = onSkip, modifier = Modifier.width(120.dp)) { Text("Skip") }
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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
            text = "For support or questions, please visit kl8tech.group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

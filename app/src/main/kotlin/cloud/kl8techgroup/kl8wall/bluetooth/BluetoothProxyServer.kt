package cloud.kl8techgroup.kl8wall.bluetooth

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cloud.kl8techgroup.kl8wall.BuildConfig
import cloud.kl8techgroup.kl8wall.KL8WallApplication
import cloud.kl8techgroup.kl8wall.proto.*
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class BluetoothProxyServer(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "BluetoothProxyServer"
        private const val PORT = 6053

        // Entity Keys
        private const val KEY_SCREEN = 101
        private const val KEY_PRESENCE = 102
        private const val KEY_CAMERA = 103
        
        private const val KEY_BATTERY_LEVEL = 104
        private const val KEY_BATTERY_TEMP = 105
        private const val KEY_WIFI_RSSI = 106
        private const val KEY_RAM_USAGE = 107
        private const val KEY_STORAGE_FREE = 108
        private const val KEY_UPTIME = 109
        
        private const val KEY_BATTERY_STATE = 110
        private const val KEY_WIFI_SSID = 111
        private const val KEY_IP_ADDRESS = 112
        private const val KEY_APP_VERSION = 113
        private const val KEY_CURRENT_URL = 114
        
        private const val KEY_TTS = 115
        
        private const val KEY_SCREEN_TIMEOUT = 116
        private const val KEY_PRESENCE_TIMEOUT = 117
        private const val KEY_TTS_VOLUME = 118
        
        private const val KEY_RELOAD = 119
        private const val KEY_SNAPSHOT = 120
        private const val KEY_SETTINGS = 121
        private const val KEY_REBOOT = 122
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null
    private var jmdns: JmDNS? = null
    private var mdnsServiceInfo: ServiceInfo? = null

    private val activeClients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private var isScanning = false
    
    private var periodicUpdateJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            forwardBleAdvertisement(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { forwardBleAdvertisement(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    fun start() {
        Log.d(TAG, "Starting BluetoothProxyServer on port $PORT...")
        scope.launch {
            settingsRepository.bluetoothProxyEnabled.collectLatest { enabled ->
                if (enabled) {
                    startServer()
                } else {
                    stopServer()
                }
            }
        }
    }

    fun stop() {
        stopServer()
        scope.cancel()
    }

    @Synchronized
    private fun startServer() {
        if (serverSocket != null) return
        try {
            serverSocket = ServerSocket(PORT)
            listenJob = scope.launch {
                while (isActive) {
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            val handler = ClientHandler(socket)
                            activeClients.add(handler)
                            scope.launch { handler.run() }
                        }
                    } catch (e: Exception) {
                        if (isActive) Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
            registerMdns()
            startPeriodicUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    @Synchronized
    private fun stopServer() {
        listenJob?.cancel()
        listenJob = null
        periodicUpdateJob?.cancel()
        periodicUpdateJob = null
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        activeClients.forEach { it.close() }
        activeClients.clear()
        stopBleScan()
        unregisterMdns()
    }

    private fun startPeriodicUpdates() {
        periodicUpdateJob?.cancel()
        periodicUpdateJob = scope.launch {
            while (isActive) {
                delay(10_000)
                try {
                    val app = context as KL8WallApplication
                    val dev = app.deviceController
                    if (dev != null) {
                        broadcastSensorState(KEY_BATTERY_LEVEL, dev.getBatteryLevel())
                        broadcastSensorState(KEY_BATTERY_TEMP, dev.getBatteryTemp())
                        broadcastSensorState(KEY_WIFI_RSSI, dev.getWifiRssi().toFloat())
                        broadcastSensorState(KEY_RAM_USAGE, dev.getRamUsagePercent())
                        broadcastSensorState(KEY_STORAGE_FREE, dev.getStorageFreeGb())
                        broadcastSensorState(KEY_UPTIME, dev.getUptimeSeconds().toFloat())

                        broadcastTextSensorState(KEY_BATTERY_STATE, dev.getBatteryState())
                        broadcastTextSensorState(KEY_WIFI_SSID, dev.getWifiSsid())
                        broadcastTextSensorState(KEY_IP_ADDRESS, dev.getIpAddress())
                        broadcastTextSensorState(KEY_CURRENT_URL, dev.getCurrentUrl())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic updates", e)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerMdns() {
        scope.launch {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ipAddressInt = wifiManager.connectionInfo.ipAddress
                if (ipAddressInt == 0) return@launch
                val ipAddress = InetAddress.getByAddress(
                    byteArrayOf(
                        (ipAddressInt and 0xff).toByte(),
                        (ipAddressInt shr 8 and 0xff).toByte(),
                        (ipAddressInt shr 16 and 0xff).toByte(),
                        (ipAddressInt shr 24 and 0xff).toByte()
                    )
                )
                jmdns = JmDNS.create(ipAddress, "kl8wall-ble")
                val txtRecords = mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "device" to Build.MODEL,
                    "mac" to getBluetoothMacAddress().replace(":", "")
                )
                mdnsServiceInfo = ServiceInfo.create(
                    "_esphomelib._tcp.local.",
                    "KL8Wall-BLE-${settingsRepository.deviceName.value}",
                    PORT,
                    0,
                    0,
                    txtRecords
                )
                jmdns?.registerService(mdnsServiceInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register mDNS", e)
            }
        }
    }

    private fun unregisterMdns() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {}
        jmdns = null
        mdnsServiceInfo = null
    }

    @Synchronized
    private fun startBleScan() {
        if (isScanning) return
        if (!hasBluetoothPermission()) return
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            Log.i(TAG, "Native BLE scanning started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
        }
    }

    @Synchronized
    private fun stopBleScan() {
        if (!isScanning) return
        if (!hasBluetoothPermission()) {
            isScanning = false
            return
        }
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scan", e)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getBluetoothMacAddress(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "kl8walldefault"
        val hash = androidId.hashCode().toLong()
        val macBytes = byteArrayOf(
            0x02,
            ((hash shr 24) and 0xFF.toLong()).toByte(),
            ((hash shr 16) and 0xFF.toLong()).toByte(),
            ((hash shr 8) and 0xFF.toLong()).toByte(),
            (hash and 0xFF.toLong()).toByte(),
            0x01
        )
        return macBytes.joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
    }

    private fun forwardBleAdvertisement(result: ScanResult) {
        val macString = result.device.address.replace(":", "")
        val macLong = try { macString.toLong(16) } catch (e: Exception) { 0L }
        val ad = BluetoothLERawAdvertisement.newBuilder()
            .setAddress(macLong)
            .setRssi(result.rssi)
            .setAddressType(0)
            .setData(com.google.protobuf.ByteString.copyFrom(result.scanRecord?.bytes ?: byteArrayOf()))
            .build()
        val response = BluetoothLERawAdvertisementsResponse.newBuilder().addAdvertisements(ad).build()
        
        scope.launch {
            activeClients.forEach { client ->
                if (client.isSubscribedToBle) client.sendPacket(93, response)
            }
        }
    }

    // --- State Broadcasting ---
    fun broadcastPresenceState(isPresent: Boolean) {
        val resp = BinarySensorStateResponse.newBuilder().setKey(KEY_PRESENCE).setState(isPresent).build()
        scope.launch {
            activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(21, resp) }
        }
    }

    private fun broadcastSensorState(key: Int, value: Float) {
        val resp = SensorStateResponse.newBuilder().setKey(key).setState(value).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(22, resp) }
    }

    private fun broadcastTextSensorState(key: Int, text: String) {
        val resp = TextSensorStateResponse.newBuilder().setKey(key).setState(text).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(23, resp) }
    }

    private fun broadcastNumberState(key: Int, value: Float) {
        val resp = NumberStateResponse.newBuilder().setKey(key).setState(value).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(50, resp) }
    }

    private fun broadcastScreenState() {
        val app = context as KL8WallApplication
        val dev = app.deviceController ?: return
        val resp = LightStateResponse.newBuilder()
            .setKey(KEY_SCREEN)
            .setState(dev.isScreenOn())
            .setBrightness(dev.getBrightness() / 100f)
            .setColorMode(ColorMode.COLOR_MODE_BRIGHTNESS)
            .build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(73, resp) }
    }

    // --- Client Handler ---
    private inner class ClientHandler(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        
        var isSubscribedToBle = false
            private set
        var isSubscribedToStates = false
            private set

        private var running = true

        fun run() {
            try {
                while (running) {
                    val zero = input.read()
                    if (zero == -1) break
                    if (zero != 0x00) break
                    val length = readVarInt(input)
                    val msgId = readVarInt(input)
                    val payload = ByteArray(length)
                    var bytesRead = 0
                    while (bytesRead < length) {
                        val read = input.read(payload, bytesRead, length - bytesRead)
                        if (read == -1) throw EOFException()
                        bytesRead += read
                    }
                    handleMessage(msgId, payload)
                }
            } catch (e: Exception) {
            } finally {
                close()
            }
        }

        private fun handleMessage(msgId: Int, payload: ByteArray) {
            when (msgId) {
                1 -> { // HelloRequest
                    val resp = HelloResponse.newBuilder()
                        .setApiVersionMajor(1)
                        .setApiVersionMinor(10)
                        .setServerInfo("ESPHome v2026.1.0")
                        .setName(settingsRepository.deviceName.value)
                        .build()
                    sendPacket(2, resp)
                }
                3 -> { // AuthenticationRequest
                    sendPacket(4, AuthenticationResponse.newBuilder().setInvalidPassword(false).build())
                }
                5 -> { // DisconnectRequest
                    sendPacket(6, DisconnectResponse.newBuilder().build())
                    running = false
                }
                7 -> { // PingRequest
                    sendPacket(8, PingResponse.newBuilder().build())
                }
                9 -> { // DeviceInfoRequest
                    val resp = DeviceInfoResponse.newBuilder()
                        .setName(settingsRepository.deviceName.value)
                        .setFriendlyName(settingsRepository.deviceName.value)
                        .setMacAddress(getBluetoothMacAddress())
                        .setBluetoothMacAddress(getBluetoothMacAddress())
                        .setEsphomeVersion("2026.1.0")
                        .setModel("KL8Wall Proxy")
                        .setManufacturer("kl8techgroup")
                        .setBluetoothProxyFeatureFlags(15)
                        .build()
                    sendPacket(10, resp)
                }
                11 -> { // ListEntitiesRequest
                    val name = settingsRepository.deviceName.value

                    // Screen
                    sendPacket(15, ListEntitiesLightResponse.newBuilder()
                        .setObjectId("screen").setKey(KEY_SCREEN).setName("$name Screen")
                        .addSupportedColorModes(ColorMode.COLOR_MODE_BRIGHTNESS).build())

                    // Presence
                    sendPacket(12, ListEntitiesBinarySensorResponse.newBuilder()
                        .setObjectId("presence").setKey(KEY_PRESENCE).setName("$name Presence")
                        .setDeviceClass("motion").build())

                    // Camera
                    sendPacket(43, ListEntitiesCameraResponse.newBuilder()
                        .setObjectId("camera").setKey(KEY_CAMERA).setName("$name Camera")
                        .build())

                    // Sensors
                    fun sSens(id: String, key: Int, n: String, u: String, c: String, s: SensorStateClass?) {
                        val b = ListEntitiesSensorResponse.newBuilder().setObjectId(id).setKey(key).setName("$name $n")
                        if (u.isNotEmpty()) b.setUnitOfMeasurement(u)
                        if (c.isNotEmpty()) b.setDeviceClass(c)
                        if (s != null) b.setStateClass(s)
                        sendPacket(16, b.build())
                    }
                    sSens("battery_level", KEY_BATTERY_LEVEL, "Battery Level", "%", "battery", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("battery_temp", KEY_BATTERY_TEMP, "Battery Temp", "°C", "temperature", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("wifi_rssi", KEY_WIFI_RSSI, "WiFi RSSI", "dBm", "signal_strength", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("ram_usage", KEY_RAM_USAGE, "RAM Usage", "%", "", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("storage_free", KEY_STORAGE_FREE, "Storage Free", "GB", "data_size", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("uptime", KEY_UPTIME, "Uptime", "s", "duration", SensorStateClass.STATE_CLASS_TOTAL_INCREASING)

                    // Text Sensors
                    fun sTxtSens(id: String, key: Int, n: String, c: String = "") {
                        val b = ListEntitiesTextSensorResponse.newBuilder().setObjectId(id).setKey(key).setName("$name $n")
                        if (c.isNotEmpty()) b.setDeviceClass(c)
                        sendPacket(18, b.build())
                    }
                    sTxtSens("battery_state", KEY_BATTERY_STATE, "Battery State")
                    sTxtSens("wifi_ssid", KEY_WIFI_SSID, "WiFi SSID")
                    sTxtSens("ip_address", KEY_IP_ADDRESS, "IP Address")
                    sTxtSens("app_version", KEY_APP_VERSION, "App Version")
                    sTxtSens("current_url", KEY_CURRENT_URL, "Current URL")

                    // TTS
                    sendPacket(97, ListEntitiesTextResponse.newBuilder().setObjectId("tts").setKey(KEY_TTS).setName("$name TTS").build())

                    // Numbers
                    fun sNum(id: String, key: Int, n: String, min: Float, max: Float, step: Float, u: String = "") {
                        val b = ListEntitiesNumberResponse.newBuilder().setObjectId(id).setKey(key).setName("$name $n").setMinValue(min).setMaxValue(max).setStep(step)
                        if (u.isNotEmpty()) b.setUnitOfMeasurement(u)
                        sendPacket(49, b.build())
                    }
                    sNum("screen_timeout", KEY_SCREEN_TIMEOUT, "Screen Timeout", 10f, 3600f, 1f, "s")
                    sNum("presence_timeout", KEY_PRESENCE_TIMEOUT, "Presence Timeout", 10f, 3600f, 1f, "s")
                    sNum("tts_volume", KEY_TTS_VOLUME, "TTS Volume", 0f, 100f, 1f, "%")

                    // Buttons
                    fun sBtn(id: String, key: Int, n: String, c: String = "") {
                        val b = ListEntitiesButtonResponse.newBuilder().setObjectId(id).setKey(key).setName("$name $n")
                        if (c.isNotEmpty()) b.setDeviceClass(c)
                        sendPacket(61, b.build())
                    }
                    sBtn("reload", KEY_RELOAD, "Reload Dashboard", "restart")
                    sBtn("snapshot", KEY_SNAPSHOT, "Take Snapshot")
                    sBtn("settings", KEY_SETTINGS, "Open Settings")
                    sBtn("reboot", KEY_REBOOT, "Reboot App", "restart")

                    sendPacket(19, ListEntitiesDoneResponse.newBuilder().build())
                }
                20 -> { // SubscribeStatesRequest
                    isSubscribedToStates = true
                    val app = context as KL8WallApplication
                    val dev = app.deviceController
                    if (dev != null) {
                        broadcastScreenState()
                        sendPacket(21, BinarySensorStateResponse.newBuilder().setKey(KEY_PRESENCE).setState(app.presenceSensorManager?.isPresent ?: false).build())
                        sendPacket(22, SensorStateResponse.newBuilder().setKey(KEY_BATTERY_LEVEL).setState(dev.getBatteryLevel()).build())
                        sendPacket(22, SensorStateResponse.newBuilder().setKey(KEY_BATTERY_TEMP).setState(dev.getBatteryTemp()).build())
                        sendPacket(22, SensorStateResponse.newBuilder().setKey(KEY_WIFI_RSSI).setState(dev.getWifiRssi().toFloat()).build())
                        sendPacket(22, SensorStateResponse.newBuilder().setKey(KEY_RAM_USAGE).setState(dev.getRamUsagePercent()).build())
                        sendPacket(22, SensorStateResponse.newBuilder().setKey(KEY_STORAGE_FREE).setState(dev.getStorageFreeGb()).build())
                        sendPacket(22, SensorStateResponse.newBuilder().setKey(KEY_UPTIME).setState(dev.getUptimeSeconds().toFloat()).build())
                        
                        sendPacket(23, TextSensorStateResponse.newBuilder().setKey(KEY_BATTERY_STATE).setState(dev.getBatteryState()).build())
                        sendPacket(23, TextSensorStateResponse.newBuilder().setKey(KEY_WIFI_SSID).setState(dev.getWifiSsid()).build())
                        sendPacket(23, TextSensorStateResponse.newBuilder().setKey(KEY_IP_ADDRESS).setState(dev.getIpAddress()).build())
                        sendPacket(23, TextSensorStateResponse.newBuilder().setKey(KEY_APP_VERSION).setState(dev.getAppVersion()).build())
                        sendPacket(23, TextSensorStateResponse.newBuilder().setKey(KEY_CURRENT_URL).setState(dev.getCurrentUrl()).build())
                        
                        sendPacket(98, TextStateResponse.newBuilder().setKey(KEY_TTS).setState("").build())
                        
                        sendPacket(50, NumberStateResponse.newBuilder().setKey(KEY_SCREEN_TIMEOUT).setState(dev.getScreenTimeoutSeconds().toFloat()).build())
                        sendPacket(50, NumberStateResponse.newBuilder().setKey(KEY_PRESENCE_TIMEOUT).setState(dev.getScreenTimeoutSeconds().toFloat()).build())
                        sendPacket(50, NumberStateResponse.newBuilder().setKey(KEY_TTS_VOLUME).setState(dev.getTtsVolume().toFloat()).build())
                    }
                }
                32 -> { // LightCommandRequest
                    val req = LightCommandRequest.parseFrom(payload)
                    val dev = (context as KL8WallApplication).deviceController
                    if (dev != null && req.key == KEY_SCREEN) {
                        if (req.hasState) {
                            if (req.state) dev.screenOn() else dev.screenOff()
                        }
                        if (req.hasBrightness) {
                            dev.setBrightness((req.brightness * 100f).toInt())
                        }
                        scope.launch {
                            delay(200)
                            broadcastScreenState()
                        }
                    }
                }
                62 -> { // ButtonCommandRequest
                    val req = ButtonCommandRequest.parseFrom(payload)
                    val app = context as KL8WallApplication
                    val dev = app.deviceController
                    if (dev != null) {
                        when (req.key) {
                            KEY_RELOAD -> dev.reload()
                            KEY_SETTINGS -> dev.openSettings()
                            KEY_REBOOT -> dev.rebootApp()
                            KEY_SNAPSHOT -> app.cameraManager?.takeSnapshot()
                        }
                    }
                }
                51 -> { // NumberCommandRequest
                    val req = NumberCommandRequest.parseFrom(payload)
                    val dev = (context as KL8WallApplication).deviceController
                    if (dev != null) {
                        when (req.key) {
                            KEY_SCREEN_TIMEOUT, KEY_PRESENCE_TIMEOUT -> {
                                dev.setScreenTimeoutSeconds(req.state.toInt())
                                broadcastNumberState(KEY_SCREEN_TIMEOUT, req.state)
                                broadcastNumberState(KEY_PRESENCE_TIMEOUT, req.state)
                            }
                            KEY_TTS_VOLUME -> {
                                dev.setTtsVolume(req.state.toInt())
                                broadcastNumberState(KEY_TTS_VOLUME, req.state)
                            }
                        }
                    }
                }
                99 -> { // TextCommandRequest
                    val req = TextCommandRequest.parseFrom(payload)
                    val dev = (context as KL8WallApplication).deviceController
                    if (dev != null && req.key == KEY_TTS) {
                        dev.speak(req.state)
                    }
                }
                45 -> { // CameraImageRequest
                    val req = CameraImageRequest.parseFrom(payload)
                    val app = context as KL8WallApplication
                    val jpeg = app.cameraManager?.latestPhotoBytes
                    if (jpeg != null) {
                        val resp = CameraImageResponse.newBuilder()
                            .setKey(KEY_CAMERA)
                            .setData(com.google.protobuf.ByteString.copyFrom(jpeg))
                            .setDone(true)
                            .build()
                        sendPacket(44, resp)
                    }
                }
                66 -> { // SubscribeBluetoothLEAdvertisementsRequest
                    isSubscribedToBle = true
                    startBleScan()
                    val resp = BluetoothScannerStateResponse.newBuilder()
                        .setMode(BluetoothScannerMode.BLUETOOTH_SCANNER_MODE_PASSIVE)
                        .setState(BluetoothScannerState.BLUETOOTH_SCANNER_STATE_RUNNING)
                        .build()
                    sendPacket(126, resp)
                }
                80 -> { // SubscribeBluetoothConnectionsFreeRequest
                    val resp = BluetoothConnectionsFreeResponse.newBuilder().setFree(3).setLimit(3).build()
                    sendPacket(81, resp)
                }
                87 -> { // UnsubscribeBluetoothLEAdvertisementsRequest
                    isSubscribedToBle = false
                    checkBleScanningNeeds()
                }
                127 -> { // BluetoothScannerSetModeRequest
                    val req = BluetoothScannerSetModeRequest.parseFrom(payload)
                    isSubscribedToBle = true
                    startBleScan()
                    val resp = BluetoothScannerStateResponse.newBuilder()
                        .setMode(req.mode)
                        .setState(BluetoothScannerState.BLUETOOTH_SCANNER_STATE_RUNNING)
                        .build()
                    sendPacket(126, resp)
                }
            }
        }

        fun sendPacket(msgId: Int, message: com.google.protobuf.MessageLite) {
            synchronized(output) {
                try {
                    val bytes = message.toByteArray()
                    output.write(0x00)
                    writeVarInt(output, bytes.size)
                    writeVarInt(output, msgId)
                    output.write(bytes)
                    output.flush()
                } catch (e: Exception) {
                    close()
                }
            }
        }

        fun close() {
            running = false
            try { socket.close() } catch (e: Exception) {}
            activeClients.remove(this)
            checkBleScanningNeeds()
        }
    }

    @Synchronized
    private fun checkBleScanningNeeds() {
        val anySubscribed = activeClients.any { it.isSubscribedToBle }
        if (!anySubscribed) {
            stopBleScan()
        }
    }

    private fun readVarInt(input: InputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException()
            value = value or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return value
    }

    private fun writeVarInt(output: OutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                output.write(v)
                break
            } else {
                output.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }
}

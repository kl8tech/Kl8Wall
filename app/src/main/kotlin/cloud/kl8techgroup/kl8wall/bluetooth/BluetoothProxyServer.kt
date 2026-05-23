package cloud.kl8techgroup.kl8wall.bluetooth

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
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
import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.Collections
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

        private const val KEY_APP_FOREGROUND = 123
        private const val KEY_BLE_DEVICES_COUNT = 124
        private const val KEY_BLE_DEVICES_LIST = 125
        private const val KEY_CAMERA_STREAMING = 126
        private const val KEY_AMBIENT_LIGHT = 127
        private const val KEY_PROXIMITY = 128
        private const val KEY_PRESSURE = 129
        private const val KEY_AMBIENT_TEMP = 130
        private const val KEY_HUMIDITY = 131
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null
    private var jmdns: JmDNS? = null
    private var mdnsServiceInfo: ServiceInfo? = null

    private val activeClients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private var isScanning = false
    
    private data class NearbyDevice(
        val mac: String,
        val name: String,
        val rssi: Int,
        val timestamp: Long
    )
    private val nearbyDevices = ConcurrentHashMap<String, NearbyDevice>()
    
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
    fun restart() {
        Log.i(TAG, "Restarting BluetoothProxyServer...")
        stopServer()
        if (settingsRepository.bluetoothProxyEnabled.value) {
            startServer()
        }
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
                            Log.i(TAG, "Accepted incoming connection from ${socket.remoteSocketAddress}")
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
            if (hasBluetoothPermission()) {
                startBleScan()
            }
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
                    if (!isScanning && hasBluetoothPermission()) {
                        startBleScan()
                    }
                    val app = context as KL8WallApplication
                    val dev = app.deviceController
                    if (dev != null) {
                        broadcastSensorState(KEY_BATTERY_LEVEL, dev.getBatteryLevel())
                        broadcastSensorState(KEY_BATTERY_TEMP, dev.getBatteryTemp())
                        broadcastSensorState(KEY_WIFI_RSSI, dev.getWifiRssi().toFloat())
                        broadcastSensorState(KEY_RAM_USAGE, dev.getRamUsagePercent())
                        broadcastSensorState(KEY_STORAGE_FREE, dev.getStorageFreeGb())
                        broadcastSensorState(KEY_UPTIME, dev.getUptimeSeconds().toFloat())

                        // New sensors
                        broadcastSensorState(KEY_AMBIENT_LIGHT, dev.getAmbientLight())
                        broadcastSensorState(KEY_PROXIMITY, dev.getProximity())
                        broadcastSensorState(KEY_PRESSURE, dev.getPressure())
                        broadcastSensorState(KEY_AMBIENT_TEMP, dev.getAmbientTemp())
                        broadcastSensorState(KEY_HUMIDITY, dev.getHumidity())

                        broadcastTextSensorState(KEY_BATTERY_STATE, dev.getBatteryState())
                        broadcastTextSensorState(KEY_WIFI_SSID, dev.getWifiSsid())
                        broadcastTextSensorState(KEY_IP_ADDRESS, dev.getIpAddress())
                        broadcastTextSensorState(KEY_CURRENT_URL, dev.getCurrentUrl())

                        val inForeground = app.isAppInForeground
                        broadcastSwitchState(KEY_APP_FOREGROUND, inForeground)

                        val isStreaming = app.cameraManager?.isStreamingEnabled ?: false
                        broadcastSwitchState(KEY_CAMERA_STREAMING, isStreaming)

                        val bleCount = getNearbyDevicesCount()
                        broadcastSensorState(KEY_BLE_DEVICES_COUNT, bleCount.toFloat())

                        val bleList = getNearbyDevicesList()
                        broadcastTextSensorState(KEY_BLE_DEVICES_LIST, bleList)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic updates", e)
                }
            }
        }
    }

    private fun getLocalIpAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in Collections.list(interfaces)) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return null
    }

    private fun registerMdns() {
        scope.launch {
            var delayMs = 2000L
            while (isActive) {
                try {
                    val ipAddress = getLocalIpAddress()
                    if (ipAddress == null) {
                        Log.w(TAG, "Cannot register mDNS: IP address is null, retrying in ${delayMs / 1000}s")
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(30000L)
                        continue
                    }
                    Log.i(TAG, "Registering mDNS for ESPHome proxy on IP: $ipAddress")
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
                    Log.i(TAG, "mDNS service registered successfully: KL8Wall-BLE-${settingsRepository.deviceName.value}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register mDNS, retrying in ${delayMs / 1000}s", e)
                    try {
                        jmdns?.close()
                    } catch (_: Exception) {}
                    jmdns = null
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(30000L)
                }
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
        
        // Track the nearby device
        val macStr = result.device.address
        val name = result.device.name ?: "Unknown"
        val rssi = result.rssi
        nearbyDevices[macStr] = NearbyDevice(macStr, name, rssi, System.currentTimeMillis())

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

    fun getNearbyDevicesCount(): Int {
        pruneExpiredDevices()
        return nearbyDevices.size
    }

    fun getNearbyDevicesList(): String {
        pruneExpiredDevices()
        if (nearbyDevices.isEmpty()) return "[]"
        val jsonArray = org.json.JSONArray()
        nearbyDevices.values.forEach { dev ->
            val jsonObject = org.json.JSONObject().apply {
                put("mac", dev.mac)
                put("name", dev.name)
                put("rssi", dev.rssi)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private fun pruneExpiredDevices() {
        val now = System.currentTimeMillis()
        val it = nearbyDevices.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.timestamp > 60000) { // 60 seconds expiry
                it.remove()
            }
        }
    }

    fun broadcastPresenceState(isPresent: Boolean) {
        val resp = BinarySensorStateResponse.newBuilder().setKey(KEY_PRESENCE).setState(isPresent).build()
        scope.launch {
            activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(21, resp) }
        }
    }

    fun publishAppForegroundState(inForeground: Boolean) {
        val resp = SwitchStateResponse.newBuilder().setKey(KEY_APP_FOREGROUND).setState(inForeground).build()
        scope.launch {
            activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(26, resp) }
        }
    }

    private fun broadcastSensorState(key: Int, value: Float) {
        val resp = SensorStateResponse.newBuilder().setKey(key).setState(value).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(25, resp) }
    }

    private fun broadcastTextSensorState(key: Int, text: String) {
        val resp = TextSensorStateResponse.newBuilder().setKey(key).setState(text).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(27, resp) }
    }

    private fun broadcastNumberState(key: Int, value: Float) {
        val resp = NumberStateResponse.newBuilder().setKey(key).setState(value).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(50, resp) }
    }

    private fun broadcastSwitchState(key: Int, state: Boolean) {
        val resp = SwitchStateResponse.newBuilder().setKey(key).setState(state).build()
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(26, resp) }
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
        activeClients.forEach { if (it.isSubscribedToStates) it.sendPacket(24, resp) }
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
                Log.i(TAG, "ClientHandler starting for ${socket.remoteSocketAddress}")
                while (running) {
                    val zero = input.read()
                    if (zero == -1) {
                        Log.d(TAG, "Client closed connection: ${socket.remoteSocketAddress}")
                        break
                    }
                    if (zero != 0x00) {
                        Log.w(TAG, "Invalid frame preamble: $zero from ${socket.remoteSocketAddress}")
                        break
                    }
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
                Log.e(TAG, "Exception in ClientHandler for ${socket.remoteSocketAddress}", e)
            } finally {
                Log.i(TAG, "ClientHandler stopped for ${socket.remoteSocketAddress}")
                close()
            }
        }

        private fun handleMessage(msgId: Int, payload: ByteArray) {
            Log.d(TAG, "Received message: msgId=$msgId, size=${payload.size} from ${socket.remoteSocketAddress}")
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
                        .setBluetoothProxyFeatureFlags(127)
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

                    sSens("ambient_light", KEY_AMBIENT_LIGHT, "Ambient Light", "lx", "illuminance", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("proximity", KEY_PROXIMITY, "Proximity", "cm", "", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("pressure", KEY_PRESSURE, "Pressure", "hPa", "pressure", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("ambient_temp", KEY_AMBIENT_TEMP, "Ambient Temperature", "°C", "temperature", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("humidity", KEY_HUMIDITY, "Humidity", "%", "humidity", SensorStateClass.STATE_CLASS_MEASUREMENT)
                    sSens("bluetooth_devices_count", KEY_BLE_DEVICES_COUNT, "Bluetooth Devices Count", "", "", SensorStateClass.STATE_CLASS_MEASUREMENT)

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
                    sTxtSens("bluetooth_devices_list", KEY_BLE_DEVICES_LIST, "Bluetooth Devices List")

                    // Switches
                    fun sSwitch(id: String, key: Int, n: String) {
                        val b = ListEntitiesSwitchResponse.newBuilder().setObjectId(id).setKey(key).setName("$name $n")
                        sendPacket(17, b.build())
                    }
                    sSwitch("app_foreground", KEY_APP_FOREGROUND, "App Foreground")
                    sSwitch("camera_streaming", KEY_CAMERA_STREAMING, "Camera Streaming")

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
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_BATTERY_LEVEL).setState(dev.getBatteryLevel()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_BATTERY_TEMP).setState(dev.getBatteryTemp()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_WIFI_RSSI).setState(dev.getWifiRssi().toFloat()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_RAM_USAGE).setState(dev.getRamUsagePercent()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_STORAGE_FREE).setState(dev.getStorageFreeGb()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_UPTIME).setState(dev.getUptimeSeconds().toFloat()).build())

                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_AMBIENT_LIGHT).setState(dev.getAmbientLight()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_PROXIMITY).setState(dev.getProximity()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_PRESSURE).setState(dev.getPressure()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_AMBIENT_TEMP).setState(dev.getAmbientTemp()).build())
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_HUMIDITY).setState(dev.getHumidity()).build())
                        
                        sendPacket(27, TextSensorStateResponse.newBuilder().setKey(KEY_BATTERY_STATE).setState(dev.getBatteryState()).build())
                        sendPacket(27, TextSensorStateResponse.newBuilder().setKey(KEY_WIFI_SSID).setState(dev.getWifiSsid()).build())
                        sendPacket(27, TextSensorStateResponse.newBuilder().setKey(KEY_IP_ADDRESS).setState(dev.getIpAddress()).build())
                        sendPacket(27, TextSensorStateResponse.newBuilder().setKey(KEY_APP_VERSION).setState(dev.getAppVersion()).build())
                        sendPacket(27, TextSensorStateResponse.newBuilder().setKey(KEY_CURRENT_URL).setState(dev.getCurrentUrl()).build())

                        val bleList = getNearbyDevicesList()
                        sendPacket(27, TextSensorStateResponse.newBuilder().setKey(KEY_BLE_DEVICES_LIST).setState(bleList).build())

                        val bleCount = getNearbyDevicesCount()
                        sendPacket(25, SensorStateResponse.newBuilder().setKey(KEY_BLE_DEVICES_COUNT).setState(bleCount.toFloat()).build())

                        val inForeground = app.isAppInForeground
                        sendPacket(26, SwitchStateResponse.newBuilder().setKey(KEY_APP_FOREGROUND).setState(inForeground).build())

                        val isStreaming = app.cameraManager?.isStreamingEnabled ?: false
                        sendPacket(26, SwitchStateResponse.newBuilder().setKey(KEY_CAMERA_STREAMING).setState(isStreaming).build())
                        
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
                33 -> { // SwitchCommandRequest
                    val req = SwitchCommandRequest.parseFrom(payload)
                    val app = context as KL8WallApplication
                    val dev = app.deviceController
                    if (dev != null) {
                        when (req.key) {
                            KEY_APP_FOREGROUND -> {
                                if (req.state) {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    }
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                        broadcastSwitchState(KEY_APP_FOREGROUND, true)
                                    }
                                } else {
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(homeIntent)
                                    broadcastSwitchState(KEY_APP_FOREGROUND, false)
                                }
                            }
                            KEY_CAMERA_STREAMING -> {
                                app.cameraManager?.isStreamingEnabled = req.state
                                broadcastSwitchState(KEY_CAMERA_STREAMING, req.state)
                            }
                        }
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
            Log.d(TAG, "Sending message: msgId=$msgId, type=${message.javaClass.simpleName} to ${socket.remoteSocketAddress}")
            synchronized(output) {
                try {
                    val bytes = message.toByteArray()
                    output.write(0x00)
                    writeVarInt(output, bytes.size)
                    writeVarInt(output, msgId)
                    output.write(bytes)
                    output.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending packet msgId=$msgId to ${socket.remoteSocketAddress}", e)
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
        // Always scan by default to keep nearby devices tracking active
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

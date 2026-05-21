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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null
    private var jmdns: JmDNS? = null
    private var mdnsServiceInfo: ServiceInfo? = null

    private val activeClients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private var isScanning = false
    private val scanCallback = object : ScanCallback() {
        private var adCount = 0

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            adCount++
            if (adCount % 100 == 1) {
                Log.d(TAG, "BLE scan result received: MAC=${result.device.address} RSSI=${result.rssi} (total=$adCount)")
            }
            forwardBleAdvertisement(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                adCount++
                if (adCount % 100 == 1) {
                    Log.d(TAG, "BLE batch scan result: MAC=${result.device.address} RSSI=${result.rssi} (total=$adCount)")
                }
                forwardBleAdvertisement(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
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
        Log.d(TAG, "Stopping BluetoothProxyServer...")
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
                            Log.i(TAG, "New client connected from ${socket.remoteSocketAddress}")
                            val handler = ClientHandler(socket)
                            activeClients.add(handler)
                            scope.launch { handler.run() }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
            }
            registerMdns()
            Log.i(TAG, "BluetoothProxyServer running on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ServerSocket on port $PORT", e)
        }
    }

    @Synchronized
    private fun stopServer() {
        // Stop listen job
        listenJob?.cancel()
        listenJob = null

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        // Stop all active clients
        activeClients.forEach { it.close() }
        activeClients.clear()

        // Stop scanning
        stopBleScan()

        // Unregister mDNS
        unregisterMdns()
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
                Log.i(TAG, "Registered mDNS service _esphomelib._tcp.local on port $PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register mDNS service for Bluetooth Proxy", e)
            }
        }
    }

    private fun unregisterMdns() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing mDNS JmDNS", e)
        }
        jmdns = null
        mdnsServiceInfo = null
    }

    @Synchronized
    private fun startBleScan() {
        if (isScanning) return

        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Missing Bluetooth permissions to start scan")
            return
        }

        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "BluetoothLeScanner not available (Bluetooth might be off)")
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            Log.i(TAG, "Native BLE scanning started for proxy")
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
            val adapter = bluetoothManager.adapter
            val scanner = adapter?.bluetoothLeScanner
            scanner?.stopScan(scanCallback)
            isScanning = false
            Log.i(TAG, "Native BLE scanning stopped")
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
        val device = result.device
        val rssi = result.rssi
        val scanRecord = result.scanRecord?.bytes ?: byteArrayOf()
        
        // Parse MAC to Long
        val macString = device.address.replace(":", "")
        val macLong = try {
            macString.toLong(16)
        } catch (e: Exception) {
            0L
        }

        // ESPHome expects AddressType (Public=0, Random=1)
        val addressType = 0 // Default public

        val ad = BluetoothLERawAdvertisement.newBuilder()
            .setAddress(macLong)
            .setRssi(rssi)
            .setAddressType(addressType)
            .setData(com.google.protobuf.ByteString.copyFrom(scanRecord))
            .build()

        val response = BluetoothLERawAdvertisementsResponse.newBuilder()
            .addAdvertisements(ad)
            .build()

        // Distribute to all clients subscribed to advertisements
        scope.launch {
            activeClients.forEach { client ->
                if (client.isSubscribedToBle) {
                    client.sendPacket(93, response)
                }
            }
        }
    }

    /**
     * Handles the ESPHome Native API connection lifecycle on a single client socket.
     */
    private inner class ClientHandler(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        
        var isSubscribedToBle = false
            private set

        private var running = true

        fun run() {
            try {
                while (running) {
                    // Packet framing: Zero-byte, Length (VarInt), MsgId (VarInt), Payload
                    val zero = input.read()
                    if (zero == -1) break
                    if (zero != 0x00) {
                        Log.w(TAG, "Protocol violation: packet did not start with zero byte: $zero")
                        break
                    }

                    val length = readVarInt(input)
                    val msgId = readVarInt(input)
                    
                    val payload = ByteArray(length)
                    var bytesRead = 0
                    while (bytesRead < length) {
                        val read = input.read(payload, bytesRead, length - bytesRead)
                        if (read == -1) throw EOFException("Truncated protobuf payload")
                        bytesRead += read
                    }

                    handleMessage(msgId, payload)
                }
            } catch (e: EOFException) {
                Log.d(TAG, "Client closed connection: ${socket.remoteSocketAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception handling client socket", e)
            } finally {
                close()
            }
        }

        private fun handleMessage(msgId: Int, payload: ByteArray) {
            when (msgId) {
                1 -> { // HelloRequest
                    val req = HelloRequest.parseFrom(payload)
                    Log.i(TAG, "HelloRequest: client='${req.clientInfo}' version=${req.apiVersionMajor}.${req.apiVersionMinor}")
                    
                    val resp = HelloResponse.newBuilder()
                        .setApiVersionMajor(1)
                        .setApiVersionMinor(10)
                        .setServerInfo("ESPHome v2026.1.0")
                        .setName(settingsRepository.deviceName.value)
                        .build()
                    sendPacket(2, resp)
                }
                3 -> { // AuthenticationRequest
                    val req = AuthenticationRequest.parseFrom(payload)
                    Log.i(TAG, "AuthenticationRequest received")
                    
                    val resp = AuthenticationResponse.newBuilder()
                        .setInvalidPassword(false)
                        .build()
                    sendPacket(4, resp)
                }
                5 -> { // DisconnectRequest
                    Log.i(TAG, "DisconnectRequest received")
                    val resp = DisconnectResponse.newBuilder().build()
                    sendPacket(6, resp)
                    running = false
                }
                7 -> { // PingRequest
                    val resp = PingResponse.newBuilder().build()
                    sendPacket(8, resp)
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
                        .setBluetoothProxyFeatureFlags(15) // Flag 15 = Active + Passive + Connectable + Raw Ads proxy capabilities
                        .build()
                    sendPacket(10, resp)
                }
                11 -> { // ListEntitiesRequest
                    // We don't advertise custom native entities, just done.
                    val resp = ListEntitiesDoneResponse.newBuilder().build()
                    sendPacket(19, resp)
                }
                20 -> { // SubscribeStatesRequest
                    // No states to subscribe to
                }
                66 -> { // SubscribeBluetoothLEAdvertisementsRequest
                    Log.i(TAG, "Client subscribed to Bluetooth LE Advertisements")
                    isSubscribedToBle = true
                    startBleScan()
                }
                80 -> { // SubscribeBluetoothConnectionsFreeRequest
                    Log.i(TAG, "Client subscribed to Bluetooth Connections Free status")
                    val resp = BluetoothConnectionsFreeResponse.newBuilder()
                        .setFree(3)
                        .setLimit(3)
                        .build()
                    sendPacket(81, resp)
                }
                87 -> { // UnsubscribeBluetoothLEAdvertisementsRequest
                    Log.i(TAG, "Client unsubscribed from Bluetooth LE Advertisements")
                    isSubscribedToBle = false
                    checkBleScanningNeeds()
                }
                else -> {
                    Log.d(TAG, "Unhandled message ID: $msgId")
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
                    Log.e(TAG, "Error sending packet $msgId", e)
                    close()
                }
            }
        }

        fun close() {
            running = false
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
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
            if (b == -1) throw EOFException("EOF while reading VarInt")
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

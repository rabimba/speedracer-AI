package com.trustableai.koru.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.bridgeValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import java.util.UUID

object BluetoothRuntimePermissions {
    fun hasBluetoothPermissions(context: Context): Boolean {
        return hasBluetoothScan(context) && hasBluetoothConnect(context)
    }

    fun hasBluetoothScan(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasBluetoothConnect(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

class RaceBoxBleClient(context: Context) : RaceBoxDataClient {
    private val tag = "RaceBoxBleClient"
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val parser = RaceBoxProtocolParser()
    private var scope: CoroutineScope? = null
    private var scanTimeoutJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null

    @Volatile private var started = false
    @Volatile private var latest: RaceBoxSample? = null
    @Volatile private var status = RaceBoxClientStatus(
        connected = false,
        scanning = false,
        detail = "RaceBox idle",
    )

    override suspend fun start() {
        if (started) return
        started = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startScan()
    }

    override suspend fun stop() {
        started = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        stopScan()
        closeGatt()
        scope?.cancel()
        scope = null
        status = RaceBoxClientStatus(connected = false, scanning = false, detail = "RaceBox stopped")
    }

    override fun latestSample(): RaceBoxSample? = latest

    override fun status(): RaceBoxClientStatus = status

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!BluetoothRuntimePermissions.hasBluetoothPermissions(appContext)) {
            status = RaceBoxClientStatus(false, false, "Bluetooth scan/connect permission missing")
            return
        }
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            status = RaceBoxClientStatus(false, false, "Bluetooth adapter unavailable or disabled")
            return
        }

        status = RaceBoxClientStatus(connected = false, scanning = true, detail = "Scanning for RaceBox Mini")
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status = RaceBoxClientStatus(false, false, "BLE scanner unavailable")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!started || !isRaceBox(result)) return
                status = RaceBoxClientStatus(false, false, "RaceBox found; connecting")
                scanTimeoutJob?.cancel()
                scanTimeoutJob = null
                stopScan()
                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                status = RaceBoxClientStatus(false, false, "RaceBox scan failed: $errorCode")
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList(), settings, callback)
        scanTimeoutJob = scope?.launch {
            delay(SCAN_TIMEOUT_MS)
            if (status.scanning) {
                stopScan()
                status = RaceBoxClientStatus(false, false, "RaceBox scan timed out")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun isRaceBox(result: ScanResult): Boolean {
        val advertisedName = result.scanRecord?.deviceName.orEmpty()
        val deviceName = runCatching { result.device.name.orEmpty() }.getOrDefault("")
        val name = advertisedName.ifBlank { deviceName }
        val advertisedServices = result.scanRecord?.serviceUuids.orEmpty().map(ParcelUuid::getUuid)
        return RACEBOX_NAME_PREFIXES.any { prefix -> name.startsWith(prefix) } ||
            UART_SERVICE_UUID in advertisedServices
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!BluetoothRuntimePermissions.hasBluetoothConnect(appContext)) {
            status = RaceBoxClientStatus(false, false, "Bluetooth connect permission missing")
            return
        }
        closeGatt()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        status = RaceBoxClientStatus(true, false, "RaceBox connected; discovering UART")
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(247)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        status = RaceBoxClientStatus(false, false, "RaceBox disconnected")
                        runCatching { gatt.close() }
                        if (started) {
                            scope?.launch {
                                delay(RECONNECT_DELAY_MS)
                                startScan()
                            }
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, statusCode: Int) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(tag, "RaceBox MTU set to $mtu")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status = RaceBoxClientStatus(true, false, "RaceBox service discovery failed: $statusCode")
                    return
                }
                val tx = gatt.getService(UART_SERVICE_UUID)?.getCharacteristic(UART_TX_CHARACTERISTIC_UUID)
                if (tx == null) {
                    status = RaceBoxClientStatus(true, false, "RaceBox UART TX characteristic missing")
                    return
                }
                val enabled = gatt.setCharacteristicNotification(tx, true)
                val descriptor = tx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (!enabled || descriptor == null) {
                    status = RaceBoxClientStatus(true, false, "RaceBox notification setup failed")
                    return
                }
                writeNotificationDescriptor(gatt, descriptor)
                status = RaceBoxClientStatus(true, false, "RaceBox notifications active")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleNotification(value)
            }

            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                handleNotification(characteristic.value ?: return)
            }
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeNotificationDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleNotification(value: ByteArray) {
        val receivedAt = SystemClock.elapsedRealtime()
        parser.append(value, receivedAt).lastOrNull()?.let { sample ->
            latest = sample
            status = RaceBoxClientStatus(
                connected = true,
                scanning = false,
                detail = if (sample.fixOk) {
                    "RaceBox live ${sample.satellites} SV"
                } else {
                    "RaceBox live, waiting for 3D fix"
                },
            )
        }
    }

    companion object {
        val UART_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val UART_TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val RACEBOX_NAME_PREFIXES = listOf("RaceBox Mini", "RaceBox Mini S", "RaceBox Micro")
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 2_000L
    }
}

interface Elm327Transport {
    val kind: ObdTransportKind

    suspend fun open()

    suspend fun close()

    suspend fun send(command: String, timeoutMs: Long = ELM_COMMAND_TIMEOUT_MS): String
}

class Elm327TransportUnavailable(message: String) : Exception(message)

class BluetoothRfcommElmTransport(context: Context) : Elm327Transport {
    private val appContext = context.applicationContext
    private var socket: BluetoothSocket? = null

    override val kind: ObdTransportKind = ObdTransportKind.BLUETOOTH

    @SuppressLint("MissingPermission")
    override suspend fun open() {
        withContext(Dispatchers.IO) {
            if (!BluetoothRuntimePermissions.hasBluetoothConnect(appContext)) {
                throw Elm327TransportUnavailable("Bluetooth connect permission missing")
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                throw Elm327TransportUnavailable("Bluetooth adapter unavailable or disabled")
            }
            val device = adapter.bondedDevices.firstOrNull { bonded ->
                bonded.name?.contains("OBDLink", ignoreCase = true) == true
            } ?: throw Elm327TransportUnavailable("Pair OBDLink MX+ in Android Bluetooth settings first")

            adapter.cancelDiscovery()
            val connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = connectedSocket
            connectedSocket.connect()
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
    }

    override suspend fun send(command: String, timeoutMs: Long): String {
        return withContext(Dispatchers.IO) {
            val connectedSocket = socket ?: throw Elm327TransportUnavailable("Bluetooth socket is not open")
            connectedSocket.outputStream.write((command.trim().uppercase(Locale.US) + "\r").toByteArray(Charsets.US_ASCII))
            connectedSocket.outputStream.flush()
            readUntilPrompt(connectedSocket.inputStream)
        }
    }

    private fun readUntilPrompt(input: InputStream): String {
        val response = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            val char = byte.toChar()
            response.append(char)
            if (char == '>') break
        }
        return response.toString()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}

class UsbSerialElmTransport(context: Context) : Elm327Transport {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    override val kind: ObdTransportKind = ObdTransportKind.USB

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { usbDriver ->
                usbDriver.device.vendorId == FTDI_VENDOR_ID ||
                    usbDriver.device.productName?.contains("OBDLink", ignoreCase = true) == true ||
                    usbDriver.device.manufacturerName?.contains("OBDLink", ignoreCase = true) == true
            } ?: drivers.firstOrNull()
                ?: throw Elm327TransportUnavailable("No USB serial OBDLink EX adapter attached")

            val device = driver.device
            if (!usbManager.hasPermission(device)) {
                requestUsbPermission(device.deviceName)
                throw Elm327TransportUnavailable("USB permission requested for OBDLink EX")
            }

            val connection = usbManager.openDevice(device)
                ?: throw Elm327TransportUnavailable("Unable to open USB serial device")
            val serialPort = driver.ports.firstOrNull()
                ?: throw Elm327TransportUnavailable("USB serial device has no ports")
            serialPort.open(connection)
            serialPort.setParameters(
                USB_BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            runCatching { serialPort.setDTR(true) }
            runCatching { serialPort.setRTS(true) }
            port = serialPort
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { port?.close() }
            port = null
        }
    }

    override suspend fun send(command: String, timeoutMs: Long): String {
        return withContext(Dispatchers.IO) {
            val serialPort = port ?: throw Elm327TransportUnavailable("USB serial port is not open")
            serialPort.write((command.trim().uppercase(Locale.US) + "\r").toByteArray(Charsets.US_ASCII), timeoutMs.toInt())
            readUntilPrompt(serialPort, timeoutMs)
        }
    }

    private fun requestUsbPermission(deviceName: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val intent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags,
        )
        usbManager.deviceList[deviceName]?.let { device ->
            usbManager.requestPermission(device, intent)
        }
    }

    private fun readUntilPrompt(serialPort: UsbSerialPort, timeoutMs: Long): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buffer = ByteArray(256)
        val response = StringBuilder()
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()
            val read = serialPort.read(buffer, remaining.coerceAtMost(100))
            if (read <= 0) continue
            val chunk = String(buffer, 0, read, Charsets.US_ASCII)
            response.append(chunk)
            if ('>' in chunk) break
        }
        return response.toString()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.trustableai.koru.USB_PERMISSION"
        private const val FTDI_VENDOR_ID = 0x0403
        private const val USB_BAUD_RATE = 115_200
    }
}

open class ObdLinkElm327Client internal constructor(
    private val transportPreference: ObdTransportPreference = ObdTransportPreference.AUTO,
    private val transportFactory: (ObdTransportPreference) -> List<Elm327Transport>,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : ObdDataClient {
    constructor(
        context: Context,
        transportPreference: ObdTransportPreference = ObdTransportPreference.AUTO,
    ) : this(
        transportPreference = transportPreference,
        transportFactory = { preference ->
            val appContext = context.applicationContext
            when (preference) {
                ObdTransportPreference.AUTO -> listOf(
                    UsbSerialElmTransport(appContext),
                    BluetoothRfcommElmTransport(appContext),
                )
                ObdTransportPreference.USB -> listOf(UsbSerialElmTransport(appContext))
                ObdTransportPreference.BLUETOOTH -> listOf(BluetoothRfcommElmTransport(appContext))
            }
        },
    )

    private var scope: CoroutineScope? = null
    private var activeTransport: Elm327Transport? = null
    private var reconnectCount = 0
    private var supportedPids: Set<Int> = emptySet()

    @Volatile private var latest: ObdSample? = null
    @Volatile private var status = ObdClientStatus(connected = false, detail = "OBDLink idle")

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { clientScope ->
            clientScope.launch { connectionLoop() }
        }
    }

    override suspend fun stop() {
        scope?.cancel()
        scope = null
        closeTransport()
        status = ObdClientStatus(connected = false, detail = "OBDLink stopped")
    }

    override fun latestSample(): ObdSample? = latest

    override fun status(): ObdClientStatus = status

    private suspend fun connectionLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                status = ObdClientStatus(
                    connected = false,
                    detail = "Connecting to OBDLink (${transportPreference.bridgeValue()})",
                    reconnectCount = reconnectCount,
                )
                val transport = openPreferredTransport()
                activeTransport = transport
                status = ObdClientStatus(
                    connected = true,
                    detail = "OBDLink ${transport.kind.bridgeValue} connected; initializing read-only polling",
                    transport = transport.kind,
                    reconnectCount = reconnectCount,
                )
                val pollPlan = initialize(transport)
                poll(transport, pollPlan)
            } catch (error: Exception) {
                reconnectCount += 1
                val transport = activeTransport
                status = ObdClientStatus(
                    connected = false,
                    detail = "OBDLink error: ${error.message ?: error.javaClass.simpleName}",
                    transport = transport?.kind,
                    supportedPids = supportedPids,
                    reconnectCount = reconnectCount,
                )
                closeTransport()
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun openPreferredTransport(): Elm327Transport {
        var lastError: Exception? = null
        for (transport in transportFactory(transportPreference)) {
            try {
                transport.open()
                return transport
            } catch (error: Exception) {
                lastError = error
                transport.close()
            }
        }
        throw lastError ?: Elm327TransportUnavailable("No OBD transport available")
    }

    private suspend fun initialize(transport: Elm327Transport): ObdPollPlan {
        // Adapter-only setup plus read-only Mode 01 queries; never clear DTCs or send vehicle writes.
        val commands = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATSP0",
        )
        commands.forEachIndexed { index, command ->
            transport.send(command, timeoutMs = if (command == "ATZ") RESET_COMMAND_TIMEOUT_MS else ELM_COMMAND_TIMEOUT_MS)
            if (index == 0) delay(RESET_SETTLE_MS)
        }
        supportedPids = resolveSupportedMode01Pids(transport)
        val hotPids = HOT_OBD_PIDS.filter { pid -> pid.mode01Pid in supportedPids }
        val diagnosticPids = DIAGNOSTIC_OBD_PIDS.filter { pid -> pid.mode01Pid in supportedPids }
        status = ObdClientStatus(
            connected = true,
            detail = "OBDLink ${transport.kind.bridgeValue} ready with ${supportedPids.size} supported Mode 01 PIDs",
            transport = transport.kind,
            supportedPids = supportedPids,
            reconnectCount = reconnectCount,
        )
        return ObdPollPlan(hotPids = hotPids, diagnosticPids = diagnosticPids)
    }

    private suspend fun resolveSupportedMode01Pids(transport: Elm327Transport): Set<Int> {
        val supported = mutableSetOf<Int>()
        val supported00 = ObdLinkProtocol.parseSupportedMode01Pids(
            response = transport.send("0100"),
            supportPid = 0x00,
        )
        supported += supported00
        if (0x20 in supported00) {
            val supported20 = ObdLinkProtocol.parseSupportedMode01Pids(
                response = transport.send("0120"),
                supportPid = 0x20,
            )
            supported += supported20
            if (0x40 in supported20) {
                supported += ObdLinkProtocol.parseSupportedMode01Pids(
                    response = transport.send("0140"),
                    supportPid = 0x40,
                )
            }
        }
        return supported
    }

    private suspend fun poll(transport: Elm327Transport, pollPlan: ObdPollPlan) {
        var sample = ObdSample(receivedAtElapsedMs = elapsedRealtimeMs())
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            val pollPids = pollPlan.hotPids + if (cycle % DIAGNOSTIC_POLL_EVERY == 0) {
                pollPlan.diagnosticPids
            } else {
                emptyList()
            }
            if (pollPids.isEmpty()) {
                latest = sample
                status = ObdClientStatus(
                    connected = true,
                    detail = "OBDLink ${transport.kind.bridgeValue} connected; no supported polling PIDs reported",
                    transport = transport.kind,
                    supportedPids = supportedPids,
                    reconnectCount = reconnectCount,
                )
                delay(NO_SUPPORTED_PID_DELAY_MS)
                continue
            }
            for (pid in pollPids) {
                val now = elapsedRealtimeMs()
                val response = transport.send(pid.request)
                sample = ObdLinkProtocol.applyReading(sample, pid, response, now)
                latest = sample
                delay(INTER_COMMAND_DELAY_MS)
            }
            status = ObdClientStatus(
                connected = true,
                detail = "OBDLink ${transport.kind.bridgeValue} live ${sample.statusText()}",
                transport = transport.kind,
                supportedPids = supportedPids,
                reconnectCount = reconnectCount,
            )
            cycle += 1
        }
    }

    private suspend fun closeTransport() {
        runCatching { activeTransport?.close() }
        activeTransport = null
    }

    private fun ObdSample.statusText(): String {
        val parts = buildList {
            rpm?.let { add("${it}rpm") }
            throttlePercent?.let { add("${"%.0f".format(Locale.US, it)}% throttle") }
            speedMph?.let { add("${"%.1f".format(Locale.US, it)}mph OBD") }
        }
        return parts.joinToString(", ").ifBlank { "waiting for PIDs" }
    }

    private data class ObdPollPlan(
        val hotPids: List<ObdPid>,
        val diagnosticPids: List<ObdPid>,
    )

    companion object {
        private const val RESET_SETTLE_MS = 800L
        private const val INTER_COMMAND_DELAY_MS = 25L
        private const val NO_SUPPORTED_PID_DELAY_MS = 1_000L
        private const val DIAGNOSTIC_POLL_EVERY = 5
        private const val RECONNECT_DELAY_MS = 2_000L
    }
}

class ObdLinkClassicClient(context: Context) : ObdLinkElm327Client(
    context = context,
    transportPreference = ObdTransportPreference.BLUETOOTH,
)

private const val ELM_COMMAND_TIMEOUT_MS = 1_000L
private const val RESET_COMMAND_TIMEOUT_MS = 2_000L

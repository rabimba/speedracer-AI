package com.trustableai.koru.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

class AimCanUsbClient(
    context: Context,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : AimCanDataClient {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val parser = AimCanSlcanParser()
    private val recentFrameTimesMs = mutableMapOf<Int, MutableList<Long>>()
    private var scope: CoroutineScope? = null
    private var activePort: UsbSerialPort? = null
    private var reconnectCount = 0
    private var frameRatesHz: Map<Int, Double> = emptyMap()

    @Volatile private var latest: AimCanSample? = null
    @Volatile private var status = AimCanClientStatus(connected = false, detail = "AiM CAN USB idle")

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { clientScope ->
            clientScope.launch { connectionLoop() }
        }
    }

    override suspend fun stop() {
        scope?.cancel()
        scope = null
        closePort()
        status = AimCanClientStatus(connected = false, detail = "AiM CAN USB stopped")
    }

    override fun latestSample(): AimCanSample? = latest

    override fun status(): AimCanClientStatus = status

    private suspend fun connectionLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                status = AimCanClientStatus(
                    connected = false,
                    detail = "Connecting to RH-02 PRO / CANable USB serial",
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                val opened = openPort()
                activePort = opened.port
                status = AimCanClientStatus(
                    connected = true,
                    detail = "AiM CAN USB connected; waiting for SLCAN frames",
                    usbDeviceName = opened.deviceName,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                initializeSlcan(opened.port)
                readLoop(opened.port, opened.deviceName)
            } catch (error: Exception) {
                reconnectCount += 1
                status = AimCanClientStatus(
                    connected = false,
                    detail = "AiM CAN USB error: ${error.message ?: error.javaClass.simpleName}",
                    usbDeviceName = status.usbDeviceName,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                closePort()
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun openPort(): OpenedCanPort {
        return withContext(Dispatchers.IO) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = selectCanDriver(drivers)
                ?: throw UsbCanUnavailable("No RH-02 PRO / CANable USB serial adapter attached")
            val device = driver.device
            if (!usbManager.hasPermission(device)) {
                requestUsbPermission(device)
                throw UsbCanUnavailable("USB permission requested for ${device.label()}")
            }
            val connection = usbManager.openDevice(device)
                ?: throw UsbCanUnavailable("Unable to open ${device.label()}")
            val port = driver.ports.firstOrNull()
                ?: throw UsbCanUnavailable("${device.label()} has no serial ports")
            port.open(connection)
            port.setParameters(
                USB_BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
            OpenedCanPort(port = port, deviceName = device.label())
        }
    }

    private fun selectCanDriver(drivers: List<UsbSerialDriver>): UsbSerialDriver? {
        val nonObdDrivers = drivers.filterNot { driver -> driver.device.isObdLinkDevice() }
        return nonObdDrivers.firstOrNull { driver -> driver.device.isLikelyCanDevice() }
            ?: nonObdDrivers.singleOrNull()
            ?: drivers.firstOrNull { driver -> driver.device.isLikelyCanDevice() }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val intent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags,
        )
        usbManager.requestPermission(device, intent)
    }

    private fun initializeSlcan(port: UsbSerialPort) {
        runCatching {
            port.write("\rC\rS8\rO\r".toByteArray(Charsets.US_ASCII), USB_WRITE_TIMEOUT_MS)
        }
    }

    private suspend fun readLoop(port: UsbSerialPort, deviceName: String) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(512)
            var sample = latest
            while (currentCoroutineContext().isActive) {
                val read = port.read(buffer, USB_READ_TIMEOUT_MS)
                if (read <= 0) continue
                val now = elapsedRealtimeMs()
                val frames = parser.append(buffer.copyOf(read), now)
                frames.forEach { frame ->
                    updateFrameRate(frame.id, frame.receivedAtElapsedMs)
                    sample = AimCanDecoder.applyFrame(
                        previous = sample?.copy(frameRatesHz = frameRatesHz),
                        frame = frame,
                        decodeErrors = parser.decodeErrors,
                    ).copy(frameRatesHz = frameRatesHz)
                    latest = sample
                }
                status = AimCanClientStatus(
                    connected = true,
                    detail = sample?.statusText() ?: "AiM CAN USB connected; waiting for recognized AiM CAN2 frames",
                    usbDeviceName = deviceName,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
            }
        }
    }

    private fun updateFrameRate(frameId: Int, nowElapsedMs: Long) {
        val samples = recentFrameTimesMs.getOrPut(frameId) { mutableListOf() }
        samples += nowElapsedMs
        val cutoff = nowElapsedMs - FRAME_RATE_WINDOW_MS
        samples.removeAll { time -> time < cutoff }
        frameRatesHz = recentFrameTimesMs.mapValues { (_, times) ->
            if (times.size < 2) {
                0.0
            } else {
                val spanSeconds = max(1L, times.last() - times.first()) / 1000.0
                (times.size - 1) / spanSeconds
            }
        }
    }

    private suspend fun closePort() {
        withContext(Dispatchers.IO) {
            runCatching { activePort?.close() }
            activePort = null
        }
    }

    private fun AimCanSample.statusText(): String {
        val parts = buildList {
            rpm?.let { add("${it}rpm") }
            gpsSpeedMph?.let { add("${"%.1f".format(Locale.US, it)}mph GPS") }
            pedalPositionPercent?.let { add("${"%.1f".format(Locale.US, it)}% pedal") }
            brakePressurePsi?.let { add("${"%.1f".format(Locale.US, it)}psi brake") }
            batteryVoltage?.let { add("${"%.1f".format(Locale.US, it)}V") }
        }
        return "AiM CAN USB live ${parts.joinToString(", ").ifBlank { "recognized frames" }}"
    }

    private fun UsbDevice.label(): String {
        val manufacturer = runCatching { manufacturerName.orEmpty() }.getOrDefault("")
        val product = runCatching { productName.orEmpty() }.getOrDefault("")
        val named = listOf(manufacturer, product).filter { it.isNotBlank() }.joinToString(" ")
        return named.ifBlank { "USB ${"%04X".format(Locale.US, vendorId)}:${"%04X".format(Locale.US, productId)}" }
    }

    private fun UsbDevice.isObdLinkDevice(): Boolean {
        val text = label().lowercase(Locale.US)
        return vendorId == FTDI_VENDOR_ID || "obdlink" in text
    }

    private fun UsbDevice.isLikelyCanDevice(): Boolean {
        val text = label().lowercase(Locale.US)
        if (CAN_DEVICE_HINTS.any { hint -> hint in text }) return true
        for (index in 0 until interfaceCount) {
            val iface = getInterface(index)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
            ) {
                return true
            }
        }
        return false
    }

    private data class OpenedCanPort(
        val port: UsbSerialPort,
        val deviceName: String,
    )

    private class UsbCanUnavailable(message: String) : Exception(message)

    private companion object {
        private const val ACTION_USB_PERMISSION = "com.trustableai.koru.CAN_USB_PERMISSION"
        private const val USB_BAUD_RATE = 115_200
        private const val USB_READ_TIMEOUT_MS = 100
        private const val USB_WRITE_TIMEOUT_MS = 500
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val FRAME_RATE_WINDOW_MS = 2_000L
        private const val FTDI_VENDOR_ID = 0x0403
        private val CAN_DEVICE_HINTS = listOf(
            "canable",
            "can",
            "slcan",
            "rh-02",
            "rh02",
            "jhoinrch",
            "stm",
            "candlelight",
            "gs-usb",
        )
    }
}

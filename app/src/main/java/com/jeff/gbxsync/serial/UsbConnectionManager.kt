package com.jeff.gbxsync.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.jeff.gbxsync.protocol.FirmwareInfo
import com.jeff.gbxsync.protocol.GbxDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionState {
    object NoDevice : ConnectionState()
    object PermissionPending : ConnectionState()
    data class Connected(val firmware: FirmwareInfo) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

/**
 * Finds the GBxCart (CH340, VID 0x1A86 / PID 0x7523), handles the USB
 * permission dance, opens the port at 1 Mbaud and hands out a GbxDevice.
 * All blocking calls must run on Dispatchers.IO.
 */
class UsbConnectionManager(private val context: Context) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoDevice)
    val state: StateFlow<ConnectionState> = _state

    var device: GbxDevice? = null
        private set
    private var transport: UsbGbxTransport? = null

    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findGbxCart(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VID_CH340 && it.productId == PID_CH340
        }

    fun hasPermission(usbDevice: UsbDevice): Boolean = usbManager.hasPermission(usbDevice)

    /**
     * Requests USB permission; the result arrives via a one-shot broadcast
     * and triggers [onResult].
     */
    fun requestPermission(usbDevice: UsbDevice, onResult: (Boolean) -> Unit) {
        _state.value = ConnectionState.PermissionPending
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (!granted) _state.value = ConnectionState.Failed("USB permission denied")
                onResult(granted)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(usbDevice, pi)
    }

    /** Opens the port and runs the firmware handshake. Blocking. */
    fun connect(usbDevice: UsbDevice): FirmwareInfo {
        disconnect()
        val driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice)
            ?: throw IllegalStateException("No serial driver for device")
        val connection = usbManager.openDevice(usbDevice)
            ?: throw IllegalStateException("Couldn't open USB device (permission?)")
        val port: UsbSerialPort = driver.ports[0]
        port.open(connection)
        port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        val t = UsbGbxTransport(port)
        val d = GbxDevice(t)
        try {
            val fw = d.connect()
            transport = t
            device = d
            _state.value = ConnectionState.Connected(fw)
            return fw
        } catch (e: Exception) {
            t.close()
            _state.value = ConnectionState.Failed(e.message ?: "Connection failed")
            throw e
        }
    }

    fun disconnect() {
        try {
            transport?.close()
        } finally {
            transport = null
            device = null
            if (_state.value !is ConnectionState.Failed) {
                _state.value = ConnectionState.NoDevice
            }
        }
    }

    fun notifyDetached() {
        transport = null
        device = null
        _state.value = ConnectionState.NoDevice
    }

    companion object {
        const val VID_CH340 = 0x1A86
        const val PID_CH340 = 0x7523
        const val BAUD_RATE = 1_000_000
    }
}

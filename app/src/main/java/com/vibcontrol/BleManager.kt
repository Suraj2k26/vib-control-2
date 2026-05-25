package com.vibcontrol

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID    = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 12_000L
    }

    sealed class State {
        object Disconnected : State()
        object Scanning     : State()
        data class Connected(val deviceName: String) : State()
    }

    enum class LogLevel { INFO, OK, ERROR }

    var onState: ((State) -> Unit)? = null
    var onLog:   ((String, LogLevel) -> Unit)? = null

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var timeoutRunnable: Runnable? = null

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected — discovering services…", LogLevel.INFO)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeChar = null
                    this@BleManager.gatt?.close()
                    this@BleManager.gatt = null
                    postState(State.Disconnected)
                    log("Device disconnected", LogLevel.ERROR)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed ($status)", LogLevel.ERROR)
                postState(State.Disconnected); return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) { log("Service not found", LogLevel.ERROR); postState(State.Disconnected); return }
            writeChar = service.getCharacteristic(CHAR_UUID)
            if (writeChar == null) { log("Characteristic not found", LogLevel.ERROR); postState(State.Disconnected); return }
            val name = gatt.device?.name ?: "VibMotor-C3"
            postState(State.Connected(name))
            log("Ready — connected to $name", LogLevel.OK)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Write error ($status)", LogLevel.ERROR)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { log("Bluetooth unavailable", LogLevel.ERROR); return }
        postState(State.Scanning)
        log("Scanning for VibMotor-C3…", LogLevel.INFO)

        val filters = listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                cancelTimeout(); scanner.stopScan(this); scanCallback = null
                log("Found: ${result.device.name ?: result.device.address}", LogLevel.INFO)
                connectDevice(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                log("Scan failed ($errorCode)", LogLevel.ERROR)
                postState(State.Disconnected); scanCallback = null
            }
        }
        scanCallback = cb
        scanner.startScan(filters, settings, cb)
        val timeout = Runnable {
            if (scanCallback != null) { scanner.stopScan(cb); scanCallback = null; postState(State.Disconnected); log("Scan timed out", LogLevel.ERROR) }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        cancelTimeout(); gatt?.disconnect(); gatt?.close(); gatt = null; writeChar = null; postState(State.Disconnected)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBytes(bytes: ByteArray) {
        val char = writeChar ?: run { log("Not connected", LogLevel.ERROR); return }
        val g = gatt ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION") char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") char.value = bytes
            @Suppress("DEPRECATION") g.writeCharacteristic(char)
        }
        val hex = bytes.joinToString(" ") { "0x${it.toUByte().toString(16).padStart(2,'0').uppercase()}" }
        log("Sent [$hex]", LogLevel.OK)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectDevice(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun cancelTimeout() { timeoutRunnable?.let { mainHandler.removeCallbacks(it) }; timeoutRunnable = null }
    private fun postState(s: State) = mainHandler.post { onState?.invoke(s) }
    private fun log(msg: String, level: LogLevel) = mainHandler.post { onLog?.invoke(msg, level) }
}

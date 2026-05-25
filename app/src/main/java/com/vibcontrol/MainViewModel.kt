package com.vibcontrol

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

// ── Screen enum ──────────────────────────────────────────────
enum class Screen { HOME, PATTERNS, MOTORS, SCHEDULE, CUSTOM }

// ── UI State ─────────────────────────────────────────────────
data class UiState(
    val screen: Screen                   = Screen.HOME,
    val bleState: BleManager.State       = BleManager.State.Disconnected,
    // Home
    val selectedPresetId: String         = "single",
    val intensity: Int                   = 80,
    val selectedMotorIndex: Int          = 0,
    // Custom builder
    val customSeq: List<Boolean>         = listOf(true, false, true, false),
    val customStepMs: Int                = 60,
    val customName: String               = "",
    // Motors
    val motorPins: List<Int>             = listOf(2),
    // Saved patterns
    val savedPatterns: List<SavedPattern>= emptyList(),
    // Schedules
    val schedules: List<Schedule>        = emptyList(),
    // Log
    val logs: List<LogEntry>             = emptyList()
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BleManager(app)
    private val storage = StorageManager(app)

    private val _state = MutableStateFlow(UiState(
        motorPins    = storage.loadMotorPins(),
        savedPatterns = storage.loadPatterns(),
        schedules    = storage.loadSchedules()
    ))
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ── Schedule alarm receiver ──────────────────────────────
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val patternId = intent.getStringExtra("patternId") ?: return
            val pattern = _state.value.savedPatterns.find { it.id == patternId } ?: return
            sendSavedPattern(pattern)
            log("Schedule fired: ${pattern.name}", BleManager.LogLevel.OK)
        }
    }

    init {
        bleManager.onState = { s -> _state.value = _state.value.copy(bleState = s) }
        bleManager.onLog   = { msg, lvl ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = LogEntry(time, msg, lvl)
            _state.value = _state.value.copy(logs = listOf(entry) + _state.value.logs.take(49))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(alarmReceiver, IntentFilter("com.vibcontrol.SCHEDULE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(alarmReceiver, IntentFilter("com.vibcontrol.SCHEDULE"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { getApplication<Application>().unregisterReceiver(alarmReceiver) } catch (_: Exception) {}
    }

    // ── Navigation ───────────────────────────────────────────
    fun navigate(screen: Screen) { _state.value = _state.value.copy(screen = screen) }

    // ── Home ─────────────────────────────────────────────────
    fun selectPreset(id: String)     { _state.value = _state.value.copy(selectedPresetId = id) }
    fun setIntensity(v: Int)         { _state.value = _state.value.copy(intensity = v) }
    fun selectMotor(index: Int)      { _state.value = _state.value.copy(selectedMotorIndex = index) }

    fun sendPreset() {
        val p = PRESET_PATTERNS.find { it.id == _state.value.selectedPresetId } ?: return
        val motorPin = _state.value.motorPins.getOrElse(_state.value.selectedMotorIndex) { 2 }
        val intensity = _state.value.intensity.toByte()
        // CMD | INTENSITY | MOTOR_PIN
        bleManager.sendBytes(byteArrayOf(p.cmdByte, intensity, motorPin.toByte()))
    }

    // ── Custom builder ───────────────────────────────────────
    fun toggleCustomStep(i: Int) {
        val seq = _state.value.customSeq.toMutableList()
        seq[i] = !seq[i]
        _state.value = _state.value.copy(customSeq = seq)
    }
    fun addCustomStep(on: Boolean) {
        if (_state.value.customSeq.size < 32) {
            _state.value = _state.value.copy(customSeq = _state.value.customSeq + on)
        }
    }
    fun removeLastStep() {
        val seq = _state.value.customSeq
        if (seq.size > 1) _state.value = _state.value.copy(customSeq = seq.dropLast(1))
    }
    fun clearCustomSeq()             { _state.value = _state.value.copy(customSeq = listOf(true, false, true, false)) }
    fun setCustomStepMs(ms: Int)     { _state.value = _state.value.copy(customStepMs = ms) }
    fun setCustomName(name: String)  { _state.value = _state.value.copy(customName = name) }

    fun sendCustom() {
        val seq = _state.value.customSeq
        val maskBytes = ByteArray((seq.size + 7) / 8)
        seq.forEachIndexed { i, on -> if (on) maskBytes[i/8] = (maskBytes[i/8].toInt() or (1 shl (i%8))).toByte() }
        val motorPin = _state.value.motorPins.getOrElse(_state.value.selectedMotorIndex) { 2 }
        val ms = _state.value.customStepMs.toByte()
        val intensity = _state.value.intensity.toByte()
        val payload = byteArrayOf(0xF0.toByte(), seq.size.toByte(), ms) + maskBytes + byteArrayOf(intensity, motorPin.toByte())
        bleManager.sendBytes(payload)
    }

    fun saveCurrentCustom() {
        val name = _state.value.customName.ifBlank { "Pattern ${_state.value.savedPatterns.size + 1}" }
        val pattern = SavedPattern(
            name = name,
            steps = _state.value.customSeq,
            stepMs = _state.value.customStepMs,
            intensity = _state.value.intensity,
            motorIndex = _state.value.selectedMotorIndex
        )
        val updated = _state.value.savedPatterns + pattern
        _state.value = _state.value.copy(savedPatterns = updated, customName = "")
        storage.savePatterns(updated)
        log("Saved pattern: $name", BleManager.LogLevel.OK)
    }

    // ── Saved patterns ───────────────────────────────────────
    fun sendSavedPattern(pattern: SavedPattern) {
        val seq = pattern.steps
        val maskBytes = ByteArray((seq.size + 7) / 8)
        seq.forEachIndexed { i, on -> if (on) maskBytes[i/8] = (maskBytes[i/8].toInt() or (1 shl (i%8))).toByte() }
        val motorPin = _state.value.motorPins.getOrElse(pattern.motorIndex) { 2 }
        val payload = byteArrayOf(0xF0.toByte(), seq.size.toByte(), pattern.stepMs.toByte()) +
                maskBytes + byteArrayOf(pattern.intensity.toByte(), motorPin.toByte())
        bleManager.sendBytes(payload)
        log("Sent: ${pattern.name}", BleManager.LogLevel.OK)
    }

    fun deletePattern(id: String) {
        val updated = _state.value.savedPatterns.filter { it.id != id }
        _state.value = _state.value.copy(savedPatterns = updated)
        storage.savePatterns(updated)
    }

    // ── Motor pins ───────────────────────────────────────────
    fun addMotor(gpio: Int) {
        if (_state.value.motorPins.size >= 4) return
        val updated = _state.value.motorPins + gpio
        _state.value = _state.value.copy(motorPins = updated)
        storage.saveMotorPins(updated)
        sendMotorConfig(updated)
    }

    fun removeMotor(index: Int) {
        if (_state.value.motorPins.size <= 1) return
        val updated = _state.value.motorPins.toMutableList().also { it.removeAt(index) }
        _state.value = _state.value.copy(motorPins = updated, selectedMotorIndex = 0)
        storage.saveMotorPins(updated)
        sendMotorConfig(updated)
    }

    fun updateMotorPin(index: Int, gpio: Int) {
        val updated = _state.value.motorPins.toMutableList().also { it[index] = gpio }
        _state.value = _state.value.copy(motorPins = updated)
        storage.saveMotorPins(updated)
        sendMotorConfig(updated)
    }

    private fun sendMotorConfig(pins: List<Int>) {
        // CMD 0xFE = set motor pins config
        val payload = byteArrayOf(0xFE.toByte(), pins.size.toByte()) + pins.map { it.toByte() }.toByteArray()
        bleManager.sendBytes(payload)
    }

    // ── Schedules ────────────────────────────────────────────
    fun addSchedule(patternId: String, patternName: String, hour: Int, minute: Int) {
        val schedule = Schedule(patternId = patternId, patternName = patternName, hour = hour, minute = minute)
        val updated = _state.value.schedules + schedule
        _state.value = _state.value.copy(schedules = updated)
        storage.saveSchedules(updated)
        scheduleAlarm(schedule)
        log("Scheduled ${patternName} at ${hour.toString().padStart(2,'0')}:${minute.toString().padStart(2,'0')}", BleManager.LogLevel.OK)
    }

    fun deleteSchedule(id: String) {
        val schedule = _state.value.schedules.find { it.id == id }
        schedule?.let { cancelAlarm(it) }
        val updated = _state.value.schedules.filter { it.id != id }
        _state.value = _state.value.copy(schedules = updated)
        storage.saveSchedules(updated)
    }

    fun toggleSchedule(id: String) {
        val updated = _state.value.schedules.map {
            if (it.id == id) {
                val toggled = it.copy(enabled = !it.enabled)
                if (toggled.enabled) scheduleAlarm(toggled) else cancelAlarm(toggled)
                toggled
            } else it
        }
        _state.value = _state.value.copy(schedules = updated)
        storage.saveSchedules(updated)
    }

    private fun scheduleAlarm(schedule: Schedule) {
        val app = getApplication<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent("com.vibcontrol.SCHEDULE").apply { putExtra("patternId", schedule.patternId) }
        val pi = android.app.PendingIntent.getBroadcast(
            app, schedule.id.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, schedule.hour)
            set(Calendar.MINUTE, schedule.minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    private fun cancelAlarm(schedule: Schedule) {
        val app = getApplication<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent("com.vibcontrol.SCHEDULE")
        val pi = android.app.PendingIntent.getBroadcast(
            app, schedule.id.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
    }

    private fun log(msg: String, level: BleManager.LogLevel) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry(time, msg, level)
        _state.value = _state.value.copy(logs = listOf(entry) + _state.value.logs.take(49))
    }
}

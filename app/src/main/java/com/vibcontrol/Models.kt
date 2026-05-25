package com.vibcontrol

// ── Motor pin config ─────────────────────────────────────────
data class MotorPin(val gpio: Int, val label: String)

val AVAILABLE_PINS = listOf(
    MotorPin(0,  "GPIO0"),  MotorPin(1,  "GPIO1"),  MotorPin(2,  "GPIO2"),
    MotorPin(3,  "GPIO3"),  MotorPin(4,  "GPIO4"),  MotorPin(5,  "GPIO5"),
    MotorPin(6,  "GPIO6"),  MotorPin(7,  "GPIO7"),  MotorPin(8,  "GPIO8"),
    MotorPin(9,  "GPIO9"),  MotorPin(10, "GPIO10"), MotorPin(20, "GPIO20"),
    MotorPin(21, "GPIO21")
)

// ── Saved pattern ────────────────────────────────────────────
data class SavedPattern(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val steps: List<Boolean>,         // true=on, false=off
    val stepMs: Int = 60,             // duration of each step in ms
    val intensity: Int = 80,          // 1-100
    val motorIndex: Int = 0           // which motor (0-based)
)

// ── Schedule ─────────────────────────────────────────────────
data class Schedule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val patternId: String,
    val patternName: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true
)

// ── Log entry ────────────────────────────────────────────────
data class LogEntry(
    val time: String,
    val message: String,
    val level: BleManager.LogLevel
)

// ── Preset patterns ──────────────────────────────────────────
data class PresetPattern(
    val id: String,
    val name: String,
    val desc: String,
    val cmdByte: Byte,
    val vizHeights: List<Int>
)

val PRESET_PATTERNS = listOf(
    PresetPattern("single",     "Single pulse",  "One short buzz",     0x01, listOf(0,8,0,0,0,0,0,0)),
    PresetPattern("double",     "Double tap",    "Two quick buzzes",   0x02, listOf(0,7,0,4,0,0,0,0)),
    PresetPattern("sos",        "SOS",           "··· — — — ···",      0x03, listOf(2,2,2,6,6,6,2,2)),
    PresetPattern("heartbeat",  "Heartbeat",     "Lub-dub rhythm",     0x04, listOf(0,8,5,0,0,8,5,0)),
    PresetPattern("escalate",   "Escalate",      "Ramps up intensity", 0x05, listOf(1,2,3,4,5,6,7,8)),
    PresetPattern("continuous", "Continuous",    "1 second hold",      0x06, listOf(7,7,7,7,7,7,7,7)),
)

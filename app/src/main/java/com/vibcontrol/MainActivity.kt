package com.vibcontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ── Colors ────────────────────────────────────────────────────
private val Teal500  = Color(0xFF1D9E75)
private val Teal600  = Color(0xFF0F6E56)
private val Teal50   = Color(0xFFE1F5EE)
private val Teal100  = Color(0xFF9FE1CB)
private val Red400   = Color(0xFFE24B4A)
private val Amber400 = Color(0xFFBA7517)
private val Gray50   = Color(0xFFF8F9FA)
private val Gray200  = Color(0xFFE9ECEF)
private val Gray500  = Color(0xFF6C757D)

private val VibTheme = lightColorScheme(
    primary            = Teal500,
    onPrimary          = Color.White,
    primaryContainer   = Teal50,
    onPrimaryContainer = Teal600,
    surface            = Color.White,
    onSurface          = Color(0xFF1C1B1F),
    surfaceVariant     = Gray50,
    onSurfaceVariant   = Gray500,
    outline            = Gray200,
    background         = Color(0xFFF5F7F6),
    onBackground       = Color(0xFF1C1B1F),
)

// ── Activity ──────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) vm.bleManager.startScan()
        else vm.bleManager.onLog?.invoke("Bluetooth permissions denied", BleManager.LogLevel.ERROR)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = VibTheme) {
                val state by vm.state.collectAsStateWithLifecycle()
                AppShell(state = state, vm = vm,
                    onConnect = { connectFlow() },
                    onDisconnect = { vm.bleManager.disconnect() }
                )
            }
        }
    }

    private fun connectFlow() {
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        permLauncher.launch(perms)
    }
}

// ── App Shell ─────────────────────────────────────────────────
@Composable
fun AppShell(state: UiState, vm: MainViewModel, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomNav(current = state.screen, onNav = vm::navigate) }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (state.screen) {
                Screen.HOME     -> HomeScreen(state, vm, onConnect, onDisconnect)
                Screen.PATTERNS -> PatternsScreen(state, vm)
                Screen.MOTORS   -> MotorsScreen(state, vm)
                Screen.SCHEDULE -> ScheduleScreen(state, vm)
                Screen.CUSTOM   -> CustomScreen(state, vm)
            }
        }
    }
}

// ── Bottom nav ────────────────────────────────────────────────
@Composable
fun BottomNav(current: Screen, onNav: (Screen) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)
        listOf(
            NavItem(Screen.HOME,     "Home",     Icons.Outlined.Home),
            NavItem(Screen.CUSTOM,   "Custom",   Icons.Outlined.Tune),
            NavItem(Screen.PATTERNS, "Saved",    Icons.Outlined.FavoriteBorder),
            NavItem(Screen.MOTORS,   "Motors",   Icons.Outlined.DeviceHub),
            NavItem(Screen.SCHEDULE, "Schedule", Icons.Outlined.Schedule),
        ).forEach { item ->
            NavigationBarItem(
                selected = current == item.screen,
                onClick  = { onNav(item.screen) },
                icon     = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp)) },
                label    = { Text(item.label, fontSize = 11.sp) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor   = Teal500,
                    selectedTextColor   = Teal500,
                    indicatorColor      = Teal50,
                    unselectedIconColor = Gray500,
                    unselectedTextColor = Gray500
                )
            )
        }
    }
}

// ── HOME SCREEN ───────────────────────────────────────────────
@Composable
fun HomeScreen(state: UiState, vm: MainViewModel, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val connected = state.bleState is BleManager.State.Connected
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp)
    ) {
        item { AppHeader() }
        item { StatusCard(state.bleState, onConnect, onDisconnect) }

        // Motor selector
        if (state.motorPins.size > 1) {
            item {
                SectionLabel("Motor")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.motorPins.forEachIndexed { i, pin ->
                        val sel = i == state.selectedMotorIndex
                        FilterChip(
                            selected = sel, onClick = { vm.selectMotor(i) },
                            label = { Text("M${i+1} · GPIO$pin", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Teal50,
                                selectedLabelColor = Teal600
                            )
                        )
                    }
                }
            }
        }

        item {
            SectionLabel("Preset patterns")
            Spacer(Modifier.height(8.dp))
            PatternGrid(PRESET_PATTERNS, state.selectedPresetId, vm::selectPreset)
        }
        item {
            SectionLabel("Intensity")
            Spacer(Modifier.height(6.dp))
            IntensityRow(state.intensity, vm::setIntensity)
        }
        item {
            Button(
                onClick = vm::sendPreset, enabled = connected,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500, disabledContainerColor = Gray200)
            ) {
                Icon(Icons.Outlined.Vibration, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send pattern", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        item {
            SectionLabel("Log")
            Spacer(Modifier.height(6.dp))
            LogCard(state.logs)
        }
    }
}

// ── CUSTOM SCREEN ─────────────────────────────────────────────
@Composable
fun CustomScreen(state: UiState, vm: MainViewModel) {
    val connected = state.bleState is BleManager.State.Connected
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp)
    ) {
        item { ScreenTitle("Custom builder", Icons.Outlined.Tune) }

        // Step timing
        item {
            SectionLabel("Step duration: ${state.customStepMs} ms")
            Slider(
                value = state.customStepMs.toFloat(), onValueChange = { vm.setCustomStepMs(it.toInt()) },
                valueRange = 20f..500f, steps = 23,
                colors = SliderDefaults.colors(thumbColor = Teal500, activeTrackColor = Teal500, inactiveTrackColor = Gray200)
            )
        }

        // Intensity
        item {
            SectionLabel("Intensity")
            IntensityRow(state.intensity, vm::setIntensity)
        }

        // Motor selector
        if (state.motorPins.size > 1) {
            item {
                SectionLabel("Motor")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    state.motorPins.forEachIndexed { i, pin ->
                        FilterChip(
                            selected = i == state.selectedMotorIndex, onClick = { vm.selectMotor(i) },
                            label = { Text("M${i+1} · GPIO$pin", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal50, selectedLabelColor = Teal600)
                        )
                    }
                }
            }
        }

        // Sequence builder
        item {
            SectionLabel("Sequence (tap to toggle)")
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(0.5.dp, Gray200), color = Color.White) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.customSeq.forEachIndexed { i, on ->
                            Box(
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (on) Teal50 else Gray50)
                                    .border(0.5.dp, if (on) Teal100 else Gray200, RoundedCornerShape(8.dp))
                                    .clickable { vm.toggleCustomStep(i) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (on) "●" else "○", fontSize = 14.sp, color = if (on) Teal500 else Gray500)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SmallChip("+ On",   onClick = { vm.addCustomStep(true) })
                        SmallChip("+ Off",  onClick = { vm.addCustomStep(false) })
                        SmallChip("− Remove", onClick = { vm.removeLastStep() })
                        SmallChip("Clear",  onClick = { vm.clearCustomSeq() })
                    }
                }
            }
        }

        // Send + Save
        item {
            Button(
                onClick = vm::sendCustom, enabled = connected,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500, disabledContainerColor = Gray200)
            ) {
                Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send now", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(0.5.dp, Gray200), color = Color.White) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Save this pattern")
                    OutlinedTextField(
                        value = state.customName,
                        onValueChange = vm::setCustomName,
                        placeholder = { Text("Pattern name", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedButton(
                        onClick = vm::saveCurrentCustom,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Save, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save pattern", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ── SAVED PATTERNS SCREEN ─────────────────────────────────────
@Composable
fun PatternsScreen(state: UiState, vm: MainViewModel) {
    val connected = state.bleState is BleManager.State.Connected
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp)
    ) {
        item { ScreenTitle("Saved patterns", Icons.Outlined.FavoriteBorder) }
        if (state.savedPatterns.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = Gray200, modifier = Modifier.size(48.dp))
                        Text("No saved patterns yet", color = Gray500, fontSize = 14.sp)
                        Text("Build one in Custom tab", color = Gray500, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(state.savedPatterns, key = { it.id }) { pattern ->
                SavedPatternCard(pattern, connected, onSend = { vm.sendSavedPattern(it) }, onDelete = { vm.deletePattern(it.id) })
            }
        }
    }
}

@Composable
fun SavedPatternCard(pattern: SavedPattern, connected: Boolean, onSend: (SavedPattern) -> Unit, onDelete: (SavedPattern) -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(0.5.dp, Gray200), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pattern.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("${pattern.steps.size} steps · ${pattern.stepMs}ms · ${pattern.intensity}% · GPIO${0}", fontSize = 12.sp, color = Gray500)
                // Mini waveform
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(14.dp)) {
                    pattern.steps.take(16).forEach { on ->
                        Box(modifier = Modifier.width(4.dp).fillMaxHeight(if (on) 1f else 0.3f).clip(RoundedCornerShape(1.dp)).background(if (on) Teal500 else Gray200))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { showDelete = true }) {
                Icon(Icons.Outlined.Delete, null, tint = Gray500, modifier = Modifier.size(18.dp))
            }
            Button(
                onClick = { onSend(pattern) }, enabled = connected,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500, disabledContainerColor = Gray200)
            ) { Text("Send", fontSize = 13.sp) }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete pattern?") },
            text  = { Text("\"${pattern.name}\" will be removed.") },
            confirmButton = { TextButton(onClick = { onDelete(pattern); showDelete = false }) { Text("Delete", color = Red400) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }
}

// ── MOTORS SCREEN ─────────────────────────────────────────────
@Composable
fun MotorsScreen(state: UiState, vm: MainViewModel) {
    var showAddMotor by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp)
    ) {
        item { ScreenTitle("Motor pins", Icons.Outlined.DeviceHub) }
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFF8E1), border = BorderStroke(0.5.dp, Color(0xFFFFE082))) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = Amber400, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                    Text("Changes are sent to ESP32 automatically. Re-flash is not needed.", fontSize = 12.sp, color = Color(0xFF5D4037))
                }
            }
        }
        items(state.motorPins.indices.toList()) { index ->
            MotorPinCard(
                index = index, gpio = state.motorPins[index],
                canDelete = state.motorPins.size > 1,
                onEdit = { editingIndex = index },
                onDelete = { vm.removeMotor(index) }
            )
        }
        if (state.motorPins.size < 4) {
            item {
                OutlinedButton(
                    onClick = { showAddMotor = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add motor", fontSize = 14.sp)
                }
            }
        }
    }

    if (showAddMotor) {
        PinPickerDialog(
            title = "Add motor",
            currentGpio = null,
            usedPins = state.motorPins,
            onConfirm = { gpio -> vm.addMotor(gpio); showAddMotor = false },
            onDismiss = { showAddMotor = false }
        )
    }
    editingIndex?.let { idx ->
        PinPickerDialog(
            title = "Change pin for Motor ${idx+1}",
            currentGpio = state.motorPins[idx],
            usedPins = state.motorPins.filterIndexed { i, _ -> i != idx },
            onConfirm = { gpio -> vm.updateMotorPin(idx, gpio); editingIndex = null },
            onDismiss = { editingIndex = null }
        )
    }
}

@Composable
fun MotorPinCard(index: Int, gpio: Int, canDelete: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(0.5.dp, Gray200), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Teal50), contentAlignment = Alignment.Center) {
                Text("M${index+1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Teal600)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Motor ${index+1}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("GPIO $gpio", fontSize = 12.sp, color = Gray500)
            }
            TextButton(onClick = onEdit) { Text("Change", fontSize = 13.sp, color = Teal500) }
            if (canDelete) {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, null, tint = Red400, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
fun PinPickerDialog(title: String, currentGpio: Int?, usedPins: List<Int>, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(currentGpio ?: AVAILABLE_PINS.first { it.gpio !in usedPins }.gpio) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AVAILABLE_PINS.forEach { pin ->
                    val used = pin.gpio in usedPins && pin.gpio != currentGpio
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selected == pin.gpio) Teal50 else Color.Transparent)
                            .clickable(enabled = !used) { if (!used) selected = pin.gpio }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(selected = selected == pin.gpio, onClick = { if (!used) selected = pin.gpio }, enabled = !used,
                            colors = RadioButtonDefaults.colors(selectedColor = Teal500))
                        Text(pin.label, fontSize = 14.sp, color = if (used) Gray200 else MaterialTheme.colorScheme.onSurface)
                        if (used) Text("(in use)", fontSize = 11.sp, color = Gray200)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected) }, colors = ButtonDefaults.buttonColors(containerColor = Teal500)) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── SCHEDULE SCREEN ───────────────────────────────────────────
@Composable
fun ScheduleScreen(state: UiState, vm: MainViewModel) {
    var showAdd by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp)
    ) {
        item { ScreenTitle("Schedule", Icons.Outlined.Schedule) }
        item {
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                enabled = state.savedPatterns.isNotEmpty()
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add schedule", fontSize = 14.sp)
            }
            if (state.savedPatterns.isEmpty()) {
                Text("Save a pattern first in the Custom tab", fontSize = 12.sp, color = Gray500, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (state.schedules.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Schedule, null, tint = Gray200, modifier = Modifier.size(48.dp))
                        Text("No schedules yet", color = Gray500, fontSize = 14.sp)
                    }
                }
            }
        } else {
            items(state.schedules, key = { it.id }) { schedule ->
                ScheduleCard(schedule, onToggle = { vm.toggleSchedule(it) }, onDelete = { vm.deleteSchedule(it) })
            }
        }
    }

    if (showAdd) {
        AddScheduleDialog(
            patterns = state.savedPatterns,
            onConfirm = { patternId, name, h, m -> vm.addSchedule(patternId, name, h, m); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }
}

@Composable
fun ScheduleCard(schedule: Schedule, onToggle: (String) -> Unit, onDelete: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(0.5.dp, Gray200), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${schedule.hour.toString().padStart(2,'0')}:${schedule.minute.toString().padStart(2,'0')}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (schedule.enabled) Teal600 else Gray500)
                Text(schedule.patternName, fontSize = 13.sp, color = Gray500)
                Text("Daily", fontSize = 11.sp, color = Gray500)
            }
            Switch(checked = schedule.enabled, onCheckedChange = { onToggle(schedule.id) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Teal500))
            IconButton(onClick = { onDelete(schedule.id) }) {
                Icon(Icons.Outlined.Delete, null, tint = Red400, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun AddScheduleDialog(patterns: List<SavedPattern>, onConfirm: (String, String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var selectedPattern by remember { mutableStateOf(patterns.first()) }
    var hour   by remember { mutableStateOf(8) }
    var minute by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add schedule", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Pattern picker
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pattern", fontSize = 12.sp, color = Gray500)
                    patterns.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (selectedPattern.id == p.id) Teal50 else Color.Transparent)
                                .clickable { selectedPattern = p }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = selectedPattern.id == p.id, onClick = { selectedPattern = p },
                                colors = RadioButtonDefaults.colors(selectedColor = Teal500))
                            Text(p.name, fontSize = 14.sp)
                        }
                    }
                }
                // Time picker
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Time", fontSize = 12.sp, color = Gray500)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Hour", fontSize = 11.sp, color = Gray500)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { hour = (hour - 1 + 24) % 24 }) { Icon(Icons.Outlined.Remove, null, modifier = Modifier.size(18.dp)) }
                                Text(hour.toString().padStart(2,'0'), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                IconButton(onClick = { hour = (hour + 1) % 24 }) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)) }
                            }
                        }
                        Text(":", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Minute", fontSize = 11.sp, color = Gray500)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { minute = (minute - 5 + 60) % 60 }) { Icon(Icons.Outlined.Remove, null, modifier = Modifier.size(18.dp)) }
                                Text(minute.toString().padStart(2,'0'), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                IconButton(onClick = { minute = (minute + 5) % 60 }) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedPattern.id, selectedPattern.name, hour, minute) },
                colors = ButtonDefaults.buttonColors(containerColor = Teal500)) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Shared components ─────────────────────────────────────────
@Composable
fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Teal50), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Wifi, null, tint = Teal500, modifier = Modifier.size(22.dp))
        }
        Column {
            Text("VibControl", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("ESP32-C3 Motor Controller", fontSize = 13.sp, color = Gray500)
        }
    }
}

@Composable
fun ScreenTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Teal50), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Teal500, modifier = Modifier.size(18.dp))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusCard(state: BleManager.State, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val (dotColor, statusText, btnText, btnAction) = when (state) {
        is BleManager.State.Connected    -> Quadruple(Teal500, "Connected to ${state.deviceName}", "Disconnect", onDisconnect)
        is BleManager.State.Scanning     -> Quadruple(Amber400, "Scanning…", "Cancel", onDisconnect)
        is BleManager.State.Disconnected -> Quadruple(Gray500, "Not connected", "Connect", onConnect)
    }
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, Gray200), color = Gray50) {
        Row(modifier = Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Text(statusText, modifier = Modifier.weight(1f), fontSize = 13.sp)
            OutlinedButton(
                onClick = btnAction, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.5.dp, if (state is BleManager.State.Connected) Red400.copy(0.6f) else Gray200),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (state is BleManager.State.Connected) Red400 else MaterialTheme.colorScheme.onSurface)
            ) { Text(btnText, fontSize = 13.sp) }
        }
    }
}

@Composable
fun PatternGrid(patterns: List<PresetPattern>, selectedId: String, onSelect: (String) -> Unit) {
    val icons = mapOf(
        "single" to Icons.Outlined.RadioButtonUnchecked, "double" to Icons.Outlined.DensitySmall,
        "sos" to Icons.Outlined.Campaign, "heartbeat" to Icons.Outlined.MonitorHeart,
        "escalate" to Icons.Outlined.TrendingUp, "continuous" to Icons.Outlined.LinearScale
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        patterns.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onSelect(p.id) },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(if (p.id == selectedId) 1.5.dp else 0.5.dp, if (p.id == selectedId) Teal500 else Gray200),
                        color = if (p.id == selectedId) Teal50 else Color.White
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Icon(icons[p.id] ?: Icons.Outlined.Circle, null, tint = if (p.id == selectedId) Teal500 else Gray500, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (p.id == selectedId) Teal600 else MaterialTheme.colorScheme.onSurface)
                            Text(p.desc, fontSize = 11.sp, color = if (p.id == selectedId) Teal500 else Gray500)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(16.dp)) {
                                p.vizHeights.forEach { h ->
                                    Box(modifier = Modifier.width(5.dp).height((3 + h * 1.6f).dp).clip(RoundedCornerShape(2.dp)).background(if (p.id == selectedId) Teal500 else Gray200))
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun IntensityRow(intensity: Int, onChanged: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = intensity.toFloat(), onValueChange = { onChanged(it.toInt()) }, valueRange = 1f..100f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Teal500, activeTrackColor = Teal500, inactiveTrackColor = Gray200)
        )
        Text("$intensity%", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(40.dp))
    }
}

@Composable
fun LogCard(logs: List<LogEntry>) {
    Surface(shape = RoundedCornerShape(12.dp), color = Gray50, border = BorderStroke(0.5.dp, Gray200)) {
        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("No activity yet", fontSize = 12.sp, color = Gray500, fontFamily = FontFamily.Monospace)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                logs.forEach { entry ->
                    val color = when (entry.level) { BleManager.LogLevel.OK -> Teal600; BleManager.LogLevel.ERROR -> Red400; else -> MaterialTheme.colorScheme.onSurface }
                    Text("${entry.time}  ${entry.message}", fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.sp, color = Gray500)
}

@Composable
fun SmallChip(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(0.5.dp, Gray200)) {
        Text(label, fontSize = 12.sp)
    }
}

data class Quadruple<A,B,C,D>(val a: A, val b: B, val c: C, val d: D)

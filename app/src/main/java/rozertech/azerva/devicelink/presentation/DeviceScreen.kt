package rozertech.azerva.devicelink.presentation

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rozertech.azerva.devicelink.core.util.PayloadCodec
import rozertech.azerva.devicelink.domain.model.EventLog
import rozertech.azerva.devicelink.domain.model.GattAttribute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
* Main Material 3 screen for DeviceLink.
*
* Uses a single immutable [DeviceUiState] supplied by the Activity. It does
* not directly access domain or data repositories; user actions call ViewModel
* methods and Bluetooth permissions are requested through Activity Results.
*
* @param model ViewModel handling actions and transient notices.
* @param state Latest UI state collected by MainActivity.
* @param nfcReady True when the device currently reports NFC as enabled.
*/
@Composable
fun DeviceScreen(model: DeviceViewModel, state: DeviceUiState, nfcReady: Boolean) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val bluetoothAdapter = remember(context) {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }

    // Only a pending user action is retained while system permission dialogs run.
    var pendingBleAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** Checks both runtime permissions required by this application's BLE flow. */
    fun hasBlePermissions(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /** Checks Bluetooth state without assuming permission cannot be revoked. */
    fun bluetoothEnabled(): Boolean = try {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED && bluetoothAdapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val queued = pendingBleAction
        pendingBleAction = null
        if (result.resultCode == Activity.RESULT_OK &&
            hasBlePermissions() && bluetoothEnabled()
        ) {
            queued?.invoke()
        } else {
            model.notice("Bluetooth must be enabled to use live BLE")
        }
    }

    val requestBlePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allGranted = granted[Manifest.permission.BLUETOOTH_SCAN] == true &&
                granted[Manifest.permission.BLUETOOTH_CONNECT] == true

        if (!allGranted || !hasBlePermissions()) {
            pendingBleAction = null
            model.notice("Nearby devices permission is required")
        } else if (!bluetoothEnabled()) {
            if (bluetoothAdapter == null) {
                pendingBleAction = null
                model.notice("Bluetooth is unavailable on this device")
            } else {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            val queued = pendingBleAction
            pendingBleAction = null
            queued?.invoke()
        }
    }

    /** Requests only the missing prerequisites before executing a BLE action. */
    fun requireBleReady(action: () -> Unit) {
        if (model.uiState.value.isDemoEnabled) {
            action()
            return
        }
        // A permission dialog can outlive the current tab or demo setting.
        // Discard its queued action if the user changes context meanwhile.
        val requestedTab = model.uiState.value.tab
        pendingBleAction = {
            val latest = model.uiState.value
            if (latest.tab == requestedTab && !latest.isDemoEnabled) action()
        }
        when {
            !hasBlePermissions() -> requestBlePermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
            bluetoothAdapter == null -> {
                pendingBleAction = null
                model.notice("Bluetooth hardware is unavailable")
            }
            !bluetoothEnabled() -> {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else -> {
                pendingBleAction = null
                action()
            }
        }
    }

    // Keep snackbar display sequential, so a new notice does not cancel the old one.
    LaunchedEffect(model) {
        model.notices.collect { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                DeviceTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = state.tab == destination,
                        onClick = { model.setTab(destination) },
                        icon = {
                            Text(
                                when (destination) {
                                    DeviceTab.HOME -> "H"
                                    DeviceTab.BLE -> "B"
                                    DeviceTab.NFC -> "N"
                                    DeviceTab.SENSORS -> "S"
                                    DeviceTab.HISTORY -> "E"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        label = {
                            Text(
                                when (destination) {
                                    DeviceTab.HOME -> "Home"
                                    DeviceTab.BLE -> "BLE"
                                    DeviceTab.NFC -> "NFC"
                                    DeviceTab.SENSORS -> "Sensors"
                                    DeviceTab.HISTORY -> "History"
                                }
                            )
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DemoHeader(state.isDemoEnabled, model::setDemo)
            Box(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).weight(1f)) {
                when (state.tab) {
                    DeviceTab.HOME -> HomeContent(state, model::setTab)
                    DeviceTab.BLE -> BleContent(
                        state = state,
                        onScan = {
                            if (state.isScanning) model.stopScan()
                            else requireBleReady {
                                if (model.uiState.value.tab == DeviceTab.BLE) model.scan()
                            }
                        },
                        onConnect = { address ->
                            requireBleReady {
                                if (model.uiState.value.tab == DeviceTab.BLE) model.connect(address)
                            }
                        },
                        onDisconnect = model::disconnect,
                        onRead = { attribute -> requireBleReady { model.read(attribute) } },
                        onWrite = { attribute, text ->
                            requireBleReady { model.write(attribute, text) }
                        },
                        onSubscribe = { attribute -> requireBleReady { model.subscribe(attribute) } }
                    )
                    DeviceTab.NFC -> NfcContent(state, nfcReady, model::simulateTag)
                    DeviceTab.SENSORS -> SensorContent(state)
                    DeviceTab.HISTORY -> HistoryContent(state.history, model::clearHistory)
                }
            }
        }
    }
}

/**
 * Header indicating whether displayed hardware data is real or synthetic.
 *
 * @param demoEnabled Current simulation setting.
 * @param onModeChanged Switch callback to update the ViewModel.
 */
@Composable
private fun DemoHeader(demoEnabled: Boolean, onModeChanged: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("DEVICE LINK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Android hardware explorer", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = demoEnabled, onCheckedChange = onModeChanged)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (demoEnabled) "DEMO · simulated peripherals and sensor readings"
                else "LIVE · physical hardware only",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Overview navigation for BLE, NFC, sensors and local history.
 *
 * @param state Snapshot displayed in overview cards.
 * @param onTabSelected Navigation callback.
 */
@Composable
private fun HomeContent(state: DeviceUiState, onTabSelected: (DeviceTab) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("Hardware overview", "Discover and inspect supported Android interfaces.")
        }
        item {
            Text(
                if (state.isDemoEnabled) "All demo readings are synthetic; no physical hardware is accessed."
                else "Permission is requested only when you initiate Bluetooth operations.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            NavigationTile(
                title = "Bluetooth Low Energy",
                subtitle = state.connection?.takeIf { it.connected }?.let {
                    "Connected to ${it.address}"
                } ?: "Scan peripherals and inspect GATT services"
            ) { onTabSelected(DeviceTab.BLE) }
        }
        item {
            NavigationTile(
                title = "NFC reader",
                subtitle = state.nfc?.let { "Last tag: ${it.uid}" }
                    ?: "Read tag identifiers and NDEF text"
            ) { onTabSelected(DeviceTab.NFC) }
        }
        item {
            NavigationTile(
                title = "Motion and ambient light",
                subtitle = "Read sensors while the Sensors tab is visible"
            ) { onTabSelected(DeviceTab.SENSORS) }
        }
        item {
            NavigationTile(
                title = "Local history",
                subtitle = "${state.history.size} recent events stored on this device"
            ) { onTabSelected(DeviceTab.HISTORY) }
        }
        item {
            Text(
                "No background BLE scanning. Real event history remains local to this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Creates an accessible, click-to-navigate card for the overview screen. */
@Composable
private fun NavigationTile(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Displays a consistent title and description for each section. */
@Composable
private fun SectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * BLE discovery and GATT interaction screen.
 *
 * The payload is kept in saveable Compose state because it is a transient
 * text-field input, not a domain model or a persistent ViewModel state.
 */
@Composable
private fun BleContent(
    state: DeviceUiState,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRead: (GattAttribute) -> Unit,
    onWrite: (GattAttribute, String) -> Unit,
    onSubscribe: (GattAttribute) -> Unit
) {
    var writeHex by rememberSaveable { mutableStateOf("01 FF") }
    val connected = state.connection?.takeIf { it.connected }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Bluetooth LE", "Scan, connect, read and write GATT characteristics.") }
        item {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isConnecting
            ) {
                Text(if (state.isScanning) "Stop scan" else "Scan for 12 seconds")
            }
        }
        if (state.isScanning || state.isConnecting) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        if (connected != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Text(connected.address, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = writeHex,
                    onValueChange = { writeHex = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Write payload (hex)") },
                    supportingText = { Text("1–20 bytes. Only write to your own or authorized devices.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )
            }
            item { Text("Last read: ${state.lastValue}") }
            item { Text("Last notification: ${state.lastNotification}") }
            item { Text("GATT characteristics (${connected.attributes.size})", fontWeight = FontWeight.Bold) }

            items(
                items = connected.attributes,
                key = { "${it.serviceUuid}/${it.characteristicUuid}" }
            ) { attribute ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(attribute.characteristicUuid, style = MaterialTheme.typography.labelLarge)
                        Text("Service: ${attribute.serviceUuid}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (attribute.readable) {
                                FilledTonalButton(onClick = { onRead(attribute) }) { Text("Read") }
                            }
                            if (attribute.writable) {
                                FilledTonalButton(onClick = { onWrite(attribute, writeHex) }) { Text("Write") }
                            }
                            if (attribute.notifiable) {
                                FilledTonalButton(onClick = { onSubscribe(attribute) }) { Text("Notify") }
                            }
                        }
                    }
                }
            }
        } else {
            if (state.connection?.error != null) {
                item { Text("Connection: ${state.connection.error}", color = MaterialTheme.colorScheme.error) }
            }
            item { Text("Nearby peripherals (${state.peripherals.size})", fontWeight = FontWeight.Bold) }
            if (state.peripherals.isEmpty()) {
                item { Text("Nothing discovered yet. Start a scan or enable demo mode.") }
            }
            items(state.peripherals, key = { it.address }) { peripheral ->
                Card(onClick = { if (!state.isConnecting) onConnect(peripheral.address) },
                    modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peripheral.name, fontWeight = FontWeight.SemiBold)
                            Text(peripheral.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${peripheral.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Displays the last recorded NFC tag and a clearly marked demo-only action. */
@Composable
private fun NfcContent(state: DeviceUiState, nfcReady: Boolean, simulate: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("NFC reader", "Read tag identifiers and NDEF text (read-only).") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when {
                            state.isDemoEnabled -> "Synthetic tag reader"
                            nfcReady -> "Hold an NFC tag near your phone"
                            else -> "NFC unavailable or disabled"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Real NFC reader mode runs only while this tab is in the foreground.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.isDemoEnabled) {
                        Button(onClick = simulate) { Text("Simulate NFC tag") }
                    }
                }
            }
        }
        item { Text("Latest tag", fontWeight = FontWeight.Bold) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("UID", style = MaterialTheme.typography.labelMedium)
                    Text(state.nfc?.uid ?: "No tag detected")
                    HorizontalDivider()
                    Text("NDEF text", style = MaterialTheme.typography.labelMedium)
                    Text(state.nfc?.text ?: "No text record")
                }
            }
        }
    }
}

/** Displays accelerometer readings and the optional ambient light measurement. */
@Composable
private fun SensorContent(state: DeviceUiState) {
    val reading = state.sensor
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle(
                "Motion and light",
                if (state.isDemoEnabled) "Synthetic sensor values (demo)"
                else "Live values while this section is visible"
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Acceleration", style = MaterialTheme.typography.titleMedium)
                    SensorAxis("X", reading?.x)
                    SensorAxis("Y", reading?.y)
                    SensorAxis("Z", reading?.z)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Ambient light", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        reading?.lightLux?.let { String.format(Locale.US, "%.1f lux", it) }
                            ?: "Sensor not available or no reading yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
        if (reading == null) {
            item { Text("Waiting for sensor samples. Some devices do not have a light sensor.") }
        }
    }
}

/**
 * Presents a single accelerometer axis and a clamped visual indicator.
 *
 * @param axis Axis label (X/Y/Z).
 * @param value Acceleration in m/s², if available.
 */
@Composable
private fun SensorAxis(axis: String, value: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$axis axis")
            Text(value?.let { String.format(Locale.US, "%.2f m/s²", it) } ?: "—")
        }
        val fraction = (((value ?: 0f) + 20f) / 40f).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Shows recent event history with a confirmation step before destructive clearing.
 *
 * @param entries Entries already ordered by the Room DAO.
 * @param onClear Invoked only after explicit confirmation.
 */
@Composable
private fun HistoryContent(entries: List<EventLog>, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear local history?") },
            text = { Text("This deletes all stored events from this device. The action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionTitle("Local event history", "Up to 250 recent events; 30-day cleanup policy.")
                }
                TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                    Text("Clear")
                }
            }
        }
        if (entries.isEmpty()) {
            item { Text("No stored events yet. Demo actions are not saved here.") }
        }
        items(entries, key = { it.id }) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(entry.title, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.kind.name, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(entry.detail, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text(
                        dateFormat.format(Instant.ofEpochMilli(entry.at)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}





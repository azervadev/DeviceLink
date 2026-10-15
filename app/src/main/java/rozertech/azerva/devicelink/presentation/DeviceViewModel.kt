package rozertech.azerva.devicelink.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rozertech.azerva.devicelink.core.util.PayloadCodec
import rozertech.azerva.devicelink.data.demo.DemoFixtures
import rozertech.azerva.devicelink.domain.model.BlePeripheral
import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.model.GattAttribute
import rozertech.azerva.devicelink.domain.model.GattSnapshot
import rozertech.azerva.devicelink.domain.model.MotionSample
import rozertech.azerva.devicelink.domain.model.NfcRecord
import rozertech.azerva.devicelink.domain.repository.BleRepository
import rozertech.azerva.devicelink.domain.repository.DemoModeRepository
import rozertech.azerva.devicelink.domain.repository.EventRepository
import rozertech.azerva.devicelink.domain.repository.NfcRepository
import rozertech.azerva.devicelink.domain.repository.SensorRepository
import rozertech.azerva.devicelink.domain.usecase.ConnectBleUseCase
import rozertech.azerva.devicelink.domain.usecase.ReadGattUseCase
import rozertech.azerva.devicelink.domain.usecase.RecordNfcTagUseCase
import rozertech.azerva.devicelink.domain.usecase.WriteGattUseCase
import javax.inject.Inject
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

/**
 * Screen-level state holder for the five DeviceLink sections.
 *
 * Domain use cases execute BLE/NFC actions. Repositories expose continuous
 * BLE, NFC, sensor and history observations. [DeviceUiState] is the only
 * persistent UI state stream; one-off notices are sent through [notices].
 *
 * Demo BLE/NFC state is kept separate from real hardware state, and simulated
 * interactions are never written into the persistent event history.
 *
 * @param connectBle Connects a real peripheral and records a successful event.
 * @param readGatt Reads a characteristic and records its value.
 * @param writeGatt Validates, writes and records a hexadecimal payload.
 * @param recordNfcTag Records a real NFC tag and its associated event.
 * @param bleRepository Provides BLE scanning, GATT state and notifications.
 * @param nfcRepository Exposes the latest real NFC record.
 * @param sensorRepository Exposes live Android sensor samples.
 * @param eventRepository Exposes and clears locally stored history.
 * @param demoRepository Maintains the voluntary demo-mode setting.
 */
@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val connectBle: ConnectBleUseCase,
    private val readGatt: ReadGattUseCase,
    private val writeGatt: WriteGattUseCase,
    private val recordNfcTag: RecordNfcTagUseCase,
    private val bleRepository: BleRepository,
    private val nfcRepository: NfcRepository,
    private val sensorRepository: SensorRepository,
    private val eventRepository: EventRepository,
    private val demoRepository: DemoModeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // Separate demo state prevents test tags or fake connections leaking into live mode.
    private val demoConnection = MutableStateFlow<GattSnapshot?>(null)
    private val demoNfc = MutableStateFlow<NfcRecord?>(null)

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var gattJob: Job? = null
    private var scanGeneration: Long = 0L

    init {
        observeDemoMode()
        observeConnection()
        observeNfc()
        observeHistory()
        observeNotifications()
        observeSensors()
    }

    /** Synchronizes the shared demo-mode setting with the UI state. */
    private fun observeDemoMode() {
        viewModelScope.launch {
            demoRepository.enabled.collect { enabled ->
                _uiState.update { it.copy(isDemoEnabled = enabled) }
            }
        }
    }

    /** Chooses either the real GATT snapshot or the synthetic snapshot. */
    private fun observeConnection() {
        viewModelScope.launch {
            combine(
                bleRepository.connection,
                demoRepository.enabled,
                demoConnection
            ) { live, demo, simulated ->
                if (demo) simulated else live
            }.collect { connection ->
                _uiState.update { it.copy(connection = connection) }
            }
        }
    }

    /** Prevents simulated NFC tags from appearing as real detections. */
    private fun observeNfc() {
        viewModelScope.launch {
            combine(
                nfcRepository.latest,
                demoRepository.enabled,
                demoNfc
            ) { live, demo, simulated ->
                if (demo) simulated else live
            }.collect { latest ->
                _uiState.update { it.copy(nfc = latest) }
            }
        }
    }

    /** Collects the Room history and reports database observation failures. */
    private fun observeHistory() {
        viewModelScope.launch {
            try {
                eventRepository.events().collect { events ->
                    _uiState.update { it.copy(history = events) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message ?: "Unable to observe event history")
            }
        }
    }

    /** Records real BLE notifications independently of UI visibility. */
    private fun observeNotifications() {
        viewModelScope.launch {
            bleRepository.notifications.collect { payload ->
                if (demoRepository.enabled.value) return@collect

                _uiState.update {
                    it.copy(lastNotification = payload.take(250))
                }

                try {
                    eventRepository.add(
                        kind = EventKind.BLE,
                        title = "GATT notification",
                        detail = payload.take(250)
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A storage failure must not stop the notification observer.
                    notice("BLE notification received, but history could not be saved")
                }
            }
        }
    }

    /**
     * Collects sensors only while the sensor tab is selected.
     *
     * `collectLatest` cancels an active sensor collection when the user switches
     * tabs or toggles demo mode, allowing callbackFlow to release its listeners.
     */
    private fun observeSensors() {
        viewModelScope.launch {
            combine(
                uiState.map { it.tab }.distinctUntilChanged(),
                demoRepository.enabled
            ) { tab, demo -> tab to demo }
                .collectLatest { (tab, isDemo) ->
                    _uiState.update { it.copy(sensor = null) }
                    if (tab != DeviceTab.SENSORS) return@collectLatest

                    try {
                        if (isDemo) {
                            var step = 0
                            while (currentCoroutineContext().isActive) {
                                val t = step++ / 6.0
                                _uiState.update { state ->
                                    state.copy(
                                        sensor = MotionSample(
                                            x = (sin(t) * 2).toFloat(),
                                            y = (sin(t / 2) * 1.2).toFloat(),
                                            z = (9.81 + sin(t / 4) / 5).toFloat(),
                                            lightLux = (240 + sin(t) * 40).toFloat(),
                                            collectedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                delay(350)
                            }
                        } else {
                            sensorRepository.observe().collect { sample ->
                                _uiState.update { it.copy(sensor = sample) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        notice(error.message ?: "Unable to read sensors")
                    }
                }
        }
    }

    /**
     * Selects a tab and stops BLE discovery when leaving the BLE section.
     *
     * @param tab Destination tab.
     */
    fun setTab(tab: DeviceTab) {
        if (tab != DeviceTab.BLE) stopScan()
        _uiState.update { it.copy(tab = tab) }
    }

    /**
     * Switches between live hardware and explicitly labelled synthetic data.
     *
     * Cancels in-flight tasks, releases live GATT and clears transient readings.
     * Persistent real history is deliberately left untouched.
     *
     * @param enabled True for demonstration mode; false for physical hardware.
     */
    fun setDemo(enabled: Boolean) {
        if (demoRepository.enabled.value == enabled) return

        stopScan()
        connectionJob?.cancel()
        gattJob?.cancel()
        bleRepository.disconnect()
        demoConnection.value = null
        demoNfc.value = null
        demoRepository.setEnabled(enabled)

        _uiState.update {
            it.copy(
                isDemoEnabled = enabled,
                isConnecting = false,
                peripherals = emptyList(),
                connection = null,
                nfc = null,
                lastValue = "No value read",
                lastNotification = "No notifications"
            )
        }
        notice(if (enabled) "DEMO MODE: synthetic data" else "LIVE MODE: physical devices")
    }

    /** Sends an ephemeral message to the current screen's snackbar collector. */
    fun notice(message: String) {
        _notices.tryEmit(message.take(250))
    }

    /**
     * Starts a bounded, cancellable scan (12 seconds for live devices).
     *
     * A generation counter prevents a cancelled scan from overwriting the
     * state of a newer scan after rapid repeated button presses.
     */
    fun scan() {
        if (_uiState.value.isScanning) return
        val generation = ++scanGeneration
        val isDemo = demoRepository.enabled.value

        _uiState.update {
            it.copy(isScanning = true, peripherals = emptyList())
        }

        scanJob = viewModelScope.launch {
            try {
                if (isDemo) {
                    DemoFixtures.peripherals.forEach { peripheral ->
                        if (generation == scanGeneration) addPeripheral(peripheral)
                        delay(350.milliseconds)
                    }
                } else {
                    withTimeoutOrNull(12000.milliseconds) {
                        bleRepository.scan().collect { peripheral ->
                            if (generation == scanGeneration) addPeripheral(peripheral)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message ?: "BLE discovery failed")
            } finally {
                if (generation == scanGeneration) {
                    _uiState.update { it.copy(isScanning = false) }
                    scanJob = null
                }
            }
        }
    }

    /** Stops an active BLE scan and releases the scanner through Flow cancellation. */
    fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        _uiState.update { it.copy(isScanning = false) }
    }

    /**
     * Inserts or replaces a discovered peripheral and orders by signal strength.
     *
     * @param peripheral Latest scan result from a BLE device.
     */
    private fun addPeripheral(peripheral: BlePeripheral) {
        _uiState.update { state ->
            val results = state.peripherals
                .filterNot { it.address == peripheral.address }
                .plus(peripheral)
                .sortedByDescending { it.rssi }
                .take(100)
            state.copy(peripherals = results)
        }
    }

    /**
     * Connects a real device through its use case, or creates a demo snapshot.
     *
     * Demo fixture addresses are never forwarded to Android Bluetooth APIs.
     *
     * @param address Discovered peripheral address.
     */
    fun connect(address: String) {
        if (_uiState.value.isConnecting) return
        stopScan()
        val isDemo = demoRepository.enabled.value

        _uiState.update { it.copy(isConnecting = true) }
        connectionJob = viewModelScope.launch {
            try {
                if (isDemo) {
                    require(DemoFixtures.peripherals.any { it.address == address }) {
                        "Unknown demo peripheral"
                    }
                    demoConnection.value = GattSnapshot(
                        address = address,
                        connected = true,
                        attributes = DemoFixtures.attributes
                    )
                } else {
                    // The use case owns both the BLE operation and event persistence.
                    // Its current implementation can throw if history saving fails.
                    val result = connectBle(address)
                    result.exceptionOrNull()?.let { throw it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val liveConnected = bleRepository.connection.value?.connected == true
                if (!isDemo && liveConnected) {
                    notice("Connected, but the event log may not have been saved")
                } else {
                    notice(error.message ?: "BLE connection failed")
                }
            } finally {
                _uiState.update { it.copy(isConnecting = false) }
                connectionJob = null
            }
        }
    }

    /** Disconnects the current BLE session and resets any simulated connection. */
    fun disconnect() {
        stopScan()
        connectionJob?.cancel()
        gattJob?.cancel()
        bleRepository.disconnect()
        demoConnection.value = null
        _uiState.update { it.copy(isConnecting = false) }
        notice("Disconnected")
    }

    /**
     * Serializes user-initiated GATT actions at the ViewModel level.
     *
     * The BLE repository already serializes actual Android GATT operations;
     * this additional guard prevents repeated UI taps from queuing actions.
     */
    private fun performGatt(action: suspend () -> Unit) {
        if (gattJob?.isActive == true) {
            notice("Another GATT operation is in progress")
            return
        }
        gattJob = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Do not retry a write automatically: hardware may have accepted it
                // before the separate Room history operation failed.
                notice(error.message ?: "Operation failed; check device and history")
            } finally {
                gattJob = null
            }
        }
    }

    /**
     * Reads a characteristic and stores the returned hexadecimal value.
     *
     * @param attribute Discovered GATT characteristic with read capability.
     */
    fun read(attribute: GattAttribute) = performGatt {
        if (!attribute.readable) {
            notice("Characteristic does not support reading")
            return@performGatt
        }
        if (_uiState.value.connection?.connected != true) {
            notice("Connect a peripheral first")
            return@performGatt
        }

        val value = if (demoRepository.enabled.value) {
            if (attribute.characteristicUuid.contains("2A19", ignoreCase = true)) "57" else "01 0A"
        } else {
            readGatt(attribute).getOrElse { throw it }
        }
        _uiState.update { it.copy(lastValue = value) }
    }

    /**
     * Validates and writes a hexadecimal payload to a discovered characteristic.
     *
     * @param attribute GATT characteristic with write capability.
     * @param hexText User-entered hexadecimal bytes, limited by PayloadCodec.
     */
    fun write(attribute: GattAttribute, hexText: String) = performGatt {
        if (!attribute.writable) {
            notice("Characteristic does not support writing")
            return@performGatt
        }
        if (_uiState.value.connection?.connected != true) {
            notice("Connect a peripheral first")
            return@performGatt
        }

        if (demoRepository.enabled.value) {
            try {
                PayloadCodec.hexToBytes(hexText)
                notice("DEMO: simulated write (nothing sent to hardware)")
            } catch (error: IllegalArgumentException) {
                notice(error.message ?: "Invalid hexadecimal payload")
            }
        } else {
            val result = writeGatt(attribute, hexText)
            result.getOrElse { throw it }
            notice("Write completed")
        }
    }

    /**
     * Enables notifications/indications for the selected characteristic.
     *
     * @param attribute Discovered characteristic that supports notifications.
     */
    fun subscribe(attribute: GattAttribute) = performGatt {
        if (!attribute.notifiable) {
            notice("Notifications are not supported")
            return@performGatt
        }
        if (_uiState.value.connection?.connected != true) {
            notice("Connect a peripheral first")
            return@performGatt
        }

        if (demoRepository.enabled.value) {
            _uiState.update {
                it.copy(lastNotification = "${attribute.characteristicUuid}: 57 (DEMO)")
            }
            notice("DEMO: notification simulated")
        } else {
            val outcome = bleRepository.subscribe(attribute)
            outcome.getOrElse { throw it }
            // Unlike the four use cases, subscribe has no own use case yet.
            try {
                eventRepository.add(
                    EventKind.BLE,
                    "Notifications enabled",
                    attribute.characteristicUuid
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("Subscribed, but the event log could not be saved")
                return@performGatt
            }
            notice("Notifications enabled")
        }
    }

    /**
     * Accepts a *real* NFC tag from the foreground Activity reader callback.
     *
     * @param uid Tag identifier formatted in hexadecimal.
     * @param text Optional decoded NDEF text content.
     */
    fun onTag(uid: String, text: String?) {
        if (demoRepository.enabled.value || _uiState.value.tab != DeviceTab.NFC) return
        viewModelScope.launch {
            try {
                recordNfcTag(uid.take(64), text?.take(200))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("NFC tag read, but event history could not be saved")
            }
        }
    }

    /** Creates a synthetic NFC record without modifying the real NFC repository. */
    fun simulateTag() {
        if (!demoRepository.enabled.value || _uiState.value.tab != DeviceTab.NFC) return
        demoNfc.value = NfcRecord(
            uid = "04 A2 6F 11 2C 81 90",
            text = "DeviceLink demo tag (synthetic)",
            seenAt = System.currentTimeMillis()
        )
        notice("DEMO: synthetic tag detected")
    }

    /** Removes locally persisted event history after explicit user confirmation. */
    fun clearHistory() {
        viewModelScope.launch {
            try {
                eventRepository.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message ?: "Failed to clear history")
            }
        }
    }

    /** Cancels UI work and releases BLE resources with the ViewModel lifecycle. */
    override fun onCleared() {
        stopScan()
        connectionJob?.cancel()
        gattJob?.cancel()
        bleRepository.disconnect()
        super.onCleared()
    }
}
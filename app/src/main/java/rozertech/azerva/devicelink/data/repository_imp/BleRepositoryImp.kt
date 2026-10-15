package rozertech.azerva.devicelink.data.repository_imp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import rozertech.azerva.devicelink.core.util.PayloadCodec
import rozertech.azerva.devicelink.domain.model.BlePeripheral
import rozertech.azerva.devicelink.domain.model.GattAttribute
import rozertech.azerva.devicelink.domain.model.GattSnapshot
import rozertech.azerva.devicelink.domain.repository.BleRepository
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android BLE implementation for API 33 and later.
 *
 * Manages scanning, one active GATT connection, characteristic read/write
 * operations and notification subscriptions. GATT requests are serialized;
 * callbacks are associated with the active connection and expected operation.
 *
 * The UI must request BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions.
 * This class checks them and handles permission revocation; it does not
 * request permissions itself.
 *
 * @param context Application context used to access Bluetooth services.
 */
@Singleton
class BleRepositoryImp @Inject constructor(
    @ApplicationContext private val context: Context
) : BleRepository {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    private val operationMutex = Mutex()
    private val guard = Any()
    private var generation = 0L
    private var opening = false
    private var gatt: BluetoothGatt? = null
    private var pending: Pending? = null
    private var connectedAddress: String? = null

    private val connectionState = MutableStateFlow<GattSnapshot?>(null)

    /** Current GATT state; null when no connection is active. */
    override val connection: StateFlow<GattSnapshot?> = connectionState.asStateFlow()

    private val notificationEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Received notifications as bounded uppercase hexadecimal strings. */
    override val notifications: Flow<String> = notificationEvents.asSharedFlow()

    private enum class Operation { DISCOVER, READ, WRITE, SUBSCRIBE }

    private data class Reply(val status: Int, val bytes: ByteArray? = null) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Reply

            if (status != other.status) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + (bytes?.contentHashCode() ?: 0)
            return result
        }
    }

    private data class Pending(
        val operation: Operation,
        val characteristicUuid: UUID?,
        val response: CompletableDeferred<Reply> = CompletableDeferred()
    )

    private val cccdUuid: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Scans for nearby BLE peripherals until collection is cancelled.
     *
     * A missing permission or unavailable Bluetooth adapter closes the Flow
     * with an error. Cancelling collection releases the scan callback.
     *
     * @return Cold Flow of discovered BLE peripherals.
     */
    override fun scan(): Flow<BlePeripheral> = callbackFlow {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("Bluetooth scan/connect permission required"))
            return@callbackFlow
        }

        val scanner = try {
            adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        } catch (error: SecurityException) {
            close(error)
            return@callbackFlow
        }

        if (scanner == null) {
            close(IllegalStateException("Bluetooth disabled or BLE scanner unavailable"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                    PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    close(SecurityException("Bluetooth permission revoked"))
                    return
                }
                try {
                    trySend(
                        BlePeripheral(
                            address = result.device.address,
                            name = result.scanRecord?.deviceName ?: "Unknown peripheral",
                            rssi = result.rssi
                        )
                    )
                } catch (error: SecurityException) {
                    close(error)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed ($errorCode)"))
            }
        }

        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Bluetooth scan permission revoked")
            }
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback
            )
        } catch (error: Exception) {
            close(error)
            return@callbackFlow
        }

        awaitClose {
            try {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    scanner.stopScan(callback)
                }
            } catch (_: SecurityException) {
                // Permission can be revoked after the scan starts.
            } catch (_: IllegalStateException) {
                // Bluetooth can be disabled during scanning.
            }
        }
    }

    /**
     * Checks whether a callback belongs to the current connection.
     *
     * An initial callback may arrive before connectGatt returns. The opening
     * flag permits that callback to establish the active GATT identity.
     */
    private fun accepts(source: BluetoothGatt, session: Long): Boolean =
        synchronized(guard) {
            if (generation != session) {
                false
            } else {
                if (gatt == null && opening) gatt = source
                gatt === source
            }
        }

    /** Completes only the pending operation matching this callback. */
    private fun complete(
        source: BluetoothGatt,
        session: Long,
        operation: Operation,
        uuid: UUID?,
        status: Int,
        bytes: ByteArray? = null
    ) {
        val request = synchronized(guard) {
            if (generation != session || gatt !== source) {
                null
            } else {
                pending?.takeIf {
                    it.operation == operation && it.characteristicUuid == uuid
                }?.also { pending = null }
            }
        }
        request?.response?.complete(Reply(status, bytes?.copyOf()))
    }

    /** Creates GATT callbacks tied to one connection generation. */
    private fun callbacks(address: String, session: Long): BluetoothGattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                source: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (!accepts(source, session)) return

                if (status != BluetoothGatt.GATT_SUCCESS ||
                    newState == BluetoothProfile.STATE_DISCONNECTED
                ) {
                    closeConnection(session, "Disconnected (status $status)")
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (context.checkSelfPermission(
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            throw SecurityException("Bluetooth permission revoked")
                        }
                        if (!source.discoverServices()) {
                            closeConnection(session, "Service discovery could not start")
                        }
                    } catch (_: SecurityException) {
                        closeConnection(session, "Bluetooth permission revoked")
                    }
                }
            }

            override fun onServicesDiscovered(source: BluetoothGatt, status: Int) {
                if (!accepts(source, session)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeConnection(session, "Service discovery failed ($status)")
                    return
                }
                try {
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        throw SecurityException("Bluetooth permission revoked")
                    }
                    val attributes = source.services.flatMap { service ->
                        service.characteristics.map { characteristic ->
                            val flags = characteristic.properties
                            GattAttribute(
                                serviceUuid = service.uuid.toString(),
                                characteristicUuid = characteristic.uuid.toString(),
                                readable = flags and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                                writable = flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                                notifiable = flags and (
                                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                                BluetoothGattCharacteristic.PROPERTY_INDICATE
                                        ) != 0
                            )
                        }
                    }
                    synchronized(guard) {
                        if (generation != session || gatt !== source) return
                        connectionState.value = GattSnapshot(address, true, attributes)
                    }
                    complete(source, session, Operation.DISCOVER, null, status)
                } catch (_: SecurityException) {
                    closeConnection(session, "Bluetooth permission revoked")
                }
            }

            override fun onCharacteristicRead(
                source: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                complete(source, session, Operation.READ, characteristic.uuid, status, value)
            }

            override fun onCharacteristicWrite(
                source: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                complete(source, session, Operation.WRITE, characteristic.uuid, status)
            }

            override fun onDescriptorWrite(
                source: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid != cccdUuid) return
                complete(
                    source,
                    session,
                    Operation.SUBSCRIBE,
                    descriptor.characteristic.uuid,
                    status
                )
            }

            override fun onCharacteristicChanged(
                source: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (!accepts(source, session)) return
                notificationEvents.tryEmit(
                    "${characteristic.uuid}: ${PayloadCodec.bytesToHex(value.take(64).toByteArray())}"
                )
            }
        }

    /**
     * Connects to a BLE peripheral and discovers its GATT services.
     *
     * Uses a 12-second timeout and closes the connection on failure.
     *
     * @param address Bluetooth address from a recent scan.
     * @return Success after service discovery, otherwise a failure Result.
     */
    override suspend fun connect(address: String): Result<Unit> = operationMutex.withLock {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return@withLock Result.failure(IllegalArgumentException("Invalid BLE address"))
        }

        closeConnection()
        var session: Long? = null
        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Bluetooth connect permission required")
            }
            val enabledAdapter = checkNotNull(adapter?.takeIf { it.isEnabled }) {
                "Bluetooth unavailable or disabled"
            }
            val device = enabledAdapter.getRemoteDevice(address)
            val request = Pending(Operation.DISCOVER, null)
            val token = synchronized(guard) {
                generation++
                opening = true
                connectedAddress = address
                pending = request
                generation
            }
            session = token

            val opened = checkNotNull(
                device.connectGatt(context, false, callbacks(address, token), BluetoothDevice.TRANSPORT_LE)
            ) { "Unable to create a GATT connection" }

            val adopted = synchronized(guard) {
                if (generation == token && (gatt == null || gatt === opened)) {
                    gatt = opened
                    opening = false
                    true
                } else {
                    false
                }
            }
            if (!adopted) {
                release(opened)
                error("Connection was interrupted")
            }

            val reply = withTimeout(12000.milliseconds) { request.response.await() }
            check(reply.status == BluetoothGatt.GATT_SUCCESS &&
                    connectionState.value?.connected == true
            ) { "Connection or service discovery failed (${reply.status})" }
            Result.success(Unit)
        } catch (error: TimeoutCancellationException) {
            closeConnection(session, "Connection timed out")
            Result.failure(error)
        } catch (error: CancellationException) {
            closeConnection(session)
            throw error
        } catch (error: Exception) {
            closeConnection(session, error.message)
            Result.failure(error)
        }
    }

    /** Resolves a characteristic from the active GATT service table. */
    private fun resolve(
        source: BluetoothGatt,
        attribute: GattAttribute
    ): BluetoothGattCharacteristic = checkNotNull(
        source.getService(UUID.fromString(attribute.serviceUuid))
            ?.getCharacteristic(UUID.fromString(attribute.characteristicUuid))
    ) { "Characteristic no longer available" }

    /**
     * Executes one serialized GATT operation and waits for its matching callback.
     *
     * A timeout invalidates the connection so a delayed callback cannot
     * complete a subsequent request.
     */
    private suspend fun <T> transact(
        attribute: GattAttribute,
        operation: Operation,
        start: (BluetoothGatt, BluetoothGattCharacteristic) -> Boolean,
        decode: (Reply) -> T
    ): Result<T> = operationMutex.withLock {
        var session: Long? = null
        var request: Pending? = null
        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Bluetooth connect permission required")
            }
            val (source, token) = synchronized(guard) {
                (gatt ?: error("Connect a BLE peripheral first")) to generation
            }
            val target = resolve(source, attribute)
            val current = Pending(operation, target.uuid)
            synchronized(guard) {
                check(generation == token && gatt === source) { "Connection changed" }
                check(pending == null) { "GATT operation already in progress" }
                pending = current
            }
            session = token
            request = current

            check(start(source, target)) { "Android rejected the GATT operation" }
            val reply = withTimeout(8000.milliseconds) { current.response.await() }
            check(reply.status == BluetoothGatt.GATT_SUCCESS) {
                "GATT operation failed (${reply.status})"
            }
            Result.success(decode(reply))
        } catch (error: TimeoutCancellationException) {
            closeConnection(session, "GATT operation timed out")
            Result.failure(error)
        } catch (error: CancellationException) {
            closeConnection(session)
            throw error
        } catch (error: Exception) {
            if (request != null) closeConnection(session, error.message)
            Result.failure(error)
        } finally {
            synchronized(guard) {
                if (pending === request) pending = null
            }
        }
    }

    /**
     * Reads up to 64 bytes from a characteristic and returns hexadecimal text.
     *
     * @param attribute Characteristic supporting PROPERTY_READ.
     * @return Hexadecimal payload, or a failure Result.
     */
    override suspend fun read(attribute: GattAttribute): Result<String> {
        if (!attribute.readable) {
            return Result.failure(IllegalArgumentException("Characteristic not readable"))
        }
        return transact(
            attribute,
            Operation.READ,
            start = { source, target ->
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("Bluetooth permission revoked")
                }
                source.readCharacteristic(target)
            },
            decode = { reply ->
                PayloadCodec.bytesToHex(
                    checkNotNull(reply.bytes) { "Missing read value" }
                        .take(64).toByteArray()
                )
            }
        )
    }

    /**
     * Writes 1–20 bytes using the API 33 memory-safe characteristic write API.
     *
     * @param attribute Characteristic supporting write with response.
     * @param bytes Payload to send.
     * @return Success when the GATT callback confirms.
     */
    override suspend fun write(attribute: GattAttribute, bytes: ByteArray): Result<Unit> {
        if (!attribute.writable) {
            return Result.failure(IllegalArgumentException("Characteristic not writable"))
        }
        if (bytes.size !in 1..20) {
            return Result.failure(IllegalArgumentException("Write size must be 1–20 bytes"))
        }
        return transact(
            attribute,
            Operation.WRITE,
            start = { source, target ->
                if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                    throw IllegalArgumentException("Write-with-response not supported")
                }
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("Bluetooth permission revoked")
                }
                source.writeCharacteristic(
                    target,
                    bytes.copyOf(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            },
            decode = {  }
        )
    }

    /**
     * Enables notifications or indications through the CCCD descriptor.
     *
     * @param attribute Characteristic supporting notify or indicate.
     * @return Success when the descriptor write callback confirms the request.
     */
    override suspend fun subscribe(attribute: GattAttribute): Result<Unit> {
        if (!attribute.notifiable) {
            return Result.failure(IllegalArgumentException("Notifications unavailable"))
        }
        return transact(
            attribute,
            Operation.SUBSCRIBE,
            start = { source, target ->
                val descriptor = checkNotNull(target.getDescriptor(cccdUuid)) {
                    "CCCD descriptor missing"
                }
                val flag = when {
                    target.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    target.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else -> throw IllegalArgumentException("Notify/indicate not supported")
                }
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("Bluetooth permission revoked")
                }
                check(source.setCharacteristicNotification(target, true)) {
                    "Cannot enable characteristic notifications"
                }
                source.writeDescriptor(descriptor, flag) == BluetoothStatusCodes.SUCCESS
            },
            decode = {  }
        )
    }

    /** Releases GATT resources even if CONNECT permission has been revoked. */
    private fun release(source: BluetoothGatt) {
        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                source.disconnect()
            }
        } catch (_: SecurityException) {
            // The user may revoke permission between check and disconnect.
        } finally {
            source.close()
        }
    }

    /**
     * Invalidates the active session, wakes a waiting caller and releases GATT.
     * If [session] is supplied, obsolete callbacks cannot close a newer connection.
     */
    private fun closeConnection(session: Long? = null, reason: String? = null) {
        val released = synchronized(guard) {
            if (session != null && generation != session) return
            generation++
            opening = false
            val oldGatt = gatt
            val oldPending = pending
            gatt = null
            pending = null
            val oldAddress = connectedAddress
            connectedAddress = null
            connectionState.value =
                if (reason != null && oldAddress != null) {
                    GattSnapshot(oldAddress, false, error = reason)
                } else {
                    null
                }
            oldGatt to oldPending
        }
        released.second?.response?.complete(Reply(-1))
        released.first?.let(::release)
    }

    /** Disconnects the active BLE peripheral and clears the connection state. */
    override fun disconnect() = closeConnection()




}
































package com.app.musicbike.services // Ensure this package declaration matches file location

// Android & System Imports
import android.Manifest
import android.annotation.SuppressLint // Needed for suppressing permission warnings on BLE calls
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder // For binding mechanism
import android.os.Build // For checking Android version (SDK_INT)
import android.os.Handler // For delayed tasks (like stopping scan)
import android.os.IBinder // Interface for Binder
import android.os.Looper // For Handler on main thread
import android.os.ParcelUuid // For UUID filtering
import android.util.Log // For logging

// Bluetooth Specific Imports
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor // Needed for enabling notifications (CCCD)
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

// AndroidX Lifecycle Imports (for LiveData)
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

// Java Imports
import java.nio.ByteBuffer // Required for binary parsing
import java.nio.ByteOrder // Required for specifying endianness
import java.util.UUID

/**
 * A background Service to manage Bluetooth Low Energy (BLE) communication.
 * Handles scanning for devices, connecting via GATT, discovering services/characteristics,
 * and communicating status/data back to the UI using LiveData.
 */
@SuppressLint("MissingPermission") // Suppress warnings: Permissions checked in UI before calling methods
class BleService : Service() {

    // --- Constants ---
    companion object {
        private const val SCAN_PERIOD: Long = 10000 // Stops scanning after 10 seconds.
        private val TAG = "BleService" // Tag for Logcat messages
        // Standard UUID for the Client Characteristic Configuration Descriptor (CCCD)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Service & Characteristic UUIDs (ensure these match your ESP32 definitions)
        private val MUSIC_BIKE_SERVICE_UUID: UUID = UUID.fromString("0fb899fa-2b3a-4e11-911d-4fa05d130dc1")
        private val SPEED_CHARACTERISTIC_UUID: UUID = UUID.fromString("a635fed5-9a19-4e31-8091-84d020481329") // Format: float (4 bytes)
        private val PITCH_CHARACTERISTIC_UUID: UUID = UUID.fromString("726c4b96-bc56-47d2-95a1-a6c49cce3a1f") // Format: float (4 bytes)
        private val ROLL_CHARACTERISTIC_UUID: UUID = UUID.fromString("a1e929e3-5a2e-4418-806a-c50ab877d126") // Format: float (4 bytes)
        private val YAW_CHARACTERISTIC_UUID: UUID = UUID.fromString("cd6fc0f8-089a-490e-8e36-74af84977c7b") // Format: float (4 bytes)
        private val GFORCE_CHARACTERISTIC_UUID: UUID = UUID.fromString("a6210f30-654f-32ea-9e37-432a639fb38e") // Format: float (4 bytes)
        private val EVENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("26205d71-58d1-45e6-9ad1-1931cd7343c3") // Format: uint8_t (0=NONE, 1=JUMP, 2=DROP)
        private val IMU_DIRECTION_CHARACTERISTIC_UUID: UUID = UUID.fromString("ceb04cf6-0555-4243-a27b-c85986ab4bd7") // Format: uint8_t (0=Rev, 1=Fwd)
        private val HALL_DIRECTION_CHARACTERISTIC_UUID: UUID = UUID.fromString("f231de63-475c-463d-9b3f-f338d7458bb9") // Format: uint8_t (0=Rev, 1=Fwd)
        private val IMU_SPEED_STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("738f5e54-5479-4941-ae13-caf4a9b07b2e") // Format: uint8_t (0=Stop/Slow, 1=Med, 2=Fast)
        private val ACCELEROMETER_ZERO_CHARACTERISTIC_UUID: UUID = UUID.fromString("a29ff0d6-5bf9-4878-83f0-9f66a7e35a15") // Format: uint8_t (any non-zero value triggers zero)
    }

    // --- Bluetooth & State Variables ---
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val bluetoothLeScanner by lazy { bluetoothAdapter?.bluetoothLeScanner } // Use lazy initialization
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val foundDevices = mutableMapOf<String, BluetoothDevice>()
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionAttemptAddress: String? = null
    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    @Volatile // Ensure visibility across threads for the flag
    private var isProcessingQueue = false


    // --- LiveData for UI Communication ---
    // Private MutableLiveData for internal updates
    private val _connectionStatus = MutableLiveData("Idle") // Use type inference
    private val _scanResults = MutableLiveData<List<BluetoothDevice>>(emptyList())
    private val _speed = MutableLiveData(0.0f) // Use type inference
    private val _pitch = MutableLiveData(0.0f)
    private val _roll = MutableLiveData(0.0f)
    private val _yaw = MutableLiveData(0.0f)
    private val _lastEvent = MutableLiveData("NONE") // Keep as String for simpler UI update
    private val _imuDirection = MutableLiveData(1) // 1=Fwd, 0=Rev
    private val _hallDirection = MutableLiveData(1) // 1=Fwd, 0=Rev
    private val _imuSpeedState = MutableLiveData(0) // 0=Stop/Slow, 1=Med, 2=Fast
    private val _gForce = MutableLiveData(0.0f)

    // Add event timeout handler
    private val eventTimeoutHandler = Handler(Looper.getMainLooper())
    private val EVENT_TIMEOUT_MS = 3000L // 3 seconds timeout
    private val resetEventRunnable = Runnable {
        _lastEvent.postValue("NONE")
    }

    // Public immutable LiveData for external observation
    val connectionStatus: LiveData<String> get() = _connectionStatus
    val scanResults: LiveData<List<BluetoothDevice>> get() = _scanResults
    val speed: LiveData<Float> get() = _speed
    val pitch: LiveData<Float> get() = _pitch
    val roll: LiveData<Float> get() = _roll
    val yaw: LiveData<Float> get() = _yaw
    val lastEvent: LiveData<String> get() = _lastEvent
    val imuDirection: LiveData<Int> get() = _imuDirection
    val hallDirection: LiveData<Int> get() = _hallDirection
    val imuSpeedState: LiveData<Int> get() = _imuSpeedState
    val gForce: LiveData<Float> get() = _gForce
    // --- End LiveData ---

    // --- Binder ---
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }
    // --- End Binder ---

    // --- Service Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service instance created.")
        if (!initializeBluetooth()) {
            // Handle Bluetooth initialization failure (e.g., stop service, notify UI)
            Log.e(TAG, "onCreate: Failed to initialize Bluetooth. Stopping service.")
            stopSelf() // Example action
        }
    }

    private fun initializeBluetooth(): Boolean {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: run {
                Log.e(TAG, "initializeBluetooth: Unable to initialize BluetoothManager.")
                return false
            }
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "initializeBluetooth: Bluetooth not supported on this device.")
            return false
        }
        return true
    }


    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind: Service binding requested.")
        // Re-check adapter validity in case BT was turned off/on
        if (bluetoothAdapter == null) {
            initializeBluetooth()
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: Service unbound.")
        // Consider closing GATT if the service should stop when unbound
        // closeGatt()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Service destroyed.")
        // Clean up resources
        handler.removeCallbacksAndMessages(null) // Clean up handler callbacks
        stopScan()
        closeGatt()
    }
    // --- End Service Lifecycle Methods ---

    // --- Permission Check Helpers ---
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasScanPermission(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION) // Location needed pre-S for scan results
        }
        val hasPermissions = requiredPermissions.all { hasPermission(it) }
        // Log detailed permission status if needed
        // if (!hasPermissions) Log.w(TAG, "Missing scan permissions: ${requiredPermissions.filterNot { hasPermission(it) }}")
        return hasPermissions
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true // Not needed before Android S
        }
    }
    // --- End Permission Check Helpers ---

    // --- BLE Scan Operations ---
    fun startScan() {
        Log.d(TAG, "startScan requested.")
        if (!hasScanPermission()) {
            Log.e(TAG, "startScan: Scan permissions missing.")
            _connectionStatus.postValue("Error: Permissions Missing")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) { // Safer check for null adapter and enabled state
            Log.e(TAG, "startScan: Bluetooth not enabled or adapter unavailable.")
            _connectionStatus.postValue("Error: Bluetooth not enabled")
            return
        }
        if (scanning) {
            Log.d(TAG, "startScan: Scan already in progress.")
            return
        }
        Log.d(TAG, "startScan: Starting BLE scan...")

        scanning = true
        foundDevices.clear()
        _scanResults.postValue(emptyList())
        _connectionStatus.postValue("Scanning for Music Bike...")

        // Stop scanning after SCAN_PERIOD milliseconds
        handler.removeCallbacksAndMessages(null) // Remove any existing timeout callbacks first
        handler.postDelayed({ if (scanning) stopScan() }, SCAN_PERIOD)

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MUSIC_BIKE_SERVICE_UUID))
            .build()
        val scanFilters: List<ScanFilter> = listOf(scanFilter)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(scanFilters, scanSettings, leScanCallback)
                ?: Log.e(TAG, "startScan: bluetoothLeScanner is null, cannot start scan.")
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: SecurityException.", e)
            _connectionStatus.postValue("Error: Scan Permission Denied")
            scanning = false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startScan: IllegalStateException.", e)
            _connectionStatus.postValue("Error: Bluetooth Off?")
            scanning = false
        }
    }

    fun stopScan() {
        if (!scanning) {
            // Log.d(TAG, "stopScan: Scan not active.") // Optional log
            return
        }
        Log.d(TAG, "stopScan: Stopping BLE Scan...")
        scanning = false
        if (_connectionStatus.value == "Scanning for Music Bike...") {
            _connectionStatus.postValue("Scan Finished")
        }

        // Check permission again before stopping (required on API 31+)
        if (!hasScanPermission()) { // Use the combined check
            Log.e(TAG, "stopScan: Missing required permissions to stop scan.")
            _connectionStatus.postValue("Error: Scan Stop Permission Missing")
            return // Avoid calling stopScan if permission is missing
        }

        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
                ?: Log.e(TAG, "stopScan: bluetoothLeScanner is null, cannot stop scan.")
        } catch (e: SecurityException) {
            Log.e(TAG, "stopScan: SecurityException.", e)
            _connectionStatus.postValue("Error: Scan Permission Denied")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "stopScan: IllegalStateException.", e)
            _connectionStatus.postValue("Error: Bluetooth Off?")
        } finally {
            handler.removeCallbacksAndMessages(null) // Ensure timeout is removed
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return // Exit if result or device is null
            // Minimal permission check here - relies on startScan checks
            // More robust check could be added if needed, but avoid excessive checks
            try {
                val deviceAddress = device.address
                if (!foundDevices.containsKey(deviceAddress)) {
                    // Getting name requires connect permission on S+
                    val deviceName = try { device.name ?: "Unknown Device" } catch (se: SecurityException) { "No Permission" }
                    Log.i(TAG, "Device found: $deviceName ($deviceAddress)")
                    foundDevices[deviceAddress] = device
                    _scanResults.postValue(foundDevices.values.toList())
                }
            } catch (e: Exception) { // Catch broader exceptions during device access
                Log.e(TAG, "onScanResult: Error processing scan result for ${device.address}", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: BLE Scan Failed with error code: $errorCode")
            scanning = false
            _connectionStatus.postValue("Error: Scan Failed ($errorCode)")
        }
    }
    // --- End BLE Scan Callback ---

    // --- GATT Connection Operations ---
    fun connect(address: String): Boolean {
        Log.d(TAG, "connect: Attempting connection to address: $address")
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "connect: Bluetooth not enabled or adapter unavailable.")
            _connectionStatus.postValue("Error: Bluetooth not enabled")
            return false
        }
        if (!hasConnectPermission()) {
            Log.e(TAG, "connect: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.postValue("Error: Connect Permission Missing")
            return false
        }

        // Disconnect from any existing different device
        bluetoothGatt?.device?.address?.let { existingAddress ->
            if (existingAddress != address) {
                Log.w(TAG, "connect: Disconnecting from $existingAddress before connecting to $address.")
                closeGatt() // Close existing connection cleanly
            } else {
                Log.w(TAG, "connect: Already connected or attempting connection to $address.")
                return false // Already handling this address
            }
        }

        val device: BluetoothDevice = try {
            bluetoothAdapter!!.getRemoteDevice(address) // Adapter non-null checked above
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "connect: Invalid Bluetooth address format: $address", e)
            _connectionStatus.postValue("Error: Invalid address")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "connect: Failed to get remote device $address", e)
            _connectionStatus.postValue("Error: Device retrieval failed")
            return false
        }

        stopScan() // Stop scanning before connecting

        Log.d(TAG, "Trying to create a new GATT connection to $address")
        _connectionStatus.postValue("Connecting to ${try { device.name } catch (se: SecurityException) { address }}...")
        connectionAttemptAddress = address

        bluetoothGatt = try {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect: SecurityException on connectGatt.", e)
            _connectionStatus.postValue("Error: Connect Permission Denied")
            connectionAttemptAddress = null
            null
        } catch (e: Exception) {
            Log.e(TAG, "connect: Exception on connectGatt.", e)
            _connectionStatus.postValue("Error: Connection Failed")
            connectionAttemptAddress = null
            null
        }

        if (bluetoothGatt == null) {
            Log.e(TAG, "connect: connectGatt failed to return a BluetoothGatt instance.")
            // Status likely set in catch blocks
            connectionAttemptAddress = null
            return false
        }

        Log.d(TAG, "connect: connectGatt call initiated.")
        return true
    }

    fun disconnect() {
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "disconnect: Not connected (bluetoothGatt is null).")
            // Set status only if it wasn't already reflecting disconnected state
            if (_connectionStatus.value != "Idle" && _connectionStatus.value != "Disconnected" && !_connectionStatus.value.orEmpty().startsWith("Error:")) {
                _connectionStatus.postValue("Disconnected")
            }
            return
        }
        val address = gatt.device?.address ?: "unknown address"
        Log.d(TAG, "disconnect: Requesting disconnection from $address")

        if (!hasConnectPermission()) {
            Log.e(TAG, "disconnect: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.postValue("Error: Disconnect Permission Missing")
            return // Cannot legally disconnect
        }

        try {
            gatt.disconnect()
            Log.d(TAG, "disconnect: disconnect() called for $address.")
            // Status update should happen in onConnectionStateChange
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect: SecurityException.", e)
            _connectionStatus.postValue("Error: Disconnect Permission Denied")
        } catch (e: Exception) {
            Log.e(TAG, "disconnect: Exception.", e)
            _connectionStatus.postValue("Error: Disconnect Failed")
        }
    }

    private fun closeGatt() {
        val gatt = bluetoothGatt ?: return
        val address = gatt.device?.address ?: "unknown address"
        Log.d(TAG, "closeGatt: Closing GATT client for $address")

        if (!hasConnectPermission()) {
            Log.e(TAG, "closeGatt: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.postValue("Error: Disconnect Permission Missing")
            // Still clear the reference locally, even if close() can't be called
            bluetoothGatt = null
            return
        }

        try {
            gatt.close()
            Log.d(TAG, "closeGatt: GATT client closed for $address.")
        } catch (e: SecurityException) {
            Log.e(TAG, "closeGatt: SecurityException.", e)
            _connectionStatus.postValue("Error: Disconnect Permission Denied")
        } catch (e: Exception) {
            Log.e(TAG, "closeGatt: Exception.", e)
            _connectionStatus.postValue("Error: GATT Close Failed")
        } finally {
            bluetoothGatt = null // Ensure reference is cleared
            // Set status only if it wasn't already reflecting disconnected state
            if (_connectionStatus.value != "Idle" && _connectionStatus.value != "Disconnected" && !_connectionStatus.value.orEmpty().startsWith("Error:")) {
                _connectionStatus.postValue("Disconnected")
            }
        }
    }
    // --- End GATT Connection Operations ---


    // --- GATT Callback Implementation ---
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address
            Log.d(TAG, "onConnectionStateChange: Address=$deviceAddress, Status=$status, NewState=$newState")

            // Check if this callback is for the device we intended to connect to
            // If connectionAttemptAddress is null, we might be processing an unexpected disconnect
            if (connectionAttemptAddress != null && deviceAddress != connectionAttemptAddress) {
                Log.w(TAG, "onConnectionStateChange: Ignoring callback for unexpected address $deviceAddress (expecting $connectionAttemptAddress)")
                // Consider closing this unexpected gatt instance if it's not null
                // gatt?.close()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "onConnectionStateChange: Successfully connected to $deviceAddress")
                    _connectionStatus.postValue("Connected")
                    bluetoothGatt = gatt // Assign the valid gatt object
                    connectionAttemptAddress = null // Connection successful, clear pending address

                    // Initiate service discovery slightly delayed on the main thread
                    // Sometimes helps stability after connection.
                    Handler(Looper.getMainLooper()).postDelayed({
                        bluetoothGatt?.let { currentGatt -> // Use the stored gatt object
                            if (hasConnectPermission()) { // Check permission right before discovery
                                Log.d(TAG, "onConnectionStateChange: Initiating service discovery...")
                                val discoveryInitiated = currentGatt.discoverServices()
                                if (discoveryInitiated) {
                                    _connectionStatus.postValue("Discovering Services...")
                                } else {
                                    Log.e(TAG, "onConnectionStateChange: discoverServices() failed to initiate.")
                                    _connectionStatus.postValue("Error: Discovery Failed Start")
                                    disconnect()
                                }
                            } else {
                                Log.e(TAG, "onConnectionStateChange: Missing Connect permission for service discovery.")
                                _connectionStatus.postValue("Error: Discovery Permission")
                                disconnect()
                            }
                        } ?: Log.e(TAG, "onConnectionStateChange: bluetoothGatt became null before discovery.")
                    }, 100) // Small delay e.g., 100ms

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "onConnectionStateChange: Disconnected from $deviceAddress (Status: GATT_SUCCESS)")
                    _connectionStatus.postValue("Disconnected")
                    closeGatt() // Clean up resources
                    connectionAttemptAddress = null // Clear any pending attempt
                }
            } else {
                // Handle connection errors (status != GATT_SUCCESS)
                Log.e(TAG, "onConnectionStateChange: GATT Error! Status: $status, Addr: $deviceAddress, NewState: $newState")
                _connectionStatus.postValue("Error: Connection Failed ($status)")
                closeGatt() // Clean up resources on error
                connectionAttemptAddress = null // Clear any pending attempt
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val address = gatt?.device?.address
                Log.i(TAG, "onServicesDiscovered: Services discovered successfully for $address")
                _connectionStatus.postValue("Services Discovered")

                val service = gatt?.getService(MUSIC_BIKE_SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "onServicesDiscovered: Music Bike Service UUID $MUSIC_BIKE_SERVICE_UUID not found!")
                    _connectionStatus.postValue("Error: Service Not Found")
                    disconnect() // Can't proceed without the service
                    return
                }

                Log.d(TAG, "onServicesDiscovered: Queueing characteristics for notifications...")
                notificationQueue.clear()
                isProcessingQueue = false // Reset flag before starting

                // Add characteristics to the queue (consider checking for null)
                service.getCharacteristic(SPEED_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(PITCH_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(ROLL_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(YAW_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(EVENT_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(IMU_DIRECTION_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(HALL_DIRECTION_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(IMU_SPEED_STATE_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }
                service.getCharacteristic(GFORCE_CHARACTERISTIC_UUID)?.also { notificationQueue.addLast(it) }

                // Verify accelerometer zero characteristic exists (but don't add to notification queue since it's write-only)
                service.getCharacteristic(ACCELEROMETER_ZERO_CHARACTERISTIC_UUID)?.also {
                    Log.d(TAG, "onServicesDiscovered: Found accelerometer zero characteristic")
                } ?: Log.w(TAG, "onServicesDiscovered: Accelerometer zero characteristic not found")

                if (notificationQueue.isNotEmpty()) {
                    Log.d(TAG, "onServicesDiscovered: Starting notification queue processing.")
                    processNotificationQueue()
                } else {
                    Log.w(TAG, "onServicesDiscovered: Notification queue is empty (no relevant characteristics found?).")
                    _connectionStatus.postValue("Ready (No Notifications)")
                }

            } else {
                Log.e(TAG, "onServicesDiscovered failed with status: $status")
                _connectionStatus.postValue("Error: Service Discovery Failed ($status)")
                disconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            // Log entry unconditionally to see if it fires for the last descriptor
            Log.d(TAG, "onDescriptorWrite ----> START <---- Char: ${descriptor?.characteristic?.uuid}, Status: $status")

            // Access the characteristic UUID *through* the descriptor object
            val characteristicUUID = descriptor?.characteristic?.uuid ?: "unknown characteristic" // Safe access

            if (descriptor?.uuid == CCCD_UUID) { // Check if it's the CCCD we wrote
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "onDescriptorWrite: CCCD write success for $characteristicUUID")
                } else {
                    // Log the specific GATT error status for better debugging
                    Log.e(TAG, "onDescriptorWrite: CCCD write FAILED for $characteristicUUID, Status: $status")
                }
            } else {
                // Log if it's a different descriptor write callback
                Log.d(TAG, "onDescriptorWrite: Callback for non-CCCD descriptor ${descriptor?.uuid}, Status: $status")
            }

            // Crucial: Mark processing as finished *before* trying the next item
            isProcessingQueue = false
            // Process the next item in the queue (if any)
            processNotificationQueue()
            Log.d(TAG, "onDescriptorWrite ----> END <---- Char: $characteristicUUID")
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray // Use this signature
        ) {
            val uuid = characteristic.uuid
            // Optional: Log raw hex for comprehensive debugging
            // val hexString = value.joinToString("") { "%02X".format(it) }
            // Log.d(TAG, "onCharacteristicChanged UUID: $uuid, Size: ${value.size}, Raw Hex: $hexString")

            try { // Wrap parsing in try-catch for robustness
                when (uuid) {
                    SPEED_CHARACTERISTIC_UUID -> {
                        if (value.size == 4) { // Expect 4 bytes for a float
                            // Wrap bytes, set order (LITTLE_ENDIAN is common for ESP32), get float
                            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                            val speedValue = buffer.float
                            Log.i(TAG, "Received Speed (Binary): $speedValue")
                            _speed.postValue(speedValue)
                        } else {
                            Log.w(TAG, "Received incorrect byte count for Speed: ${value.size} (expected 4)")
                        }
                    }
                    PITCH_CHARACTERISTIC_UUID -> {
                        if (value.size == 4) {
                            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                            val pitchValue = buffer.float
                            Log.i(TAG, "Received Pitch (Binary): $pitchValue")
                            _pitch.postValue(pitchValue)
                        } else {
                            Log.w(TAG, "Received incorrect byte count for Pitch: ${value.size} (expected 4)")
                        }
                    }
                    ROLL_CHARACTERISTIC_UUID -> {
                        if (value.size == 4) {
                            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                            val rollValue = buffer.float
                            Log.i(TAG, "Received Roll (Binary): $rollValue")
                            _roll.postValue(rollValue)
                        } else {
                            Log.w(TAG, "Received incorrect byte count for Roll: ${value.size} (expected 4)")
                        }
                    }
                    YAW_CHARACTERISTIC_UUID -> {
                        if (value.size == 4) {
                            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                            val yawValue = buffer.float
                            Log.i(TAG, "Received Yaw (Binary): $yawValue")
                            _yaw.postValue(yawValue)
                        } else {
                            Log.w(TAG, "Received incorrect byte count for Yaw: ${value.size} (expected 4)")
                        }
                    }
                    GFORCE_CHARACTERISTIC_UUID -> {
                        if (value.size == 4) {
                            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                            val gForceValue = buffer.float
                            Log.i(TAG, "Received G-Force (Binary): $gForceValue")
                            _gForce.postValue(gForceValue)
                        } else {
                            Log.w(TAG, "Received incorrect byte count for G-Force: ${value.size} (expected 4)")
                        }
                    }
                    EVENT_CHARACTERISTIC_UUID -> {
                        if (value.isNotEmpty()) { // Expect 1 byte (uint8_t)
                            val eventCode = value[0].toInt() and 0xFF // Get byte as unsigned Int (0-255)
                            val eventString = when(eventCode) {
                                1 -> "JUMP"
                                2 -> "DROP"
                                // case 0 -> "NONE" // ESP32 doesn't send 0 currently
                                else -> null // Ignore unknown or '0' codes if ESP doesn't send NONE
                            }
                            // Only update LiveData if it's a recognized event
                            eventString?.let {
                                Log.i(TAG, "Received Event Code (Binary): $eventCode ($it)")
                                _lastEvent.postValue(it)
                            } ?: Log.d(TAG, "Received non-event code for Event: $eventCode")

                        } else {
                            Log.w(TAG, "Received empty data for Event")
                        }
                    }
                    IMU_DIRECTION_CHARACTERISTIC_UUID -> {
                        if (value.isNotEmpty()) { // Expect 1 byte (uint8_t)
                            val dirCode = value[0].toInt() and 0xFF // 0 or 1
                            Log.i(TAG, "Received IMU Direction Code (Binary): $dirCode")
                            _imuDirection.postValue(dirCode)
                        } else {
                            Log.w(TAG, "Received empty data for IMU Direction")
                        }
                    }
                    HALL_DIRECTION_CHARACTERISTIC_UUID -> {
                        if (value.isNotEmpty()) { // Expect 1 byte (uint8_t)
                            val dirCode = value[0].toInt() and 0xFF // 0 or 1
                            Log.i(TAG, "Received Hall Direction Code (Binary): $dirCode")
                            _hallDirection.postValue(dirCode)
                        } else {
                            Log.w(TAG, "Received empty data for Hall Direction")
                        }
                    }
                    IMU_SPEED_STATE_CHARACTERISTIC_UUID -> {
                        if (value.isNotEmpty()) { // Expect 1 byte (uint8_t)
                            val stateCode = value[0].toInt() and 0xFF // 0, 1, or 2
                            Log.i(TAG, "Received IMU Speed State Code (Binary): $stateCode")
                            _imuSpeedState.postValue(stateCode)
                        } else {
                            Log.w(TAG, "Received empty data for IMU Speed State")
                        }
                    }
                    else -> {
                        Log.w(TAG, "Received data from unhandled characteristic: $uuid")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing characteristic data for $uuid", e)
                // Optionally post an error state to the UI
            }
        }
        // ============================================================
        // === END MODIFIED onCharacteristicChanged                 ===
        // ============================================================

    } // --- End gattCallback Implementation ---


    // --- Helper Function to Enable Notifications (Queue-based) ---
    /**
     * Processes the next characteristic in the notification queue.
     * Enables notifications sequentially, waiting for the previous operation to complete.
     */
    private fun processNotificationQueue() {
        // Check GATT validity *before* trying to access the queue
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e(TAG, "processNotificationQueue: GATT is null! Cannot process queue.")
            synchronized(notificationQueue) {
                isProcessingQueue = false
            }
            return
        }

        // Use synchronized block for thread-safe queue access and flag checking
        var characteristicToProcess: BluetoothGattCharacteristic? = null // Initialize to null <--- FIX HERE
        var isQueueEmpty = false

        synchronized(notificationQueue) {
            if (isProcessingQueue) {
                Log.d(TAG, "processNotificationQueue: Exiting - Already processing an item.")
                return // Don't start a new operation if one is in progress
            }
            if (notificationQueue.isEmpty()) {
                Log.d(TAG, "processNotificationQueue: Exiting - Queue is empty.")
                isQueueEmpty = true // Mark that the queue is empty
            } else {
                // Assign inside sync block (compiler knows it might be null from init)
                characteristicToProcess = notificationQueue.removeFirst()
                isProcessingQueue = true // Mark as busy ONLY when starting a new item
                Log.d(TAG, "processNotificationQueue: Processing ${characteristicToProcess?.uuid}")
            }
        } // End synchronized block

        // Handle the case where the queue was empty
        if (isQueueEmpty) {
            // Check flag again outside sync block
            if (!isProcessingQueue) {
                Log.i(TAG, "processNotificationQueue: Queue confirmed empty. Setting status Ready.")
                _connectionStatus.postValue("Ready")
            }
            return
        }

        // Now the compiler knows characteristicToProcess has an initial value (null)
        // Safely unwrap using elvis operator
        val characteristic = characteristicToProcess ?: run {
            Log.w(TAG, "processNotificationQueue: Dequeued characteristic was unexpectedly null.")
            isProcessingQueue = false
            processNotificationQueue() // Try next item
            return
        }


        // --- Process the dequeued characteristic ---
        // Required checks before proceeding
        if (!hasConnectPermission()) {
            Log.e(TAG, "processNotificationQueue: Missing BLUETOOTH_CONNECT permission for ${characteristic.uuid}.")
            isProcessingQueue = false // Reset flag, stop queue processing due to permission error
            return
        }

        // Check if characteristic supports NOTIFY property
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            Log.w(TAG, "processNotificationQueue: Characteristic ${characteristic.uuid} does not support NOTIFY. Skipping.")
            isProcessingQueue = false // Reset flag before recursion
            processNotificationQueue() // Process next item immediately
            return
        }

        // 1. Enable notification locally on the Android device
        if (!gatt.setCharacteristicNotification(characteristic, true)) { // Check return value
            Log.e(TAG, "processNotificationQueue: setCharacteristicNotification(true) failed for ${characteristic.uuid}")
            isProcessingQueue = false // Reset flag before recursion
            processNotificationQueue() // Try next item
            return
        }

        // 2. Find the CCCD descriptor
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "processNotificationQueue: CCCD descriptor not found for ${characteristic.uuid}. Cannot enable.")
            isProcessingQueue = false // Reset flag before recursion
            processNotificationQueue() // Try next item
            return
        }

        // 3. Write to the CCCD on the peripheral device using helper
        descriptor.let { nonNullDescriptor ->
            Log.d(TAG, "processNotificationQueue: Writing ENABLE_NOTIFICATION_VALUE to CCCD for ${characteristic.uuid}")
            val writeInitiated = writeCccd(gatt, nonNullDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

            if (!writeInitiated) {
                Log.e(TAG, "processNotificationQueue: Failed to initiate write to CCCD for ${characteristic.uuid}")
                // If write initiation failed, onDescriptorWrite might not be called.
                // Reset flag and try the next item to avoid getting stuck.
                isProcessingQueue = false
                processNotificationQueue()
            } else {
                Log.d(TAG, "processNotificationQueue: CCCD write initiated for ${characteristic.uuid}. Waiting for onDescriptorWrite callback.")
                // Success: onDescriptorWrite will reset flag and call processNotificationQueue again.
            }
        }
        // Note: The case where descriptor was initially null is handled by the 'return' above.
    }

    // Helper function to handle descriptor writes across Android versions
    /**
     * Helper function to write to a CCCD, handling Android version differences.
     * Returns true if the write operation was *initiated* successfully, false otherwise.
     * Actual success/failure is confirmed in onDescriptorWrite callback.
     */
    private fun writeCccd(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        if (!hasConnectPermission()) { // Check permission before write
            Log.e(TAG, "writeCccd: Missing Connect permission for ${descriptor.characteristic?.uuid}")
            return false
        }
        return try {
            var initiated = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // New method returns GATT status code directly for initiation attempt
                val status = gatt.writeDescriptor(descriptor, value)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    initiated = true
                } else {
                    Log.e(TAG, "writeCccd (API 33+): writeDescriptor initiation failed with status $status for ${descriptor.characteristic?.uuid}")
                    initiated = false
                }
            } else {
                // Deprecated method
                @Suppress("DEPRECATION") // Suppress warning for this assignment
                descriptor.value = value
                @Suppress("DEPRECATION") // Suppress warning for this function call
                initiated = gatt.writeDescriptor(descriptor) // Returns true if operation was initiated
            }
            initiated // Return whether initiation seemed successful
        } catch (e: SecurityException) {
            Log.e(TAG, "writeCccd: SecurityException for ${descriptor.characteristic?.uuid}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "writeCccd: Exception for ${descriptor.characteristic?.uuid}", e)
            false
        }
    }

    // Add new function to zero the accelerometer
    fun zeroAccelerometer(): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "zeroAccelerometer: Not connected (bluetoothGatt is null)")
            return false
        }

        if (!hasConnectPermission()) {
            Log.e(TAG, "zeroAccelerometer: Missing BLUETOOTH_CONNECT permission")
            return false
        }

        val service = gatt.getService(MUSIC_BIKE_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(ACCELEROMETER_ZERO_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            Log.e(TAG, "zeroAccelerometer: Characteristic not found")
            return false
        }

        try {
            // Write a non-zero value (1) to trigger the zero reset
            val value = byteArrayOf(1)
            val success = characteristic.setValue(value)
            if (!success) {
                Log.e(TAG, "zeroAccelerometer: Failed to set value")
                return false
            }

            // Set write type for Write With Response (handshake for zeroing)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val writeSuccess = gatt.writeCharacteristic(characteristic)
            if (writeSuccess) {
                Log.i(TAG, "zeroAccelerometer: Write request sent successfully")
                return true
            } else {
                Log.e(TAG, "zeroAccelerometer: Write request failed")
                return false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "zeroAccelerometer: SecurityException", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "zeroAccelerometer: Exception", e)
            return false
        }
    }

} // End of BleService class
package com.app.musicbike.services

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter // Import ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings // Import ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build // Import Build for SDK version checks
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid // Import ParcelUuid for filtering
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID // Import UUID

// TODO: Implement GATT connection callback and data handling

class BleService : Service() {

    // --- Class Members ---
    private val TAG = "BleService" // TAG for logging
    private val binder = LocalBinder()
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    // Lazy initialization for the BLE scanner
    private val bluetoothLeScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private var scanning = false // Flag to track if scanning is active
    private val handler = Handler(Looper.getMainLooper()) // Handler for stopping scan after a period
    private val foundDevices = mutableMapOf<String, BluetoothDevice>() // Map to store unique devices found

    // --- LiveData for UI Communication ---
    // Private MutableLiveData that this Service modifies
    private val _connectionStatus = MutableLiveData<String>("Idle") // Holds current status (Idle, Scanning, Connecting, Connected, Error, etc.)
    private val _scanResults = MutableLiveData<List<BluetoothDevice>>(emptyList()) // Holds the list of filtered devices found

    // Public immutable LiveData that UI (Fragments/Activities) observes
    val connectionStatus: LiveData<String> get() = _connectionStatus
    val scanResults: LiveData<List<BluetoothDevice>> get() = _scanResults
    // --- End LiveData ---

    // This UUID *must* exactly match the Service UUID being advertised by your ESP32 device.
    // Used a Version 4 UUID generator online.
    private val MUSIC_BIKE_SERVICE_UUID: UUID = UUID.fromString("0fb899fa-2b3a-4e11-911d-4fa05d130dc1")
    // --- End UUID Definition ---

    // --- Service Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service instance created.")
        // Initialize Bluetooth Manager and Adapter
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind: Service binding requested.")
        // Ensure adapter is available
        if (bluetoothAdapter == null) {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            Log.d(TAG, "onBind: BluetoothAdapter re-initialized.")
        }
        Log.d(TAG, "onBind: Returning binder.")
        return binder // Return the binder instance to the client (MainActivity)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: Service unbound.")
        // Return true here if you want onRebind to be called when a new client binds
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Service destroyed.")
        // Clean up resources: stop scan, disconnect GATT etc.
        stopScan() // Ensure scan is stopped
        // TODO: Add GATT disconnect logic here if a connection exists
    }
    // --- End Service Lifecycle Methods ---

    // Binder class for clients (MainActivity) to interact with the Service
    inner class LocalBinder : Binder() {
        // Returns the instance of BleService so clients can call its public methods
        fun getService(): BleService = this@BleService
    }

    // --- Permission Check Helpers ---
    // Checks if the necessary scan permissions are granted (adapts for Android 12+)
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Location permission was required for scanning pre-Android 12
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    // Checks if location permission is granted (needed for scanning pre-Android 12, recommended always)
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    // Checks if connect permission is granted (needed for connect and getting name/address on Android 12+)
    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Connect permission did not exist before Android 12
        }
    }
    // --- End Permission Check Helpers ---


    // --- BLE Operations ---

    /**
     * Starts scanning for BLE devices advertising the specific MUSIC_BIKE_SERVICE_UUID.
     * Permissions MUST be granted by the UI layer before calling this function.
     */
    fun startScan() {
        // Double-check permissions (although UI should check first)
        if (!hasScanPermission() || !hasLocationPermission()) {
            Log.e(TAG, "startScan: Missing required Bluetooth/Location permissions.")
            _connectionStatus.postValue("Error: Permissions Missing")
            return
        }
        // Check if Bluetooth adapter is available and enabled
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "startScan: Bluetooth is not enabled or not available.")
            _connectionStatus.postValue("Error: Bluetooth not enabled")
            return
        }
        // Don't start a new scan if one is already running
        if (scanning) {
            Log.d(TAG, "startScan: Scan already in progress.")
            return
        }

        Log.d(TAG, "Starting BLE Scan for Service UUID: $MUSIC_BIKE_SERVICE_UUID")
        scanning = true // Set scanning flag
        foundDevices.clear() // Clear results from any previous scan
        _scanResults.postValue(emptyList()) // Notify UI that the list is cleared
        _connectionStatus.postValue("Scanning for Music Bike...") // Update status LiveData

        // Stops scanning after a pre-defined scan period (SCAN_PERIOD).
        handler.postDelayed({
            if (scanning) { // Only stop if it's still supposed to be scanning
                Log.d(TAG,"Scan timed out after $SCAN_PERIOD ms")
                stopScan()
            }
        }, SCAN_PERIOD)

        // --- Create ScanFilter to find only our specific devices ---
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MUSIC_BIKE_SERVICE_UUID)) // Filter by our unique Service UUID
            .build()
        val scanFilters: List<ScanFilter> = listOf(scanFilter) // Must be a List
        // --- End ScanFilter ---

        // --- Define ScanSettings ---
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Prioritize faster discovery
            // Other settings like .setMatchMode, .setCallbackType can be added here
            .build()
        // --- End ScanSettings ---

        try {
            // --- Start the actual scan using the BLE scanner ---
            // Pass the filters, settings, and the callback object
            bluetoothLeScanner?.startScan(scanFilters, scanSettings, leScanCallback)
            Log.d(TAG, "startScan: Scan initiated successfully.")
        } catch (e: SecurityException) {
            // This catch is mainly a fallback; permissions should be checked before calling.
            Log.e(TAG, "startScan: SecurityException. Check permissions.", e)
            _connectionStatus.postValue("Error: Scan Permission Denied")
            scanning = false // Reset scanning flag
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startScan: IllegalStateException. Bluetooth possibly off or scanner unavailable?", e)
            _connectionStatus.postValue("Error: Bluetooth Off?")
            scanning = false
        }
    }

    /**
     * Stops the ongoing BLE scan.
     */
    fun stopScan() {
        if (!scanning) {
            Log.d(TAG, "stopScan: Scan not active or already stopped.")
            return // Nothing to do if not scanning
        }
        Log.d(TAG, "Stopping BLE Scan...")
        scanning = false // Clear the scanning flag
        // Update status only if it was actively scanning
        if (_connectionStatus.value == "Scanning for Music Bike...") {
            _connectionStatus.postValue("Scan Finished") // Indicate scan stopped
        }
        try {
            // --- Stop the actual scan ---
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d(TAG, "stopScan: Scan stopped successfully.")
        } catch (e: SecurityException) {
            Log.e(TAG, "stopScan: SecurityException. Check permissions.", e)
            _connectionStatus.postValue("Error: Scan Permission Denied")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "stopScan: IllegalStateException. Bluetooth possibly off?", e)
            _connectionStatus.postValue("Error: Bluetooth Off?")
        }
        // Remove any pending timeout callbacks to stop the scan
        handler.removeCallbacksAndMessages(null)
    }


    // --- BLE Scan Callback Object ---
    // This object receives results from the BLE scanner
    private val leScanCallback = object : ScanCallback() {

        // Called when a device matching the filter is found
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device -> // Ensure result and device are not null
                // Re-check permissions before accessing device info (safer)
                if (!hasScanPermission() || !hasLocationPermission()) {
                    Log.w(TAG, "onScanResult: Missing permissions during callback.")
                    stopScan()
                    return
                }

                try {
                    val deviceAddress = device.address
                    // Add device to map if it's not already there (using address as unique key)
                    if (!foundDevices.containsKey(deviceAddress)) {
                        // Getting device name requires BLUETOOTH_CONNECT on API 31+
                        val deviceName = if (hasConnectPermission()) {
                            device.name ?: "Unknown Device" // Use "Unknown" if name is null
                        } else {
                            "Name requires Connect permission"
                        }

                        Log.i(TAG, "Device found: $deviceName ($deviceAddress)")
                        foundDevices[deviceAddress] = device // Add to our map
                        // Update the LiveData with the new list (must be done on main thread via postValue)
                        _scanResults.postValue(foundDevices.values.toList())
                    }
                } catch (e: SecurityException) {
                    // Catch potential permission errors when accessing device.name or device.address
                    Log.e(TAG, "onScanResult: SecurityException accessing device info. Check permissions.", e)
                    stopScan() // Stop scan if permissions fail mid-scan
                    _connectionStatus.postValue("Error: Permission Denied during scan")
                }
            }
        }

        // Called if scan fails to start
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Scan Failed with error code: $errorCode")
            scanning = false // Ensure scanning flag is reset
            _connectionStatus.postValue("Error: Scan Failed ($errorCode)")
        }

        // Optional: Handle batch results if needed (less common for immediate UI updates)
        override fun onBatchScanResults(results: List<ScanResult?>?) {
            super.onBatchScanResults(results)
            // Similar logic as onScanResult if you expect batch results based on ScanSettings
            Log.d(TAG, "Batch scan results received: ${results?.size}")
            // ... (process batch results similarly, checking permissions) ...
            // _scanResults.postValue(foundDevices.values.toList()) // Update after processing batch
        }
    }
    // --- End BLE Scan Callback ---

    // --- Connection Logic (Placeholder) ---
    /**
     * Initiates a connection to the specified device address.
     * TODO: Implement GATT connection and callback.
     */
    fun connect(address: String) {
        Log.d(TAG, "Connect function called for address: $address")
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "connect: Bluetooth not enabled.")
            _connectionStatus.postValue("Error: Bluetooth not enabled")
            return
        }
        // Check connect permission if required
        if (!hasConnectPermission()) {
            Log.e(TAG, "connect: Missing BLUETOOTH_CONNECT permission.")
            _connectionStatus.postValue("Error: Connect Permission Missing")
            return
        }

        _connectionStatus.postValue("Connecting to $address...")
        // Important: Stop scanning before attempting to connect
        if (scanning) {
            stopScan()
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(address) // Get BluetoothDevice object
            if (device == null) {
                Log.e(TAG, "connect: Device not found with address $address")
                _connectionStatus.postValue("Error: Device not found")
                return
            }
            // --- Actual connection attempt ---
            // TODO: Define a BluetoothGattCallback object to handle connection events
            // TODO: Call device.connectGatt(this, false, yourGattCallback)
            Log.w(TAG, "connect: GATT connection logic not implemented yet.") // Placeholder log
            _connectionStatus.postValue("Error: Connect not implemented") // Placeholder status

        } catch (e: SecurityException) {
            Log.e(TAG, "connect: SecurityException. Check BLUETOOTH_CONNECT permission.", e)
            _connectionStatus.postValue("Error: Connect Permission Denied")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "connect: Invalid Bluetooth address format: $address", e)
            _connectionStatus.postValue("Error: Invalid address")
        }
    }
    // --- End Connection Logic ---

    // TODO: Implement disconnect() function
    // TODO: Implement functions to read/write characteristics

    companion object {
        // Defines a duration for the scan in milliseconds.
        private const val SCAN_PERIOD: Long = 10000 // Stops scanning after 10 seconds.
    }
}

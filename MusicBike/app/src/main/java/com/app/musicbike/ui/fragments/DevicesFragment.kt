package com.app.musicbike.ui.fragments

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.musicbike.services.BleService
import com.app.musicbike.databinding.FragmentDevicesBinding
import com.app.musicbike.ui.activities.MainActivity
import com.app.musicbike.ui.adapter.DeviceListAdapter // Import the new adapter

class DevicesFragment : Fragment() {

    private val TAG = "DevicesFragment"
    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private var bleService: BleService? = null
    private var hasAttemptedObservationSetup = false

    // --- Declare the adapter ---
    private lateinit var deviceListAdapter: DeviceListAdapter
    private var selectedDeviceAddress: String? = null // To store the address of the selected device

    // --- Permission Handling (remains the same) ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                Log.d(TAG, "Permission ${it.key} granted: ${it.value}")
                if (!it.value) allGranted = false
            }
            if (allGranted) {
                Log.d(TAG, "All required permissions granted after request.")
                startBleScan()
            } else {
                Log.w(TAG, "Not all permissions were granted.")
                Toast.makeText(context, "Required permissions denied. Cannot scan.", Toast.LENGTH_LONG).show()
                binding.statusTextDevices.text = "Status: Permissions Denied"
            }
        }
    // --- End Permission Handling ---

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")
        setupRecyclerView() // Setup RecyclerView first
        setupButtonClickListeners()
        binding.statusTextDevices.text = "Status: Initializing..."
        // Try to get service immediately if activity already has it
        if ((activity as? MainActivity)?.isBleServiceConnected == true) {
            onServiceReady()
        }
    }

    fun onServiceReady() {
        Log.d(TAG, "onServiceReady called by Activity.")
        bleService = (activity as? MainActivity)?.getBleServiceInstance()
        if (bleService != null && !hasAttemptedObservationSetup) {
            Log.d(TAG, "Service is ready, setting up observers.")
            observeBleService()
            updateStatusFromService()
            hasAttemptedObservationSetup = true
        } else if (bleService == null) {
            Log.e(TAG, "onServiceReady called, but failed to get service instance!")
            binding.statusTextDevices.text = "Status: Error getting service"
        } else {
            Log.d(TAG, "onServiceReady called, but observers already set up.")
        }
    }

    private fun updateStatusFromService() {
        if (bleService != null) {
            val currentStatus = bleService?.connectionStatus?.value ?: "Idle"
            Log.d(TAG, "updateStatusFromService: Setting status to '$currentStatus'")
            binding.statusTextDevices.text = "Status: $currentStatus"
        } else {
            Log.w(TAG, "updateStatusFromService: Service is null")
            binding.statusTextDevices.text = "Status: Service not ready"
        }
    }


    private fun setupRecyclerView() {
        // --- Initialize and set the adapter ---
        deviceListAdapter = DeviceListAdapter { selectedDevice ->
            // This lambda is the clickListener passed to the adapter
            handleDeviceSelection(selectedDevice)
        }

        binding.deviceListDevices.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceListAdapter // Set the adapter here
        }
        Log.d(TAG, "RecyclerView setup complete, adapter set.")
        // --- End Adapter Setup ---
    }

    // --- Handle device selection ---
    private fun handleDeviceSelection(device: BluetoothDevice) {
        // Check permission before accessing address (good practice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Connect permission needed", Toast.LENGTH_SHORT).show()
            // Optionally request BLUETOOTH_CONNECT here if needed for connection later
            return
        }
        try {
            selectedDeviceAddress = device.address // Store the address
            val deviceName = device.name ?: "Unknown"
            Log.d(TAG, "Device selected: $deviceName ($selectedDeviceAddress)")
            binding.statusTextDevices.text = "Selected: $deviceName" // Update status
            binding.btnConnectDevices.isEnabled = true // Enable connect button
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting device info on selection", e)
            Toast.makeText(context, "Permission error on selection", Toast.LENGTH_SHORT).show()
            selectedDeviceAddress = null
            binding.btnConnectDevices.isEnabled = false
        }
    }
    // --- End Handle device selection ---

    private fun setupButtonClickListeners() {
        binding.btnStartScanDevices.setOnClickListener {
            Log.d(TAG, "Start Scan button clicked")
            selectedDeviceAddress = null // Clear selection on new scan
            binding.btnConnectDevices.isEnabled = false // Disable connect button
            checkAndRequestPermissions()
        }

        binding.btnConnectDevices.setOnClickListener {
            Log.d(TAG, "Connect button clicked")
            if (selectedDeviceAddress == null) {
                Toast.makeText(context, "Please select a device first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (bleService != null) {
                // TODO: Check BLUETOOTH_CONNECT permission if API >= 31 before connecting
                Log.d(TAG, "Calling bleService.connect() for $selectedDeviceAddress")
                bleService?.connect(selectedDeviceAddress!!) // Use the stored address
                binding.btnConnectDevices.isEnabled = false // Disable after clicking connect
                binding.btnStartScanDevices.isEnabled = false // Disable scan during connection attempt
            } else {
                Toast.makeText(context, "Service not ready.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        // ... (permission checking logic remains the same) ...
        Log.d(TAG, "Checking permissions...")
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "All permissions already granted.")
            startBleScan()
        } else {
            Log.d(TAG, "Requesting missing permissions: ${missingPermissions.joinToString()}")
            requestMultiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startBleScan() {
        // ... (scan starting logic remains the same) ...
        Log.d(TAG, "Attempting to start BLE scan...")
        if (bleService == null) {
            bleService = (activity as? MainActivity)?.getBleServiceInstance()
        }

        if (bleService != null) {
            Log.d(TAG, "Calling bleService.startScan()")
            binding.btnConnectDevices.isEnabled = false
            deviceListAdapter.submitList(emptyList()) // Clear list when starting scan
            bleService?.startScan()
        } else {
            Log.w(TAG, "Cannot start scan, BleService is still not available.")
            Toast.makeText(context, "Service not ready, please wait.", Toast.LENGTH_SHORT).show()
            binding.statusTextDevices.text = "Status: Service not ready"
        }
    }


    private fun observeBleService() {
        if (bleService == null) {
            Log.e(TAG, "Observe: BleService is null.")
            return
        }
        Log.d(TAG, "Setting up observers for BleService LiveData")

        bleService?.connectionStatus?.observe(viewLifecycleOwner) { status ->
            Log.d(TAG, "Observed Connection Status: $status")
            binding.statusTextDevices.text = "Status: $status"
            // Maybe disable scan button if connected?
            // binding.btnStartScanDevices.isEnabled = status != "Connected" // Example
        }

        bleService?.scanResults?.observe(viewLifecycleOwner) { devices ->
            Log.d(TAG, "Observed Scan Results: Updating adapter with ${devices.size} devices")
            // --- Update the adapter ---
            deviceListAdapter.submitList(devices)
            // --- End Adapter Update ---
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAttemptedObservationSetup = false
        Log.d(TAG, "Fragment view destroyed")
    }

    // Removed onResume logic
}

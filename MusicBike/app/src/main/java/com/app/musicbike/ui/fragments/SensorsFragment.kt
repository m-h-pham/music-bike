package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.util.Log // Import Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.app.musicbike.services.BleService // Import BleService
import com.app.musicbike.databinding.FragmentSensorsBinding
import com.app.musicbike.ui.activities.MainActivity // Import MainActivity
import java.util.Locale // Import Locale for formatting

class SensorsFragment : Fragment() {

    private val TAG = "SensorsFragment" // Add TAG
    private var _binding: FragmentSensorsBinding? = null
    private val binding get() = _binding!!

    // Hold reference to the service
    private var bleService: BleService? = null
    private var hasAttemptedObservationSetup = false // Flag

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")
        // Try to get service if activity already has it (e.g., on configuration change)
        if ((activity as? MainActivity)?.isServiceConnected == true) {
            onServiceReady() // Attempt setup early if possible
        } else {
            binding.txtSensorData.text = "Waiting for connection..." // Initial text
        }
    }

    // Called by MainActivity when the BleService is connected and ready
    fun onServiceReady() {
        Log.d(TAG, "onServiceReady called by Activity.")
        bleService = (activity as? MainActivity)?.getBleServiceInstance()
        if (bleService != null && !hasAttemptedObservationSetup) {
            Log.d(TAG, "Service is ready, setting up observers.")
            observeSensorData() // Setup observers now
            hasAttemptedObservationSetup = true
        } else if (bleService == null) {
            Log.e(TAG, "onServiceReady called, but failed to get service instance!")
            binding.txtSensorData.text = "Error getting service"
        } else {
            Log.d(TAG, "onServiceReady called, but observers already set up.")
        }
    }

    // --- NEW: Function to observe sensor data LiveData ---
    private fun observeSensorData() {
        if (bleService == null) {
            Log.e(TAG, "observeSensorData: BleService is null.")
            return
        }
        Log.d(TAG, "Setting up observers for Sensor LiveData")

        // Observe Speed
        bleService?.speed?.observe(viewLifecycleOwner) { speed ->
            updateSensorDisplay() // Update the display whenever any value changes
        }
        // Observe Pitch
        bleService?.pitch?.observe(viewLifecycleOwner) { pitch ->
            updateSensorDisplay()
        }
        // Observe Roll
        bleService?.roll?.observe(viewLifecycleOwner) { roll ->
            updateSensorDisplay()
        }
        // Observe Yaw
        bleService?.yaw?.observe(viewLifecycleOwner) { yaw ->
            updateSensorDisplay()
        }
        // Observe Event
        bleService?.lastEvent?.observe(viewLifecycleOwner) { event ->
            updateSensorDisplay()
        }
        bleService?.imuDirection?.observe(viewLifecycleOwner) { direction ->
            updateSensorDisplay()
        }
        bleService?.hallDirection?.observe(viewLifecycleOwner) { direction ->
            updateSensorDisplay()
        }
        bleService?.imuSpeedState?.observe(viewLifecycleOwner) { state ->
            updateSensorDisplay()
        }
        bleService?.gForce?.observe(viewLifecycleOwner) { gForce ->
            updateSensorDisplay()
        }

        // Observe connection status to show appropriate message
        bleService?.connectionStatus?.observe(viewLifecycleOwner) { status ->
            if (status != "Ready" && status != "Connected" && status != "Services Discovered" && status != "Discovering Services...") {
                // Show disconnected/error status if not actively connected/ready
                binding.txtSensorData.text = "Status: $status"
            } else if (status == "Ready"){
                // If status becomes Ready, update display with current values
                updateSensorDisplay()
            }
        }
    }

    private fun updateSensorDisplay() {
        // Get the latest values from LiveData
        val speed = bleService?.speed?.value ?: 0.0f
        val pitch = bleService?.pitch?.value ?: 0.0f
        val roll = bleService?.roll?.value ?: 0.0f
        val yaw = bleService?.yaw?.value ?: 0.0f
        val lastEvent = bleService?.lastEvent?.value ?: "NONE"
        val imuDir = if (bleService?.imuDirection?.value == 1) "Fwd" else "Rev"
        val hallDir = if (bleService?.hallDirection?.value == 1) "Fwd" else "Rev"
        val speedState = when (bleService?.imuSpeedState?.value) {
            0 -> "Stop/Slow"
            1 -> "Medium"
            2 -> "Fast"
            else -> "N/A"
        }
        // --- Add this line to get G-Force ---
        val gForce = bleService?.gForce?.value ?: 0.0f
        // --- End G-Force addition ---

        // Format the string to display (add G-Force)
        val displayText = String.format(Locale.US,
            "Speed: %.1f km/h | G-Force: %.2fg\n" + // Added G-Force here
                    "Pitch: %.1f | Roll: %.1f | Yaw: %.1f\n" +
                    "IMU Dir: %s | Hall Dir: %s\n" +
                    "IMU Spd State: %s\n" +
                    "Event: %s",
            speed, gForce, // Add gForce to format arguments
            pitch, roll, yaw,
            imuDir, hallDir,
            speedState,
            lastEvent
        )

        binding.txtSensorData.text = displayText // Set the text
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAttemptedObservationSetup = false // Reset flag
        Log.d(TAG, "Fragment view destroyed")
    }

    // Optional: Try to get service reference again when fragment resumes
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called.")
        // If service is ready but observation hasn't started (e.g., fragment recreated)
        if (bleService == null && (activity as? MainActivity)?.isServiceConnected == true) {
            Log.d(TAG, "onResume: Service connected, attempting to set up observers.")
            onServiceReady()
        } else if (bleService != null) {
            // Service already known, ensure UI reflects current state
            updateSensorDisplay()
        } else {
            binding.txtSensorData.text = "Waiting for connection..."
        }
    }
}
package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.app.musicbike.databinding.FragmentSensorsBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.ui.activities.MainActivity
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class SensorsFragment : Fragment() {

    private val TAG = "SensorsFragment"
    private var _binding: FragmentSensorsBinding? = null
    private val binding get() = _binding!!

    private var bleService: BleService? = null
    private var hasAttemptedObservationSetup = false

    // Recording support
    private var isRecording = false
    private var recordDurationSec = 0.0f
    private val recordBuffer = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the slider (0..20 steps → 0.0..2.0s in 0.1s increments)
        binding.seekRecordingDuration.max = 20
        binding.seekRecordingDuration.progress = 0
        binding.txtDurationValue.text = "0.0"

        // Add listener to update duration value
        binding.seekRecordingDuration.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    // convert 0..20 to 0.0..2.0 seconds in 0.1 increments
                    val seconds = progress * 0.1f
                    binding.txtDurationValue.text =
                        String.format(Locale.US, "%.1f", seconds)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { /* no-op */ }
                override fun onStopTrackingTouch(seekBar: SeekBar) { /* no-op */ }
            }
        )

        binding.seekRecordingDuration.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    recordDurationSec = progress * 0.1f
                    binding.txtDurationValue.text =
                        String.format(Locale.US, "%.1f", recordDurationSec)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )

        // Record button
        binding.btnStartRecording.setOnClickListener {
            val filename = binding.editFilename.text?.toString()?.trim().orEmpty()
            if (filename.isEmpty()) {
                Snackbar.make(binding.root, "Filename is required", Snackbar.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            startRecording(filename)
        }

        // Try to hook up BLE as before
        if ((activity as? MainActivity)?.isServiceConnected == true) {
            onServiceReady()
        } else {
            binding.txtSensorData.text = "Waiting for connection..."
        }
    }

    private fun startRecording(filename: String) {
        if (recordDurationSec <= 0f) {
            Snackbar.make(binding.root, "Set a duration > 0", Snackbar.LENGTH_SHORT).show()
            return
        }
        recordBuffer.clear()
        isRecording = true
        binding.btnStartRecording.text = "Recording..."
        binding.btnStartRecording.isEnabled = false

        // Schedule stop
        mainHandler.postDelayed({
            stopRecording(filename)
        }, (recordDurationSec * 1000).toLong())
    }

    private fun stopRecording(filename: String) {
        isRecording = false
        binding.btnStartRecording.text = "Start Recording"
        binding.btnStartRecording.isEnabled = true

        try {
            val outFile = File(requireContext().filesDir, "$filename.txt")
            FileOutputStream(outFile).use { fos ->
                recordBuffer.forEach { line ->
                    fos.write((line + "\n").toByteArray())
                }
            }
            Snackbar.make(
                binding.root,
                "Saved ${recordBuffer.size} lines to ${outFile.name}",
                Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file", e)
            Snackbar.make(binding.root, "Save failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    fun onServiceReady() {
        bleService = (activity as? MainActivity)?.getBleServiceInstance()
        if (bleService != null && !hasAttemptedObservationSetup) {
            observeSensorData()
            hasAttemptedObservationSetup = true
        } else if (bleService == null) {
            binding.txtSensorData.text = "Error getting service"
        }
    }

    private fun observeSensorData() {
        bleService?.apply {
            speed.observe(viewLifecycleOwner) { updateSensorDisplay() }
            pitch.observe(viewLifecycleOwner) { updateSensorDisplay() }
            roll.observe(viewLifecycleOwner) { updateSensorDisplay() }
            yaw.observe(viewLifecycleOwner) { updateSensorDisplay() }
            lastEvent.observe(viewLifecycleOwner) { updateSensorDisplay() }
            imuDirection.observe(viewLifecycleOwner) { updateSensorDisplay() }
            hallDirection.observe(viewLifecycleOwner) { updateSensorDisplay() }
            imuSpeedState.observe(viewLifecycleOwner) { updateSensorDisplay() }
            gForce.observe(viewLifecycleOwner) { updateSensorDisplay() }
            connectionStatus.observe(viewLifecycleOwner) { status ->
                if (status !in listOf("Ready", "Connected", "Services Discovered")) {
                    binding.txtSensorData.text = "Status: $status"
                } else if (status == "Ready") {
                    updateSensorDisplay()
                }
            }
        }
    }

    private fun updateSensorDisplay() {
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
        val gForce = bleService?.gForce?.value ?: 0.0f

        val displayText = String.format(
            Locale.US,
            "Speed: %.1f km/h | G-Force: %.2fg\n" +
                    "Pitch: %.1f | Roll: %.1f | Yaw: %.1f\n" +
                    "IMU Dir: %s | Hall Dir: %s\n" +
                    "IMU Spd State: %s\n" +
                    "Event: %s",
            speed, gForce,
            pitch, roll, yaw,
            imuDir, hallDir,
            speedState,
            lastEvent
        )
        binding.txtSensorData.text = displayText

        // If recording is active, capture this triple
        if (isRecording) {
            recordBuffer.add(
                String.format(Locale.US, "IMU: %.1f, %.1f, %.1f", pitch, roll, yaw)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (bleService == null && (activity as? MainActivity)?.isServiceConnected == true) {
            onServiceReady()
        } else {
            updateSensorDisplay()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAttemptedObservationSetup = false
        mainHandler.removeCallbacksAndMessages(null)
    }
}

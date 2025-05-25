package com.app.musicbike.ui.fragments

import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.app.musicbike.R // Import R for accessing resources (e.g., R.raw.sound_file)
import com.app.musicbike.databinding.FragmentSensorsBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.ui.activities.MainActivity
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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

    // Countdown and sound support
    private var countdownTimer: CountDownTimer? = null
    private var isCountingDown = false
    private var mediaPlayer: MediaPlayer? = null

    // Used for machine learning file viewer
    private lateinit var fileAdapter: FileAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the slider (0..50 steps → 0.0..5.0s in 0.1s increments)
        binding.seekRecordingDuration.max = 50 // Example: 50 * 0.1f = 5.0 seconds max
        binding.seekRecordingDuration.progress = 0
        binding.txtDurationValue.text = "0.0"

        // Add listener to update duration value
        binding.seekRecordingDuration.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    recordDurationSec = progress * 0.1f // Adjust multiplier if max changes
                    binding.txtDurationValue.text =
                        String.format(Locale.US, "%.1f", recordDurationSec)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { /* no-op */ }
                override fun onStopTrackingTouch(sb: SeekBar) { /* no-op */ }
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

            if (recordDurationSec <= 0f) {
                Snackbar.make(binding.root, "Set a recording duration > 0 seconds", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isRecording || isCountingDown) {
                Snackbar.make(binding.root, "A recording or countdown is already in progress.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Filename and duration are valid, start the sequence
            initiateRecordingSequence(filename)
        }

        // Zero accelerometer button
        binding.btnZeroAccelerometer.setOnClickListener {
            if (bleService?.zeroAccelerometer() == true) {
                Snackbar.make(binding.root, "Zeroing accelerometer...", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Failed to zero accelerometer", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Try to hook up BLE
        if ((activity as? MainActivity)?.isBleServiceConnected == true) {
            onServiceReady()
        } else {
            binding.txtSensorData.text = "Waiting for connection..."
        }
        setupFileList()
    }

    private fun initiateRecordingSequence(filename: String) {
        isCountingDown = true
        binding.btnStartRecording.isEnabled = false
        val countdownDurationMillis = 10000L // 10 seconds
        val countDownIntervalMillis = 1000L  // 1 second

        countdownTimer = object : CountDownTimer(countdownDurationMillis, countDownIntervalMillis) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = millisUntilFinished / 1000
                binding.btnStartRecording.text = "Starting in ${secondsRemaining}s..."
                when (secondsRemaining.toInt()) {
                    3 -> playCountdownSound(3)
                    2 -> playCountdownSound(2)
                    1 -> playCountdownSound(1)
                }
            }
            override fun onFinish() {
                isCountingDown = false
                binding.btnStartRecording.text = "Starting..." // Brief intermediate state
                startActualRecording(filename)
            }
        }
        binding.btnStartRecording.text = "Starting in 10s..." // Initial text for countdown
        countdownTimer?.start()
    }

    private fun playCountdownSound(secondValue: Int) {
        if (!isAdded) return // Ensure fragment is attached to a context
        Log.d(TAG, "Attempting to play sound for $secondValue seconds left")
        // Release any existing MediaPlayer instance.
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Error stopping/releasing previous MediaPlayer", e)
        }
        mediaPlayer = null
        // Play sound at 3, 2, 1 in countdown
        val soundResId = when (secondValue) {
            3 -> R.raw.tone_beep_slow
            2 -> R.raw.tone_beep_slow
            1 -> R.raw.tone_beep_slow
            else -> 0 // No sound defined
        }
        if (soundResId != 0) {
            try {
                mediaPlayer = MediaPlayer.create(requireContext(), soundResId)
                mediaPlayer?.setOnCompletionListener { mp ->
                    Log.d(TAG, "Sound playback completed for $secondValue.")
                    mp.release()
                    if (mediaPlayer == mp) { // Clear the reference if it's the same player that completed
                        mediaPlayer = null
                    }
                }
                mediaPlayer?.setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error for sound $secondValue: what=$what, extra=$extra")
                    mp.release()
                    if (mediaPlayer == mp) {
                        mediaPlayer = null
                    }
                    true // Error handled
                }
                mediaPlayer?.start()
                Log.d(TAG, "Started sound for $secondValue.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing or playing sound for $secondValue seconds", e)
                mediaPlayer?.release() // Ensure release on exception
                mediaPlayer = null
            }
        } else {
            Log.d(TAG, "No sound resource ID defined for $secondValue seconds left. Skipping sound.")
        }
        // For frequent, short sounds, consider using SoundPool for lower latency.
    }

    private fun startActualRecording(filename: String) {
        if (recordDurationSec <= 0f) { // This check is now primarily in the click listener
            Snackbar.make(binding.root, "Set a duration > 0", Snackbar.LENGTH_SHORT).show()
            // Reset button if something went wrong before this point
            binding.btnStartRecording.text = "Start Recording"
            binding.btnStartRecording.isEnabled = true
            isRecording = false // Ensure this is false if we bail out
            isCountingDown = false
            return
        }
        recordBuffer.clear()
        isRecording = true
        binding.btnStartRecording.text = "Recording..."
        binding.btnStartRecording.isEnabled = false // Should already be disabled, but ensure it
        // Schedule stop
        mainHandler.postDelayed({
            stopRecording(filename)
        }, (recordDurationSec * 1000).toLong())
        Log.d(TAG, "Recording started for ${recordDurationSec}s. Saving to $filename.txt")
    }

    private fun stopRecording(filename: String) {
        isRecording = false
        isCountingDown = false
        binding.btnStartRecording.text = "Start Recording"
        binding.btnStartRecording.isEnabled = true
        countdownTimer?.cancel() // Cancel countdown if stop is called prematurely
        if (recordBuffer.isEmpty() && recordDurationSec > 0) {
            Log.w(TAG, "Recording stopped, but buffer is empty. File will be empty or not created.")
        }
        val outFile = getUniqueFile(filename)
        try {
            FileOutputStream(outFile).use { fos ->
                recordBuffer.forEach { line ->
                    fos.write((line + "\n").toByteArray())
                }
            }
            Snackbar.make(binding.root,
                "Saved ${recordBuffer.size} lines to ${outFile.name}",
                Snackbar.LENGTH_SHORT
            ).show()
            Log.d(TAG, "Saved to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file", e)
            Snackbar.make(binding.root,
                "Save failed: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
        recordBuffer.clear() // Clear buffer after saving or attempting to save
    }

    /**
     * Returns a File in the app’s internal filesDir that does not
     * collide with any existing .txt file by appending _1, _2, …
     */
    private fun getUniqueFile(baseName: String): File {
        val dir = requireContext().filesDir
        // Start with the plain name
        var candidate = File(dir, "$baseName.txt")
        if (!candidate.exists()) return candidate

        // If it exists, try suffixes _1, _2, …
        var suffix = 1
        do {
            candidate = File(dir, "${baseName}_$suffix.txt")
            suffix++
        } while (candidate.exists())
        return candidate
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
                    updateSensorDisplay() // Update display once BLE service is fully ready
                }
            }
        }
    }

    private fun updateSensorDisplay() {
        val speedVal = bleService?.speed?.value ?: 0.0f
        val pitchVal = bleService?.pitch?.value ?: 0.0f
        val rollVal = bleService?.roll?.value ?: 0.0f
        val yawVal = bleService?.yaw?.value ?: 0.0f
        val lastEventVal = bleService?.lastEvent?.value ?: "NONE"
        val imuDirVal = if (bleService?.imuDirection?.value == 1) "Fwd" else "Rev"
        val hallDirVal = if (bleService?.hallDirection?.value == 1) "Fwd" else "Rev"
        val speedStateVal = when (bleService?.imuSpeedState?.value) {
            0 -> "Stop/Slow"
            1 -> "Medium"
            2 -> "Fast"
            else -> "N/A"
        }
        val gForceVal = bleService?.gForce?.value ?: 0.0f
        val displayText = String.format(
            Locale.US,
            "Speed: %.1f km/h | G-Force: %.2fg\n" +
                    "Pitch: %.1f | Roll: %.1f | Yaw: %.1f\n" +
                    "IMU Dir: %s | Hall Dir: %s\n" +
                    "IMU Spd State: %s\n" +
                    "Event: %s",
            speedVal, gForceVal,
            pitchVal, rollVal, yawVal,
            imuDirVal, hallDirVal,
            speedStateVal,
            lastEventVal
        )
        binding.txtSensorData.text = displayText
        // If recording is active, capture this data
        if (isRecording) {
            // Example: record all relevant values, comma-separated
            val dataLine = String.format(Locale.US,
                "%.2f,%.2f,%.2f,%.2f,%.2f,%s,%s,%s,%s",
                System.currentTimeMillis() / 1000.0, // timestamp
                pitchVal, rollVal, yawVal, gForceVal,
                imuDirVal, hallDirVal, speedStateVal, lastEventVal
            )
            recordBuffer.add(dataLine)
        }
    }

    private class FileAdapter(
        private val onDelete: (File) -> Unit
    ) : ListAdapter<File, FileAdapter.FileViewHolder>(FILE_DIFF_CALLBACK) {

        inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtFilename: TextView = itemView.findViewById(R.id.txtFilename)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(file: File) {
                txtFilename.text = file.name
                btnDelete.setOnClickListener { onDelete(file) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.file_item, parent, false)
            return FileViewHolder(view)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            private val FILE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<File>() {
                override fun areItemsTheSame(old: File, new: File) =
                    old.absolutePath == new.absolutePath

                override fun areContentsTheSame(old: File, new: File) = true
            }
        }
    }

    private fun setupFileList() {
        // Initialize adapter (as you already have)
        fileAdapter = FileAdapter { file ->
            if (file.delete()) {
                Snackbar.make(binding.root, "Deleted ${file.name}", Snackbar.LENGTH_SHORT).show()
                loadFileList()
            } else {
                Snackbar.make(binding.root, "Failed to delete ${file.name}", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Configure RecyclerView
        binding.rvFileList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fileAdapter
        }

        // Configure pull-to-refresh
        binding.swipeRefreshFiles.setOnRefreshListener {
            loadFileList()                            // reload current .txt files
            binding.swipeRefreshFiles.isRefreshing = false
        }

        // Initial load
        loadFileList()
    }

    private fun loadFileList() {
        val txtFiles = requireContext().filesDir
            .listFiles { dir, name -> name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        fileAdapter.submitList(txtFiles)
    }

    override fun onResume() {
        super.onResume()
        // Re-check service connection if it might have established while fragment was paused
        if (bleService == null && (activity as? MainActivity)?.isBleServiceConnected == true) {
            onServiceReady()
        } else {
            // Update display with current values if service was already connected
            updateSensorDisplay()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Crucial to avoid memory leaks
        hasAttemptedObservationSetup = false
        mainHandler.removeCallbacksAndMessages(null) // Stop any pending recording stops
        countdownTimer?.cancel() // Cancel countdown if active
        countdownTimer = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: IllegalStateException) {
            // Ignore
        }
        mediaPlayer = null
    }
}
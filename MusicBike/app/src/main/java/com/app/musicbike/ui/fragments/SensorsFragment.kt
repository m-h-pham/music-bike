package com.app.musicbike.ui.fragments

import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
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

// For runtime permissions
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// For Storage Access Framework
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.preference.PreferenceManager
import java.io.OutputStream

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

    // Storage Access Framework support
    private lateinit var sharedPrefs: SharedPreferences
    private var selectedDirectoryUri: Uri? = null
    
    // Activity result launcher for selecting directory
    private val selectDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(TAG, "User selected directory: $uri")
                handleDirectorySelection(uri)
            }
        } else {
            Log.w(TAG, "User cancelled directory selection")
            Snackbar.make(binding.root, "Directory selection cancelled", Snackbar.LENGTH_SHORT).show()
        }
    }

    private companion object {
        private const val REQUEST_WRITE_STORAGE_PERMISSION = 101
        private const val PREF_SELECTED_DIRECTORY_URI = "selected_directory_uri"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize SharedPreferences and load previously selected directory
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        loadSelectedDirectory()

        // Initialize the slider (0..50 steps → 0.0..5.0s in 0.1s increments)
        binding.seekRecordingDuration.max = 100 // Example: 100 * 0.1f = 10.0 seconds max
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
            Log.d(TAG, "Start Recording button clicked")
            
            // First, validate input regardless of permission status
            val filename = binding.editFilename.text?.toString()?.trim().orEmpty()
            if (filename.isEmpty()) {
                Log.d(TAG, "Validation failed: filename is empty")
                Snackbar.make(binding.root, "Filename is required", Snackbar.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            if (recordDurationSec <= 0f) {
                Log.d(TAG, "Validation failed: duration is $recordDurationSec")
                Snackbar.make(binding.root, "Set a recording duration > 0 seconds", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isRecording || isCountingDown) {
                Log.d(TAG, "Validation failed: already recording or counting down")
                Snackbar.make(binding.root, "A recording or countdown is already in progress.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            Log.d(TAG, "Validation passed. Checking permissions...")
            
            // Now check and request permissions
            if (!checkAndRequestStoragePermission()) {
                Log.d(TAG, "Permission not granted, request initiated")
                // Permission is not granted and has been requested.
                // The user will be prompted, and onRequestPermissionsResult will handle the outcome.
                return@setOnClickListener
            }

            Log.d(TAG, "Permission granted, starting recording sequence")
            // Permission is granted and validation passed, start the sequence
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
                    5 -> playCountdownSound(2)
                    4 -> playCountdownSound(1)
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
            5 -> R.raw.tone_beep
            4 -> R.raw.tone_beep
            3 -> R.raw.tone_beep
            2 -> R.raw.tone_beep
            1 -> R.raw.tone_beep
            else -> 0 // No sound defined
        }
        if (soundResId != 0) {
            try {
                mediaPlayer = MediaPlayer.create(requireContext(), soundResId)
                // Set volume to maximum (1.0f = 100% for both left and right channels)
                mediaPlayer?.setVolume(1.0f, 1.0f)
                
                // Set audio attributes to ensure it plays on the media stream
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                mediaPlayer?.setAudioAttributes(audioAttributes)

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
        
        try {
            val bytesToWrite = recordBuffer.joinToString("\n").toByteArray()
            val sdkVersion = android.os.Build.VERSION.SDK_INT
            
            if (sdkVersion >= android.os.Build.VERSION_CODES.Q && selectedDirectoryUri != null) {
                // Use Storage Access Framework for Android 10+
                saveFileUsingSAF(filename, bytesToWrite)
            } else {
                // Use legacy file system for Android 9 and below
                saveFileLegacy(filename, bytesToWrite)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file", e)
            Snackbar.make(binding.root,
                "Save failed: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
        recordBuffer.clear() // Clear buffer after saving or attempting to save
    }
    
    private fun saveFileUsingSAF(filename: String, data: ByteArray) {
        try {
            val treeUri = selectedDirectoryUri ?: throw IllegalStateException("No directory selected")
            val documentFile = DocumentFile.fromTreeUri(requireContext(), treeUri)
                ?: throw IllegalStateException("Cannot access selected directory")
            
            // Generate unique filename
            val finalFilename = generateUniqueFilename(documentFile, filename)
            
            // Create the file
            val newFile = documentFile.createFile("text/plain", finalFilename)
                ?: throw IllegalStateException("Cannot create file in selected directory")
            
            // Write data
            requireContext().contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                outputStream.write(data)
            }
            
            Log.d(TAG, "Saved to SAF: ${newFile.uri}")
            Snackbar.make(binding.root,
                "Saved ${recordBuffer.size} lines to $finalFilename",
                Snackbar.LENGTH_SHORT
            ).show()
            
            // Reload file list
            loadFileList()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file using SAF", e)
            throw e
        }
    }
    
    private fun saveFileLegacy(filename: String, data: ByteArray) {
        val outFile = getUniqueFileLegacy(filename)
        FileOutputStream(outFile).use { fos ->
            fos.write(data)
        }
        Snackbar.make(binding.root,
            "Saved ${recordBuffer.size} lines to Documents/${outFile.name}",
            Snackbar.LENGTH_SHORT
        ).show()
        Log.d(TAG, "Saved to legacy: ${outFile.absolutePath}")
    }
    
    private fun generateUniqueFilename(parentDir: DocumentFile, baseName: String): String {
        var candidate = "$baseName.txt"
        var suffix = 1
        
        while (parentDir.findFile(candidate) != null) {
            candidate = "${baseName}_$suffix.txt"
            suffix++
        }
        
        return candidate
    }

    /**
     * Returns a File in the app's internal filesDir that does not
     * collide with any existing .txt file by appending _1, _2, …
     */
    private fun getUniqueFileLegacy(baseName: String): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        // Create the directory if it doesn't exist
        if (!dir.exists()) {
            dir.mkdirs()
        }
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
                "%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.2f",
                System.currentTimeMillis() / 1000.0, // timestamp
                pitchVal, rollVal, yawVal, gForceVal, hallDirVal, speedVal
            )
            recordBuffer.add(dataLine)
        }
    }

    private class FileAdapter(
        private val onDelete: (FileItemWrapper) -> Unit
    ) : ListAdapter<FileItemWrapper, FileAdapter.FileViewHolder>(FILE_DIFF_CALLBACK) {

        inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtFilename: TextView = itemView.findViewById(R.id.txtFilename)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(fileItem: FileItemWrapper) {
                txtFilename.text = fileItem.name
                btnDelete.setOnClickListener { onDelete(fileItem) }
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
            private val FILE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<FileItemWrapper>() {
                override fun areItemsTheSame(old: FileItemWrapper, new: FileItemWrapper) =
                    old.name == new.name

                override fun areContentsTheSame(old: FileItemWrapper, new: FileItemWrapper) = 
                    old.name == new.name
            }
        }
    }

    private fun setupFileList() {
        // Initialize adapter (as you already have)
        fileAdapter = FileAdapter { fileItem ->
            if (fileItem.delete()) {
                Snackbar.make(binding.root, "Deleted ${fileItem.name}", Snackbar.LENGTH_SHORT).show()
                loadFileList()
            } else {
                Snackbar.make(binding.root, "Failed to delete ${fileItem.name}", Snackbar.LENGTH_SHORT).show()
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

    private fun checkAndRequestStoragePermission(): Boolean {
        Log.d(TAG, "Checking storage permission...")
        
        val sdkVersion = android.os.Build.VERSION.SDK_INT
        Log.d(TAG, "Android SDK version: $sdkVersion")
        
        return if (sdkVersion >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+): Use Storage Access Framework
            Log.d(TAG, "Using Storage Access Framework for Android 10+")
            
            if (selectedDirectoryUri != null) {
                // Check if we still have permission to the previously selected directory
                try {
                    val documentFile = DocumentFile.fromTreeUri(requireContext(), selectedDirectoryUri!!)
                    if (documentFile?.exists() == true && documentFile.canWrite()) {
                        Log.d(TAG, "Already have access to selected directory")
                        return true
                    } else {
                        Log.w(TAG, "Lost access to previously selected directory")
                        selectedDirectoryUri = null
                        sharedPrefs.edit().remove(PREF_SELECTED_DIRECTORY_URI).apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking directory access", e)
                    selectedDirectoryUri = null
                    sharedPrefs.edit().remove(PREF_SELECTED_DIRECTORY_URI).apply()
                }
            }
            
            // Request user to select directory
            Log.d(TAG, "Requesting user to select directory")
            Snackbar.make(
                binding.root,
                "Please select a folder to save recording files",
                Snackbar.LENGTH_LONG
            ).setAction("Select Folder") {
                openDirectoryPicker()
            }.show()
            
            false // Not ready yet, need user to select directory
        } else {
            // Android 9 and below: Use legacy permission system
            Log.d(TAG, "Using legacy permission system for Android 9 and below")
            
            val hasPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "WRITE_EXTERNAL_STORAGE permission granted: $hasPermission")
            
            if (!hasPermission) {
                val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                    requireActivity(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                Log.d(TAG, "Should show permission rationale: $shouldShowRationale")
                
                if (shouldShowRationale) {
                    Snackbar.make(
                        binding.root,
                        "Storage permission is needed to save recording files",
                        Snackbar.LENGTH_LONG
                    ).setAction("Grant") {
                        Log.d(TAG, "User clicked Grant from rationale snackbar")
                        requestPermission()
                    }.show()
                } else {
                    Log.d(TAG, "Requesting permission directly")
                    requestPermission()
                }
                false
            } else {
                Log.d(TAG, "Permission already granted")
                true
            }
        }
    }
    
    private fun openDirectoryPicker() {
        Log.d(TAG, "Opening directory picker")
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            selectDirectoryLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening directory picker", e)
            Snackbar.make(
                binding.root,
                "Failed to open directory picker: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun requestPermission() {
        Log.d(TAG, "Requesting WRITE_EXTERNAL_STORAGE permission...")
        try {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE_PERMISSION
            )
            Log.d(TAG, "Permission request initiated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permission", e)
            Snackbar.make(
                binding.root,
                "Failed to request storage permission: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult called with requestCode: $requestCode")
        Log.d(TAG, "Permissions: ${permissions.joinToString()}")
        Log.d(TAG, "Grant results: ${grantResults.joinToString()}")
        
        if (requestCode == REQUEST_WRITE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Storage permission was granted")
                // Permission was granted
                Snackbar.make(binding.root, "Storage permission granted.", Snackbar.LENGTH_SHORT).show()
                
                // Re-validate and attempt to start recording now that permission is granted
                val filename = binding.editFilename.text?.toString()?.trim().orEmpty()
                Log.d(TAG, "Re-validating after permission grant - filename: '$filename', duration: $recordDurationSec")
                
                if (filename.isNotEmpty() && recordDurationSec > 0f && !isRecording && !isCountingDown) {
                    Log.d(TAG, "Re-validation passed, starting recording sequence")
                    initiateRecordingSequence(filename)
                } else if (isRecording || isCountingDown) {
                    Log.w(TAG, "Cannot start recording - already in progress")
                    // This case should ideally not be hit if button was disabled, but as a safeguard:
                    Snackbar.make(binding.root, "Recording or countdown already in progress.", Snackbar.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "Cannot start recording - validation failed after permission grant")
                    Snackbar.make(binding.root, "Filename and duration are required to start recording.", Snackbar.LENGTH_SHORT).show()
                }
            } else {
                Log.w(TAG, "Storage permission was denied")
                // Permission denied
                Snackbar.make(binding.root, "Storage permission is required to save recordings.", Snackbar.LENGTH_LONG).show()
            }
            return
        } else {
            Log.d(TAG, "Ignoring permission result for requestCode: $requestCode")
        }
    }

    private fun loadFileList() {
        val sdkVersion = android.os.Build.VERSION.SDK_INT
        
        try {
            if (sdkVersion >= android.os.Build.VERSION_CODES.Q && selectedDirectoryUri != null) {
                // Load files using SAF for Android 10+
                loadFileListSAF()
            } else {
                // Load files using legacy file system for Android 9 and below
                loadFileListLegacy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading file list", e)
            fileAdapter.submitList(emptyList())
        }
    }
    
    private fun loadFileListSAF() {
        val treeUri = selectedDirectoryUri ?: return
        val documentFile = DocumentFile.fromTreeUri(requireContext(), treeUri) ?: return
        
        val txtFiles = documentFile.listFiles()
            .filter { it.name?.endsWith(".txt") == true }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { docFile ->
                // Convert DocumentFile to a wrapper that implements File-like interface
                docFile.name?.let { name ->
                    DocumentFileWrapper(docFile, name)
                }
            }
        
        fileAdapter.submitList(txtFiles)
    }
    
    private fun loadFileListLegacy() {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val txtFiles = documentsDir
            .listFiles { _, name -> name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        fileAdapter.submitList(txtFiles.map { FileWrapper(it) })
    }
    
    // Wrapper classes to provide unified interface for File and DocumentFile
    abstract class FileItemWrapper {
        abstract val name: String
        abstract fun delete(): Boolean
    }
    
    class FileWrapper(private val file: File) : FileItemWrapper() {
        override val name: String get() = file.name
        override fun delete(): Boolean = file.delete()
    }
    
    class DocumentFileWrapper(private val documentFile: DocumentFile, override val name: String) : FileItemWrapper() {
        override fun delete(): Boolean = documentFile.delete()
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

    private fun loadSelectedDirectory() {
        val uriString = sharedPrefs.getString(PREF_SELECTED_DIRECTORY_URI, null)
        selectedDirectoryUri = uriString?.let { Uri.parse(it) }
        Log.d(TAG, "Loaded selected directory: $selectedDirectoryUri")
    }

    private fun handleDirectorySelection(uri: Uri) {
        try {
            // Take persistent permission for the selected directory
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            selectedDirectoryUri = uri
            sharedPrefs.edit().putString(PREF_SELECTED_DIRECTORY_URI, uri.toString()).apply()
            
            Log.d(TAG, "Successfully selected and saved directory: $uri")
            Snackbar.make(binding.root, "Directory selected successfully", Snackbar.LENGTH_SHORT).show()
            
            // Reload file list to show files in the new directory
            loadFileList()
            
            // If user was trying to record, start the recording process now
            val filename = binding.editFilename.text?.toString()?.trim().orEmpty()
            if (filename.isNotEmpty() && recordDurationSec > 0f && !isRecording && !isCountingDown) {
                Log.d(TAG, "Directory selected, now starting recording sequence")
                initiateRecordingSequence(filename)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling directory selection", e)
            Snackbar.make(binding.root, "Failed to access selected directory: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }
}
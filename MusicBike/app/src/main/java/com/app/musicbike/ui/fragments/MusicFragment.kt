package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.app.musicbike.R
import com.app.musicbike.databinding.FragmentMusicBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.services.MusicService
import com.app.musicbike.ui.activities.MainActivity
import com.app.musicbike.ui.viewmodels.MusicViewModel
import java.io.File
import java.util.*

class MusicFragment : Fragment() {

    private val TAG = "MusicFragment"
    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())

    private val musicViewModel: MusicViewModel by viewModels()

    private var bleService: BleService? = null

    private var isWheelSpeedAutoUi = false
    private var isPitchAutoUi = false
    private var isEventAutoUi = false
    private var isHallDirectionAutoUi = false

    private fun isBleConnected(): Boolean {
        val mainActivity = activity as? MainActivity
        this.bleService = mainActivity?.getBleServiceInstance()
        val status = bleService?.connectionStatus?.value ?: return false
        return status.contains("connected", true) ||
                status.contains("ready", true) ||
                status.contains("discovered", true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView")
        return binding.root
    }

    // Called by MainActivity when BleService is ready
    fun onServiceReady() {
        Log.d(TAG, "onServiceReady (for BleService) called.")
        this.bleService = (activity as? MainActivity)?.getBleServiceInstance()
        updateUiBasedOnBleConnection()
    }

    // Called by MainActivity when MusicService is ready
    fun onMusicServiceReady(service: MusicService?) {
        Log.d(TAG, "onMusicServiceReady called with service: $service")
        // Pass viewLifecycleOwner for observing LiveData from the service via the ViewModel
        musicViewModel.setMusicService(service, viewLifecycleOwner)
        // Initial UI update based on ViewModel state (which should reflect service state)
        observeViewModel() // Ensure observers are set up after service is available
    }

    private fun copyAssetToInternalStorage(assetName: String): String {
        val file = File(requireContext().filesDir, assetName)
        if (file.exists()) file.delete()
        try {
            requireContext().assets.open(assetName).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetName", e)
            return ""
        }
        return file.absolutePath
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        (activity as? MainActivity)?.let { mainAct ->
            if (mainAct.isBleServiceConnected) {
                this.bleService = mainAct.getBleServiceInstance()
            }
            if (mainAct.isMusicServiceConnected) {
                // Pass viewLifecycleOwner here as well
                musicViewModel.setMusicService(mainAct.getMusicServiceInstance(), viewLifecycleOwner)
            }
        }
        observeViewModel() // Call this to set up observers
        setupUI()
        updateUiBasedOnBleConnection()
    }

    private fun setupUI() {
        Log.d(TAG, "setupUI called")

        binding.toggleButton.setOnClickListener {
            Log.d(TAG, "ToggleButton in MusicFragment - CLICKED!")
            musicViewModel.togglePlayback()
        }

        // Bank Selector (no changes to this part of setupUI)
        val bankSpinner = binding.bankSelector
        val allBanks = requireContext().assets.list("")?.filter {
            it.endsWith(".bank") && !it.endsWith("strings.bank")
        } ?: emptyList()
        val bankNames = allBanks.map { it.removeSuffix(".bank") }
        val bankAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, bankNames)
        bankAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bankSpinner.adapter = bankAdapter
        bankSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (allBanks.isNotEmpty() && position < allBanks.size) {
                    val selectedBankName = allBanks[position]
                    val masterBankPath = copyAssetToInternalStorage(selectedBankName)
                    val stringsBankPath = copyAssetToInternalStorage("Master.strings.bank")
                    musicViewModel.loadBank(masterBankPath, stringsBankPath, bankNames[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        setupSeekBar(binding.wheelSpeedSeekBar, binding.wheelSpeedLabel, "Wheel Speed", 25, 0, 0) { value ->
            if (!isWheelSpeedAutoUi) {
                musicViewModel.setFmodParameter("Wheel Speed", value)
            }
        }
        binding.wheelSpeedModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !isBleConnected()) {
                buttonView.isChecked = false
                binding.wheelSpeedSeekBar.isEnabled = true
                showToast("BLE not connected. Auto mode cancelled.")
                return@setOnCheckedChangeListener
            }
            isWheelSpeedAutoUi = isChecked
            binding.wheelSpeedSeekBar.isEnabled = !isChecked
            musicViewModel.setWheelSpeedAuto(isChecked)
        }

        setupSeekBar(binding.pitchSeekBar, binding.pitchLabel, "Pitch", 90, -45, 45) { value ->
            if (musicViewModel.isPitchAuto.value != true) { // Check ViewModel state
                musicViewModel.setFmodParameter("Pitch", value)
            }
        }
        binding.pitchModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isBleConnected()) {
                binding.pitchModeSwitch.isChecked = false // Revert
                // binding.pitchSeekBar.isEnabled = true // Will be handled by observer
                // binding.pitchReverseSwitch.isEnabled = false // Will be handled by observer
                showToast("BLE not connected. Auto mode cancelled.")
                return@setOnCheckedChangeListener
            }
            musicViewModel.setPitchAuto(isChecked)
        }

        binding.pitchReverseSwitch.setOnCheckedChangeListener { _, isChecked ->
            musicViewModel.togglePitchSignalReversal() // ViewModel handles the logic and persistence
            Log.d(TAG, "Pitch Reverse Switch UI toggled to: $isChecked")
        }

        binding.pitchReverseSwitch.isEnabled = musicViewModel.isPitchAuto.value ?: false

        val events = arrayOf("None", "Jump", "Drop")
        val eventAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, events)
        binding.eventSpinner.adapter = eventAdapter
        binding.eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!isEventAutoUi) {
                    musicViewModel.setFmodParameter("Event", position.toFloat())
                    if (position in 1..2) {
                        handler.postDelayed({
                            binding.eventSpinner.setSelection(0)
                            // musicViewModel.setFmodParameter("Event", 0f) // Service handles auto-reset if needed
                        }, 1000)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.eventModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !isBleConnected()) {
                buttonView.isChecked = false
                binding.eventSpinner.isEnabled = true
                showToast("BLE not connected. Auto mode cancelled.")
                return@setOnCheckedChangeListener
            }
            isEventAutoUi = isChecked
            binding.eventSpinner.isEnabled = !isChecked
            musicViewModel.setEventAuto(isChecked)
        }

        val hallDirectionOptions = arrayOf("Forward", "Reverse")
        val hallAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, hallDirectionOptions)
        binding.hallDirectionSpinner.adapter = hallAdapter
        binding.hallDirectionSpinner.setSelection(0) // Default Forward (UI)
        // musicViewModel.setFmodParameter("Hall Direction", 1f) // ViewModel/Service handles initial state

        binding.hallDirectionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!isHallDirectionAutoUi) {
                    val value = if (position == 0) 1f else 0f
                    musicViewModel.setFmodParameter("Hall Direction", value)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.hallDirectionModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !isBleConnected()) {
                buttonView.isChecked = false
                binding.hallDirectionSpinner.isEnabled = true
                showToast("BLE not connected. Auto mode cancelled.")
                return@setOnCheckedChangeListener
            }
            isHallDirectionAutoUi = isChecked
            binding.hallDirectionSpinner.isEnabled = !isChecked
            musicViewModel.setHallDirectionAuto(isChecked)
        }

        binding.autoAllSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isBleConnected()) {
                showToast("BLE not connected. Auto All cancelled.")
                handler.postDelayed({ binding.autoAllSwitch.isChecked = false }, 100)
                return@setOnCheckedChangeListener
            }
            binding.wheelSpeedModeSwitch.isChecked = isChecked
            binding.pitchModeSwitch.isChecked = isChecked
            binding.eventModeSwitch.isChecked = isChecked
            binding.hallDirectionModeSwitch.isChecked = isChecked
        }
    }

    private fun observeViewModel() {
        Log.d(TAG, "Setting up ViewModel observers.")

        musicViewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            Log.d(TAG, "Observed isPlaying: $playing")
            binding.toggleButton.text = if (playing) "Pause" else "Play"
        }

        musicViewModel.currentBankName.observe(viewLifecycleOwner) { bankName ->
            Log.d(TAG, "Observed currentBankName: $bankName")
            // Find the position of bankName in your bankNames list and set spinner
            val bankNames = (binding.bankSelector.adapter as ArrayAdapter<String>)
            val position = (0 until bankNames.count).firstOrNull { bankNames.getItem(it) == bankName }
            if (position != null && binding.bankSelector.selectedItemPosition != position) {
                binding.bankSelector.setSelection(position)
            }
        }

        musicViewModel.wheelSpeedDisplay.observe(viewLifecycleOwner) { speed ->
            // Only update UI from this if in auto mode, otherwise manual input takes precedence for display
            if (musicViewModel.isWheelSpeedAuto.value == true) {
                binding.wheelSpeedSeekBar.progress = speed.coerceIn(0f, 25f).toInt() // Max is 25 for this seekbar
                binding.wheelSpeedLabel.text = "Wheel Speed: ${speed.toInt()}"
            }
        }
        musicViewModel.pitchDisplay.observe(viewLifecycleOwner) { pitch ->
            if (musicViewModel.isPitchAuto.value == true) {
                binding.pitchSeekBar.progress = (pitch.coerceIn(-45f, 45f) + 45).toInt()
                binding.pitchLabel.text = "Pitch: ${pitch.toInt()}"
            }
        }
        musicViewModel.eventDisplayValue.observe(viewLifecycleOwner) { eventParam ->
            if (musicViewModel.isEventAuto.value == true) {
                val selection = when(eventParam.toInt()) {
                    1 -> 1 // Jump
                    2 -> 2 // Drop
                    else -> 0 // None
                }
                if (binding.eventSpinner.selectedItemPosition != selection) {
                    binding.eventSpinner.setSelection(selection)
                }
            }
        }
        musicViewModel.hallDirectionDisplayValue.observe(viewLifecycleOwner) { directionParam ->
            if (musicViewModel.isHallDirectionAuto.value == true) {
                // Assuming FMOD 1f = "Forward" (position 0 in spinner), 0f = "Reverse" (position 1)
                val selection = if (directionParam == 1f) 0 else 1
                if (binding.hallDirectionSpinner.selectedItemPosition != selection) {
                    binding.hallDirectionSpinner.setSelection(selection)
                }
            }
        }

        // Observers for auto mode states (to update switches and UI element enabled states)
        musicViewModel.isWheelSpeedAuto.observe(viewLifecycleOwner) { isAuto ->
            if (binding.wheelSpeedModeSwitch.isChecked != isAuto) binding.wheelSpeedModeSwitch.isChecked = isAuto
            binding.wheelSpeedSeekBar.isEnabled = !isAuto
        }
        musicViewModel.isPitchAuto.observe(viewLifecycleOwner) { isAuto ->
            if (binding.pitchModeSwitch.isChecked != isAuto) binding.pitchModeSwitch.isChecked = isAuto
            binding.pitchSeekBar.isEnabled = !isAuto
            binding.pitchReverseSwitch.isEnabled = isAuto // Enable reverse only if pitch auto is on
        }
        musicViewModel.isEventAuto.observe(viewLifecycleOwner) { isAuto ->
            if (binding.eventModeSwitch.isChecked != isAuto) binding.eventModeSwitch.isChecked = isAuto
            binding.eventSpinner.isEnabled = !isAuto
        }
        musicViewModel.isHallDirectionAuto.observe(viewLifecycleOwner) { isAuto ->
            if (binding.hallDirectionModeSwitch.isChecked != isAuto) binding.hallDirectionModeSwitch.isChecked = isAuto
            binding.hallDirectionSpinner.isEnabled = !isAuto
        }
        musicViewModel.isPitchSignalReversed.observe(viewLifecycleOwner) { isReversed ->
            if (binding.pitchReverseSwitch.isChecked != isReversed) {
                binding.pitchReverseSwitch.isChecked = isReversed
            }
            Log.d(TAG, "Observed pitchReversalEnabled from ViewModel: $isReversed, UI Switch updated.")
        }
    }

    private fun updateUiBasedOnBleConnection() {
        val connected = isBleConnected()
        Log.d(TAG, "updateUiBasedOnBleConnection: BLE connected = $connected")
        if (!connected) {
            if (binding.wheelSpeedModeSwitch.isChecked) binding.wheelSpeedModeSwitch.isChecked = false
            if (binding.pitchModeSwitch.isChecked) binding.pitchModeSwitch.isChecked = false
            if (binding.eventModeSwitch.isChecked) binding.eventModeSwitch.isChecked = false
            if (binding.hallDirectionModeSwitch.isChecked) binding.hallDirectionModeSwitch.isChecked = false
            if (binding.autoAllSwitch.isChecked) binding.autoAllSwitch.isChecked = false
        }
    }

    private fun setupSeekBar(
        seekBar: SeekBar, label: TextView, labelText: String,
        max: Int, offset: Int, initialProgress: Int,
        onChange: (Float) -> Unit
    ) {
        seekBar.max = max
        val isAssociatedAutoModeActive = when (seekBar.id) {
            R.id.wheelSpeedSeekBar -> musicViewModel.isWheelSpeedAuto.value ?: false
            R.id.pitchSeekBar -> musicViewModel.isPitchAuto.value ?: false
            else -> false
        }

        if (!isAssociatedAutoModeActive) {
            seekBar.progress = initialProgress
            val initialValue = initialProgress + offset
            label.text = "$labelText: $initialValue"
            // onChange(initialValue.toFloat()) // ViewModel will set initial FMOD params if needed
        } else {
            // Value will be set by observing ViewModel's display LiveData
        }

        seekBar.setOnSeekBarChangeListener(object : SimpleSeekBarChangeListener() {
            override fun onProgressChanged(sBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress + offset
                    label.text = "$labelText: $value"
                    onChange(value.toFloat())
                }
            }
        })
    }

    private fun showToast(msg: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }

    open class SimpleSeekBarChangeListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
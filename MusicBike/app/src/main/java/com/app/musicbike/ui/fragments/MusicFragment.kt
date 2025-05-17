package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.app.musicbike.R
import com.app.musicbike.databinding.FragmentMusicBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.ui.activities.MainActivity
import java.io.File
import java.util.*

class MusicFragment : Fragment() {

    private val testMode = false

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())

    private var isPaused = true
    private var isWheelSpeedAuto = false
    private var isPitchAuto = false
    private var isEventAuto = false
    private var isHallDirectionAuto = false

    private var bleService: BleService? = null

    // Observers
    private var speedObserver: Observer<Float>? = null
    private var pitchObserver: Observer<Float>? = null
    private var eventObserver: Observer<String>? = null
    private var hallDirectionObserver: Observer<Int>? = null

    private fun isBleConnected(): Boolean {
        val mainActivity = activity as? MainActivity
        bleService = mainActivity?.getBleServiceInstance()
        val status = bleService?.connectionStatus?.value ?: return false
        return status.contains("connected", true) ||
                status.contains("ready", true) ||
                status.contains("discovered", true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    fun onServiceReady() {
        bleService = (activity as? MainActivity)?.getBleServiceInstance()
    }

    private fun copyAssetToInternalStorage(assetName: String): String {
        val file = File(requireContext().filesDir, assetName)
        if (file.exists()) file.delete()
        requireContext().assets.open(assetName).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val mainActivity = activity as? MainActivity
        bleService = mainActivity?.getBleServiceInstance()

        // Playback
        binding.toggleButton.setOnClickListener {
            mainActivity?.toggleFMODPlayback()
            isPaused = !isPaused
            binding.toggleButton.text = if (isPaused) "Play" else "Pause"
        }

        // Bank Selector
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
                val selectedBankName = allBanks[position]
                val masterBankPath = copyAssetToInternalStorage(selectedBankName)
                val stringsBankPath = copyAssetToInternalStorage("Master.strings.bank")
                mainActivity?.startFMODPlayback(masterBankPath, stringsBankPath)
                isPaused = true
                binding.toggleButton.text = "Play"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Wheel Speed
        setupSeekBar(binding.wheelSpeedSeekBar, binding.wheelSpeedLabel, "Wheel Speed", 25, 0, 0) {
            mainActivity?.setFMODParameter("Wheel Speed", it)
        }

        binding.wheelSpeedModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !isBleConnected()) {
                buttonView.isChecked = false
                binding.wheelSpeedSeekBar.isEnabled = true
                showToast("BLE not connected. Auto mode cancelled.")
                return@setOnCheckedChangeListener
            }
            isWheelSpeedAuto = isChecked
            binding.wheelSpeedSeekBar.isEnabled = !isChecked
            if (isChecked) observeWheelSpeed() else removeWheelSpeedObserver()
        }

        // Pitch
        setupSeekBar(binding.pitchSeekBar, binding.pitchLabel, "Pitch", 90, -45, 45) {
            mainActivity?.setFMODParameter("Pitch", it)
        }

        binding.pitchModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !isBleConnected()) {
                buttonView.isChecked = false
                binding.pitchSeekBar.isEnabled = true
                showToast("BLE not connected. Auto mode cancelled.")
                return@setOnCheckedChangeListener
            }
            isPitchAuto = isChecked
            binding.pitchSeekBar.isEnabled = !isChecked
            if (isChecked) observePitch() else removePitchObserver()
        }

        // Event
        val events = arrayOf("None", "Jump", "Drop")
        val eventAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, events)
        eventAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.eventSpinner.adapter = eventAdapter

        binding.eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!isEventAuto) {
                    mainActivity?.setFMODParameter("Event", position.toFloat())
                    if (position in 1..2) {
                        handler.postDelayed({
                            binding.eventSpinner.setSelection(0)
                            mainActivity?.setFMODParameter("Event", 0f)
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
            isEventAuto = isChecked
            binding.eventSpinner.isEnabled = !isChecked
            if (isChecked) observeEvent() else removeEventObserver()
        }

        // Hall Direction
        val hallDirectionOptions = arrayOf("Forward", "Reverse")
        val hallAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, hallDirectionOptions)
        hallAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.hallDirectionSpinner.adapter = hallAdapter

        // Default to Forward
        binding.hallDirectionSpinner.setSelection(0)
        mainActivity?.setFMODParameter("Hall Direction", 1f)

        binding.hallDirectionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!isHallDirectionAuto) {
                    val value = if (position == 0) 1f else 0f
                    mainActivity?.setFMODParameter("Hall Direction", value)
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
            isHallDirectionAuto = isChecked
            binding.hallDirectionSpinner.isEnabled = !isChecked
            if (isChecked) observeHallDirection() else removeHallDirectionObserver()
        }

        // Auto All
        binding.autoAllSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isBleConnected()) {
                showToast("BLE not connected. Auto All cancelled.")
                handler.postDelayed({ binding.autoAllSwitch.isChecked = false }, 2000)
                return@setOnCheckedChangeListener
            }

            binding.wheelSpeedModeSwitch.isChecked = isChecked
            binding.pitchModeSwitch.isChecked = isChecked
            binding.eventModeSwitch.isChecked = isChecked
            binding.hallDirectionModeSwitch.isChecked = isChecked
        }
    }

    private fun setupSeekBar(
        seekBar: SeekBar,
        label: TextView,
        labelText: String,
        max: Int,
        offset: Int,
        initialProgress: Int,
        onChange: (Float) -> Unit
    ) {
        seekBar.max = max
        seekBar.progress = initialProgress
        label.text = "$labelText: ${initialProgress + offset}"
        onChange((initialProgress + offset).toFloat())

        seekBar.setOnSeekBarChangeListener(object : SimpleSeekBarChangeListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + offset
                label.text = "$labelText: $value"
                onChange(value.toFloat())
            }
        })
    }

    private fun observeWheelSpeed() {
        val source = bleService ?: return
        removeWheelSpeedObserver()
        val mainActivity = activity as? MainActivity
        speedObserver = Observer {
            val clamped = it.coerceIn(0f, 25f)
            binding.wheelSpeedSeekBar.progress = clamped.toInt()
            binding.wheelSpeedLabel.text = "Wheel Speed: ${clamped.toInt()}"
            mainActivity?.setFMODParameter("Wheel Speed", clamped)
        }
        source.speed.observe(viewLifecycleOwner, speedObserver!!)
    }

    private fun removeWheelSpeedObserver() {
        speedObserver?.let { bleService?.speed?.removeObserver(it) }
        speedObserver = null
    }

    private fun observePitch() {
        val source = bleService ?: return
        removePitchObserver()
        val mainActivity = activity as? MainActivity
        pitchObserver = Observer {
            val clamped = it.coerceIn(-45f, 45f)
            binding.pitchSeekBar.progress = (clamped + 45).toInt()
            binding.pitchLabel.text = "Pitch: ${clamped.toInt()}"
            mainActivity?.setFMODParameter("Pitch", clamped)
        }
        source.pitch.observe(viewLifecycleOwner, pitchObserver!!)
    }

    private fun removePitchObserver() {
        pitchObserver?.let { bleService?.pitch?.removeObserver(it) }
        pitchObserver = null
    }

    private fun observeEvent() {
        val source = bleService ?: return
        removeEventObserver()
        val mainActivity = activity as? MainActivity
        eventObserver = Observer {
            val index = when (it.uppercase(Locale.US)) {
                "JUMP" -> 1
                "DROP" -> 2
                else -> 0
            }
            binding.eventSpinner.setSelection(index)
            mainActivity?.setFMODParameter("Event", index.toFloat())
        }
        source.lastEvent.observe(viewLifecycleOwner, eventObserver!!)
    }

    private fun removeEventObserver() {
        eventObserver?.let { bleService?.lastEvent?.removeObserver(it) }
        eventObserver = null
    }

    private fun observeHallDirection() {
        val source = bleService ?: return
        removeHallDirectionObserver()
        val mainActivity = activity as? MainActivity
        hallDirectionObserver = Observer {
            val index = if (it == 1) 0 else 1
            binding.hallDirectionSpinner.setSelection(index)
            mainActivity?.setFMODParameter("Hall Direction", it.toFloat())
        }
        source.hallDirection.observe(viewLifecycleOwner, hallDirectionObserver!!)
    }

    private fun removeHallDirectionObserver() {
        hallDirectionObserver?.let { bleService?.hallDirection?.removeObserver(it) }
        hallDirectionObserver = null
    }

    private fun showToast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        removeWheelSpeedObserver()
        removePitchObserver()
        removeEventObserver()
        removeHallDirectionObserver()
        handler.removeCallbacksAndMessages(null)
    }

    open class SimpleSeekBarChangeListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}

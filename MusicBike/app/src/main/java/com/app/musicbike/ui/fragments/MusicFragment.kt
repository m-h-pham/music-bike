package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.app.musicbike.R
import com.app.musicbike.databinding.FragmentMusicBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.ui.activities.MainActivity
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
    private var isAutoAll = false

    private var bleService: BleService? = null

    private fun isBleConnected(): Boolean {
        return bleService?.connectionStatus?.value == "Connected"
    }

    private fun hideStatus(icon: ImageView) {
        fadeOut(icon)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    fun onServiceReady() {
        bleService = (activity as? MainActivity)?.getBleServiceInstance()
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

        // Wheel Speed
        setupSeekBar(binding.wheelSpeedSeekBar, binding.wheelSpeedLabel, "Wheel Speed", 100, 0, 0) {
            mainActivity?.setFMODParameter("Wheel Speed", it)
        }

        binding.wheelSpeedModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isWheelSpeedAuto = isChecked
            binding.wheelSpeedSeekBar.isEnabled = !isChecked
            if (isChecked && isBleConnected()) {
                val success = observeWheelSpeed()
                showStatus(binding.wheelSpeedStatusIcon, success)
                if (!success) {
                    revertSwitch(binding.wheelSpeedModeSwitch, binding.wheelSpeedSeekBar, binding.wheelSpeedStatusIcon)
                    showToast("BLE unavailable for Wheel Speed. Reverting to manual.")
                    isWheelSpeedAuto = false
                }
            } else if (isChecked) {
                revertSwitch(binding.wheelSpeedModeSwitch, binding.wheelSpeedSeekBar, binding.wheelSpeedStatusIcon)
                showToast("BLE not connected. Reverting to manual.")
                isWheelSpeedAuto = false
            } else {
                hideStatus(binding.wheelSpeedStatusIcon)
            }
        }

        // Pitch
        setupSeekBar(binding.pitchSeekBar, binding.pitchLabel, "Pitch", 90, -45, 45) {
            mainActivity?.setFMODParameter("Pitch", it)
        }

        binding.pitchModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isPitchAuto = isChecked
            binding.pitchSeekBar.isEnabled = !isChecked
            if (isChecked && isBleConnected()) {
                val success = observePitch()
                showStatus(binding.pitchStatusIcon, success)
                if (!success) {
                    revertSwitch(binding.pitchModeSwitch, binding.pitchSeekBar, binding.pitchStatusIcon)
                    showToast("BLE unavailable for Pitch. Reverting to manual.")
                    isPitchAuto = false
                }
            } else if (isChecked) {
                revertSwitch(binding.pitchModeSwitch, binding.pitchSeekBar, binding.pitchStatusIcon)
                showToast("BLE not connected. Reverting to manual.")
                isPitchAuto = false
            } else {
                hideStatus(binding.pitchStatusIcon)
            }
        }

        // Event
        val events = arrayOf("None", "Jump", "Drop")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, events)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.eventSpinner.adapter = adapter

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

        binding.eventModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isEventAuto = isChecked
            binding.eventSpinner.isEnabled = !isChecked
            if (isChecked && isBleConnected()) {
                val success = observeEvent()
                showStatus(binding.eventStatusIcon, success)
                if (!success) {
                    revertSwitch(binding.eventModeSwitch, binding.eventSpinner, binding.eventStatusIcon)
                    showToast("BLE unavailable for Event. Reverting to manual.")
                    isEventAuto = false
                }
            } else if (isChecked) {
                revertSwitch(binding.eventModeSwitch, binding.eventSpinner, binding.eventStatusIcon)
                showToast("BLE not connected. Reverting to manual.")
                isEventAuto = false
            } else {
                hideStatus(binding.eventStatusIcon)
            }
        }

        // Auto All
        binding.autoAllSwitch.setOnCheckedChangeListener { _, isChecked ->
            isAutoAll = isChecked

            if (isChecked && !isBleConnected()) {
                showToast("BLE not connected. Auto All cancelled.")
                handler.postDelayed({ binding.autoAllSwitch.isChecked = false }, 2000)
                return@setOnCheckedChangeListener
            }

            var allSuccess = true

            binding.wheelSpeedModeSwitch.isChecked = isChecked
            if (isChecked && !observeWheelSpeed()) allSuccess = false

            binding.pitchModeSwitch.isChecked = isChecked
            if (isChecked && !observePitch()) allSuccess = false

            binding.eventModeSwitch.isChecked = isChecked
            if (isChecked && !observeEvent()) allSuccess = false

            if (isChecked && !allSuccess) {
                showToast("One or more sensors unavailable. Auto All reverted.")
                handler.postDelayed({ binding.autoAllSwitch.isChecked = false }, 2500)
            }
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
        val initialValue = initialProgress + offset
        label.text = "$labelText: $initialValue"
        onChange(initialValue.toFloat())

        seekBar.setOnSeekBarChangeListener(object : SimpleSeekBarChangeListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + offset
                label.text = "$labelText: $value"
                onChange(value.toFloat())
            }
        })
    }

    private fun observeWheelSpeed(): Boolean {
        val mainActivity = activity as? MainActivity

        if (testMode) {
            val testValue = 60f
            binding.wheelSpeedLabel.text = "Wheel Speed: ${testValue.toInt()}"
            binding.wheelSpeedSeekBar.progress = testValue.toInt()
            mainActivity?.setFMODParameter("Wheel Speed", testValue)
            return true
        }

        val source = bleService ?: return false
        source.speed.observe(viewLifecycleOwner) {
            val clamped = it.coerceIn(0f, 100f)
            binding.wheelSpeedLabel.text = "Wheel Speed: ${clamped.toInt()}"
            binding.wheelSpeedSeekBar.progress = clamped.toInt()
            mainActivity?.setFMODParameter("Wheel Speed", clamped)
        }
        return true
    }

    private fun observePitch(): Boolean {
        val mainActivity = activity as? MainActivity

        if (testMode) {
            val testPitch = 15f
            binding.pitchLabel.text = "Pitch: ${testPitch.toInt()}"
            binding.pitchSeekBar.progress = (testPitch + 45).toInt()
            mainActivity?.setFMODParameter("Pitch", testPitch)
            return true
        }

        val source = bleService ?: return false
        source.pitch.observe(viewLifecycleOwner) {
            val clamped = it.coerceIn(-45f, 45f)
            binding.pitchLabel.text = "Pitch: ${clamped.toInt()}"
            binding.pitchSeekBar.progress = (clamped + 45).toInt()
            mainActivity?.setFMODParameter("Pitch", clamped)
        }
        return true
    }

    private fun observeEvent(): Boolean {
        val mainActivity = activity as? MainActivity

        if (testMode) {
            val testEvent = "JUMP"
            val index = when (testEvent.uppercase(Locale.US)) {
                "JUMP" -> 1
                "DROP" -> 2
                else -> 0
            }
            binding.eventSpinner.setSelection(index)
            mainActivity?.setFMODParameter("Event", index.toFloat())
            return true
        }

        val source = bleService ?: return false
        source.lastEvent.observe(viewLifecycleOwner) {
            val index = when (it.uppercase(Locale.US)) {
                "JUMP" -> 1
                "DROP" -> 2
                else -> 0
            }
            binding.eventSpinner.setSelection(index)
            mainActivity?.setFMODParameter("Event", index.toFloat())
        }
        return true
    }

    private fun revertSwitch(switch: Switch, control: View, icon: ImageView) {
        switch.isChecked = false
        control.isEnabled = true
        handler.postDelayed({ fadeOut(icon) }, 2500)
    }

    private fun showStatus(icon: ImageView, success: Boolean) {
        icon.setImageResource(if (success) R.drawable.ic_status_on else R.drawable.ic_status_off)
        fadeIn(icon)
        handler.postDelayed({ fadeOut(icon) }, 2500)
    }

    private fun fadeIn(view: View, duration: Long = 300) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(duration).start()
    }

    private fun fadeOut(view: View, duration: Long = 300) {
        view.animate().alpha(0f).setDuration(duration).withEndAction {
            view.visibility = View.GONE
        }.start()
    }

    private fun showToast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }

    open class SimpleSeekBarChangeListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    override fun onResume() {
        super.onResume()
        if (bleService == null && (activity as? MainActivity)?.isServiceConnected == true) {
            onServiceReady()
        }
    }
}

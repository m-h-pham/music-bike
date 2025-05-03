package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.app.musicbike.databinding.FragmentMusicBinding
import com.app.musicbike.ui.activities.MainActivity

class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!
    private var isPaused = true
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val mainActivity = activity as? MainActivity

        // Play/Pause button
        binding.toggleButton.setOnClickListener {
            mainActivity?.toggleFMODPlayback()
            isPaused = !isPaused
            binding.toggleButton.text = if (isPaused) "Play" else "Pause"
        }

        // Wheel Speed slider
        binding.wheelSpeedSeekBar.setOnSeekBarChangeListener(object : SimpleSeekBarChangeListener() {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.wheelSpeedLabel.text = "Wheel Speed: $progress"
                mainActivity?.setFMODParameter("Wheel Speed", progress.toFloat())
            }
        })

        // Pitch slider: center on 45 -> actual pitch = progress - 45
        binding.pitchSeekBar.progress = 45 // center value, i.e., pitch = 0
        binding.pitchLabel.text = "Pitch: 0"
        mainActivity?.setFMODParameter("Pitch", 0f)
        binding.pitchSeekBar.setOnSeekBarChangeListener(object : SimpleSeekBarChangeListener() {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val pitchValue = progress - 45
                binding.pitchLabel.text = "Pitch: $pitchValue"
                mainActivity?.setFMODParameter("Pitch", pitchValue.toFloat())
            }
        })

        // Event Spinner (None = 0, Jump = 1, Drop = 2)
        val events = arrayOf("None", "Jump", "Drop")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, events)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.eventSpinner.adapter = adapter

        binding.eventSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                mainActivity?.setFMODParameter("Event", position.toFloat())

                // Auto-reset Event back to "None" after 1 second for Jump or Drop
                if (position == 1 || position == 2) {
                    handler.postDelayed({
                        binding.eventSpinner.setSelection(0) // Reset to "None"
                        mainActivity?.setFMODParameter("Event", 0f)
                    }, 1000)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        })

        // TODO: Later hook in BLE sensor data here
        // e.g. mainActivity.getBleServiceInstance()?.getSensorLiveData()?.observe(...)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }

    open class SimpleSeekBarChangeListener : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }
}

package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.app.musicbike.databinding.FragmentMusicBinding

class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? com.app.musicbike.ui.activities.MainActivity

        // Toggle play/pause
        binding.toggleButton.setOnClickListener {
            mainActivity?.toggleFMODPlayback()

            // Update label based on actual FMOD state
            binding.toggleButton.postDelayed({
                val isPaused = mainActivity?.isFMODPaused() ?: true
                binding.toggleButton.text = if (isPaused) "Play" else "Pause"
            }, 100)
        }

        // Manual Wheel Speed parameter control
        binding.wheelSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.wheelSpeedLabel.text = "Wheel Speed: $progress"
                mainActivity?.setFMODParameter("Wheel Speed", progress.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

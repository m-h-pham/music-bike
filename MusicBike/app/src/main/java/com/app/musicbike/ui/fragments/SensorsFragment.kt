package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.app.musicbike.databinding.FragmentSensorsBinding // Import generated binding

class   SensorsFragment : Fragment() {

    private var _binding: FragmentSensorsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // TODO: Add logic to receive sensor data updates (from BleService via Activity/ViewModel)
        // and update the TextView
        // Example access: binding.txtSensorData.text = "Updated Sensor Data"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
    
package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.app.musicbike.databinding.FragmentMusicBinding // Import generated binding

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
        // TODO: Add logic for music controls, FMOD interaction, etc.
        // Example access: binding.textMusicPlaceholder.text = "..."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
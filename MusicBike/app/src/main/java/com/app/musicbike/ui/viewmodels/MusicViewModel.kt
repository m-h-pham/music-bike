package com.app.musicbike.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import com.app.musicbike.services.MusicService

class MusicViewModel : ViewModel() {

    private val TAG = "MusicViewModel"

    private var musicService: MusicService? = null

    // LiveData for UI state observed by MusicFragment
    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> get() = _isPlaying

    // ... (other LiveData like _currentBankName, _wheelSpeedDisplay, etc., remain the same)
    private val _currentBankName = MutableLiveData<String>("None")
    val currentBankName: LiveData<String> get() = _currentBankName

    private val _wheelSpeedDisplay = MutableLiveData<Float>(0f)
    val wheelSpeedDisplay: LiveData<Float> get() = _wheelSpeedDisplay

    private val _pitchDisplay = MutableLiveData<Float>(0f)
    val pitchDisplay: LiveData<Float> get() = _pitchDisplay

    private val _eventDisplayValue = MutableLiveData<Float>(0f)
    val eventDisplayValue: LiveData<Float> get() = _eventDisplayValue

    private val _hallDirectionDisplayValue = MutableLiveData<Float>(1f)
    val hallDirectionDisplayValue: LiveData<Float> get() = _hallDirectionDisplayValue

    // Observers for MusicService LiveData
    private var serviceLifecycleOwner: LifecycleOwner? = null
    private val fmodSpeedObserver = Observer<Float> { speed -> _wheelSpeedDisplay.postValue(speed) }
    private val fmodPitchObserver = Observer<Float> { pitch -> _pitchDisplay.postValue(pitch) }
    private val fmodEventObserver = Observer<Float> { eventParam -> _eventDisplayValue.postValue(eventParam) }
    private val fmodHallDirObserver = Observer<Float> { directionParam -> _hallDirectionDisplayValue.postValue(directionParam) }


    fun setMusicService(service: MusicService?, owner: LifecycleOwner) {
        // ADD/MODIFY LOGS HERE
        Log.d(TAG, "ViewModel setMusicService called. Incoming service is ${if (service == null) "NULL" else "NOT NULL"}.")

        removeServiceObservers() // Remove from old instance if any

        this.musicService = service
        this.serviceLifecycleOwner = owner // Store owner to manage observers correctly

        if (this.musicService != null) {
            _isPlaying.postValue(this.musicService!!.isPlaying()) // Initial state
            this.musicService!!.currentFmodSpeed.observe(owner, fmodSpeedObserver)
            this.musicService!!.currentFmodPitch.observe(owner, fmodPitchObserver)
            this.musicService!!.currentFmodEventParameter.observe(owner, fmodEventObserver)
            this.musicService!!.currentFmodHallDirection.observe(owner, fmodHallDirObserver)
            Log.d(TAG, "MusicService instance set in ViewModel and observers attached.")
        } else {
            Log.w(TAG, "MusicService instance is NULL after trying to set it in ViewModel.")
        }
    }

    private fun removeServiceObservers() {
        serviceLifecycleOwner?.let { owner -> // Use stored owner
            musicService?.currentFmodSpeed?.removeObserver(fmodSpeedObserver)
            musicService?.currentFmodPitch?.removeObserver(fmodPitchObserver)
            musicService?.currentFmodEventParameter?.removeObserver(fmodEventObserver)
            musicService?.currentFmodHallDirection?.removeObserver(fmodHallDirObserver)
        }
        // Log.d(TAG, "Removed observers from previous MusicService instance if any.")
    }

    fun togglePlayback() {
        // ADD LOGS HERE
        Log.d(TAG, "ViewModel togglePlayback() ENTERED.")
        if (musicService == null) {
            Log.e(TAG, "ViewModel togglePlayback: musicService is NULL! Cannot send command.")
            return
        }
        Log.d(TAG, "ViewModel togglePlayback: musicService is available. Calling service.togglePlayback().")
        musicService?.togglePlayback()
        // Update LiveData based on the new state from the service
        _isPlaying.postValue(musicService?.isPlaying() ?: false) // isPlaying should also be non-null if service is non-null
        Log.d(TAG, "ViewModel togglePlayback() EXITED. New isPlaying LiveData: ${isPlaying.value}")
    }

    // ... (loadBank, setFmodParameter, playFmodEvent methods remain the same) ...
    fun loadBank(masterBankPath: String, stringsBankPath: String, bankDisplayName: String) {
        musicService?.loadBank(masterBankPath, stringsBankPath)
        _currentBankName.postValue(bankDisplayName)
        _isPlaying.postValue(false)
        Log.d(TAG, "loadBank called for: $bankDisplayName")
    }

    fun setFmodParameter(name: String, value: Float) {
        if (_isWheelSpeedAuto.value == true && name == "Wheel Speed") return
        if (_isPitchAuto.value == true && name == "Pitch") return
        if (_isEventAuto.value == true && name == "Event") return
        if (_isHallDirectionAuto.value == true && name == "Hall Direction") return

        musicService?.setFmodParameter(name, value)
        Log.d(TAG, "setFmodParameter (manual command): $name to $value")
    }

    fun playFmodEvent() {
        musicService?.playFmodEvent()
        Log.d(TAG, "playFmodEvent (generic) called from ViewModel")
    }


    // --- Auto Mode Logic (setters call MusicService) ---
    private val _isWheelSpeedAuto = MutableLiveData<Boolean>(false)
    val isWheelSpeedAuto: LiveData<Boolean> get() = _isWheelSpeedAuto
    private val _isPitchAuto = MutableLiveData<Boolean>(false)
    val isPitchAuto: LiveData<Boolean> get() = _isPitchAuto
    private val _isEventAuto = MutableLiveData<Boolean>(false)
    val isEventAuto: LiveData<Boolean> get() = _isEventAuto
    private val _isHallDirectionAuto = MutableLiveData<Boolean>(false)
    val isHallDirectionAuto: LiveData<Boolean> get() = _isHallDirectionAuto

    fun setWheelSpeedAuto(isAuto: Boolean) {
        _isWheelSpeedAuto.postValue(isAuto)
        musicService?.setAutoSpeedMode(isAuto)
        Log.d(TAG, "setWheelSpeedAuto: $isAuto")
    }
    fun setPitchAuto(isAuto: Boolean) {
        _isPitchAuto.postValue(isAuto)
        musicService?.setAutoPitchMode(isAuto)
        Log.d(TAG, "setPitchAuto: $isAuto")
    }
    fun setEventAuto(isAuto: Boolean) {
        _isEventAuto.postValue(isAuto)
        musicService?.setAutoEventMode(isAuto)
        Log.d(TAG, "setEventAuto: $isAuto")
    }
    fun setHallDirectionAuto(isAuto: Boolean) {
        _isHallDirectionAuto.postValue(isAuto)
        musicService?.setAutoHallDirectionMode(isAuto)
        Log.d(TAG, "setHallDirectionAuto: $isAuto")
    }
    // ---

    override fun onCleared() {
        super.onCleared()
        removeServiceObservers()
        Log.d(TAG, "MusicViewModel cleared.")
    }
}
package com.app.musicbike.ui.viewmodels

import android.app.Application // Import Application for AndroidViewModel
import android.content.Context // Import Context for SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
//import androidx.lifecycle.ViewModel
import com.app.musicbike.services.MusicService

// Change ViewModel to AndroidViewModel to easily access SharedPreferences via applicationContext
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MusicViewModel"
    private val prefs = application.getSharedPreferences("MusicBikeRideStats", Context.MODE_PRIVATE)

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

    // ---LiveData for Ride Stats ---
    private val _maxSpeed = MutableLiveData<Float>(0f)
    val maxSpeed: LiveData<Float> get() = _maxSpeed

    private val _maxPositivePitch = MutableLiveData<Float>(0f)
    val maxPositivePitch: LiveData<Float> get() = _maxPositivePitch

    private val _minNegativePitch = MutableLiveData<Float>(0f) // Stores as positive, display as negative
    val minNegativePitch: LiveData<Float> get() = _minNegativePitch

    private val _jumpCount = MutableLiveData<Int>(0)
    val jumpCount: LiveData<Int> get() = _jumpCount

    private val _dropCount = MutableLiveData<Int>(0)
    val dropCount: LiveData<Int> get() = _dropCount
    // --- END stats---

    // Observers for MusicService LiveData
    private var serviceLifecycleOwner: LifecycleOwner? = null
    private val fmodSpeedObserver = Observer<Float> { speed ->
        _wheelSpeedDisplay.postValue(speed)
        updateMaxSpeedStat(speed) // ADDED: Update stat
    }
    private val fmodPitchObserver = Observer<Float> { pitch ->
        _pitchDisplay.postValue(pitch)
        updatePitchStats(pitch) // ADDED: Update stat
    }
    private val fmodEventObserver = Observer<Float> { eventParam -> // eventParam from MusicService is 0, 1, or 2
        _eventDisplayValue.postValue(eventParam)
        updateEventCounts(eventParam) // ADDED: Update stat
    }
    private val fmodHallDirObserver = Observer<Float> { /* Not directly used for these stats yet */ }


    init {
        loadStats() // Load stats when ViewModel is created
    }

    fun setMusicService(service: MusicService?, owner: LifecycleOwner) {
        removeServiceObservers()
        this.musicService = service
        this.serviceLifecycleOwner = owner

        if (this.musicService != null) {
            _isPlaying.postValue(this.musicService!!.isPlaying())

            this.musicService!!.currentFmodSpeed.observe(owner, fmodSpeedObserver)
            this.musicService!!.currentFmodPitch.observe(owner, fmodPitchObserver)
            this.musicService!!.currentFmodEventParameter.observe(owner, fmodEventObserver)
            this.musicService!!.currentFmodHallDirection.observe(owner, fmodHallDirObserver)
            Log.d(TAG, "MusicService instance set and observers attached.")
        } else {
            Log.w(TAG, "MusicService instance is null in ViewModel.")
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
    private val _isPitchSignalReversed = MutableLiveData<Boolean>(loadPitchSignalReversalPreference()) // Initialize from SharedPreferences
    val isPitchSignalReversed: LiveData<Boolean> get() = _isPitchSignalReversed

    fun setWheelSpeedAuto(isAuto: Boolean) {
        _isWheelSpeedAuto.postValue(isAuto)
        musicService?.setAutoSpeedMode(isAuto)
        saveBooleanPreference("isWheelSpeedAuto", isAuto)
        Log.d(TAG, "setWheelSpeedAuto: $isAuto")
    }
    fun setPitchAuto(isAuto: Boolean) {
        _isPitchAuto.postValue(isAuto)
        musicService?.setAutoPitchMode(isAuto)
        saveBooleanPreference("isPitchAuto", isAuto)
        Log.d(TAG, "setPitchAuto: $isAuto")
    }
    fun setEventAuto(isAuto: Boolean) {
        _isEventAuto.postValue(isAuto)
        musicService?.setAutoEventMode(isAuto)
        saveBooleanPreference("isEventAuto", isAuto)
        Log.d(TAG, "setEventAuto: $isAuto")
    }
    fun setHallDirectionAuto(isAuto: Boolean) {
        _isHallDirectionAuto.postValue(isAuto)
        musicService?.setAutoHallDirectionMode(isAuto)
        saveBooleanPreference("isHallDirectionAuto", isAuto)
        Log.d(TAG, "setHallDirectionAuto: $isAuto")
    }
    fun togglePitchSignalReversal() {
        val newState = !(_isPitchSignalReversed.value ?: false)
        _isPitchSignalReversed.postValue(newState)
        musicService?.setPitchSignalReversal(newState)
        saveBooleanPreference("isPitchSignalReversed", newState)
        Log.d(TAG, "Pitch signal reversal toggled to: $newState")
    }

    private fun savePitchSignalReversalPreference(isReversed: Boolean) {
        // TODO: Implement SharedPreferences saving for pitch reversal
        // Example:
        // val prefs = applicationContext.getSharedPreferences("MusicBikePrefs", Context.MODE_PRIVATE)
        // prefs.edit().putBoolean("pitch_signal_reversed", isReversed).apply()
        Log.d(TAG, "Pitch reversal preference saved: $isReversed (Implement SharedPreferences)")
    }

    private fun loadPitchSignalReversalPreference(): Boolean {
        // TODO: Implement SharedPreferences loading for pitch reversal
        // Example:
        // val prefs = applicationContext.getSharedPreferences("MusicBikePrefs", Context.MODE_PRIVATE)
        // return prefs.getBoolean("pitch_signal_reversed", false) // Default to false
        Log.d(TAG, "Pitch reversal preference loaded (Implement SharedPreferences)")
        return false // Default placeholder
    }

    // --- Stat Update and Persistence Logic ---
    private fun updateMaxSpeedStat(currentSpeed: Float) {
        if (currentSpeed > (_maxSpeed.value ?: 0f)) {
            _maxSpeed.postValue(currentSpeed)
            prefs.edit().putFloat("maxSpeed", currentSpeed).apply()
        }
    }

    private fun updatePitchStats(currentPitch: Float) {
        if (currentPitch > 0 && currentPitch > (_maxPositivePitch.value ?: 0f)) {
            _maxPositivePitch.postValue(currentPitch)
            prefs.edit().putFloat("maxPositivePitch", currentPitch).apply()
        } else if (currentPitch < 0 && currentPitch < -(_minNegativePitch.value ?: 0f) ) { // store as positive
            _minNegativePitch.postValue(-currentPitch) // store absolute value
            prefs.edit().putFloat("minNegativePitch", -currentPitch).apply()
        }
    }

    private fun updateEventCounts(eventParam: Float) { // Assuming 1.0f for Jump, 2.0f for Drop
        when (eventParam.toInt()) {
            1 -> { // Jump
                val newCount = (_jumpCount.value ?: 0) + 1
                _jumpCount.postValue(newCount)
                prefs.edit().putInt("jumpCount", newCount).apply()
            }
            2 -> { // Drop
                val newCount = (_dropCount.value ?: 0) + 1
                _dropCount.postValue(newCount)
                prefs.edit().putInt("dropCount", newCount).apply()
            }
        }
    }

    fun resetStats() {
        _maxSpeed.postValue(0f)
        _maxPositivePitch.postValue(0f)
        _minNegativePitch.postValue(0f)
        _jumpCount.postValue(0)
        _dropCount.postValue(0)

        prefs.edit()
            .putFloat("maxSpeed", 0f)
            .putFloat("maxPositivePitch", 0f)
            .putFloat("minNegativePitch", 0f)
            .putInt("jumpCount", 0)
            .putInt("dropCount", 0)
            .apply()
        Log.i(TAG, "Ride stats reset.")
    }

    private fun loadStats() {
        _maxSpeed.value = prefs.getFloat("maxSpeed", 0f)
        _maxPositivePitch.value = prefs.getFloat("maxPositivePitch", 0f)
        _minNegativePitch.value = prefs.getFloat("minNegativePitch", 0f)
        _jumpCount.value = prefs.getInt("jumpCount", 0)
        _dropCount.value = prefs.getInt("dropCount", 0)
        Log.i(TAG, "Ride stats loaded from SharedPreferences.")
    }

    // Helper for boolean preferences (used for auto modes and pitch reversal)
    private fun saveBooleanPreference(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun loadBooleanPreference(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }


    override fun onCleared() {
        super.onCleared()
        removeServiceObservers()
        Log.d(TAG, "MusicViewModel cleared.")
    }
}
package com.app.musicbike.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.app.musicbike.R

class MusicService : Service() {

    private val TAG = "MusicService"
    private val binder = LocalBinder()
    private lateinit var notificationManager: NotificationManager

    // --- FMOD Native Interface ---
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "MusicServiceChannel"
        private const val NOTIFICATION_CHANNEL_NAME = "Music Playback Service"
        private const val ONGOING_NOTIFICATION_ID = 102

        init {
            try {
                Log.d("FMOD_MusicService", "Attempting to load FMOD libraries from MusicService...")
                System.loadLibrary("fmod")
                System.loadLibrary("fmodstudio")
                System.loadLibrary("musicbike")
                Log.d("FMOD_MusicService", "FMOD libraries loaded successfully by MusicService.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("FMOD_MusicService", "Failed to load FMOD libraries in MusicService.", e)
            }
        }
    }

    private external fun nativeStartFMODPlayback(masterBankPath: String, stringsBankPath: String)
    private external fun nativeSetFMODParameter(paramName: String, value: Float)
    private external fun nativeToggleFMODPlayback()
    private external fun nativePlayFMODEvent()
    private external fun nativeIsFMODPaused(): Boolean
    private external fun nativeStopFMODUpdateThread()
    // --- End FMOD Native Interface ---

    // --- BleService Connection ---
    private var bleService: BleService? = null
    private var bleServiceBound = false
    private val bleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected: BleService connection established in MusicService.")
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            bleServiceBound = true

            bleService?.speed?.observeForever(speedObserver)
            bleService?.pitch?.observeForever(pitchObserver)
            bleService?.lastEvent?.observeForever(eventObserver)
            bleService?.hallDirection?.observeForever(hallDirectionObserver)
            Log.d(TAG, "MusicService now observing BleService LiveData (including Hall Direction).")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: BleService connection lost in MusicService.")
            bleService?.speed?.removeObserver(speedObserver)
            bleService?.pitch?.removeObserver(pitchObserver)
            bleService?.lastEvent?.removeObserver(eventObserver)
            bleService?.hallDirection?.removeObserver(hallDirectionObserver)
            bleService = null
            bleServiceBound = false
        }
    }
    // --- End BleService Connection ---

    private var isSpeedInAutoMode = false
    private var isPitchInAutoMode = false
    private var isEventInAutoMode = false
    private var isHallDirectionInAutoMode = false

    private val _currentFmodSpeed = MutableLiveData<Float>(0.0f)
    val currentFmodSpeed: LiveData<Float> get() = _currentFmodSpeed
    private val _currentFmodPitch = MutableLiveData<Float>(0.0f)
    val currentFmodPitch: LiveData<Float> get() = _currentFmodPitch
    private val _currentFmodEventParameter = MutableLiveData<Float>(0.0f)
    val currentFmodEventParameter: LiveData<Float> get() = _currentFmodEventParameter
    private val _currentFmodHallDirection = MutableLiveData<Float>(1.0f)
    val currentFmodHallDirection: LiveData<Float> get() = _currentFmodHallDirection

    private val speedObserver = Observer<Float> { speed ->
        if (isSpeedInAutoMode) {
            val clampedSpeed = speed.coerceIn(0f, 25f)
            Log.d(TAG, "AUTO MODE: Setting Wheel Speed to $clampedSpeed")
            setFmodParameterInternal("Wheel Speed", clampedSpeed)
        }
    }
    private val pitchObserver = Observer<Float> { pitch ->
        if (isPitchInAutoMode) {
            val clampedPitch = pitch.coerceIn(-45f, 45f)
            Log.d(TAG, "AUTO MODE: Setting Pitch to $clampedPitch")
            setFmodParameterInternal("Pitch", clampedPitch)
        }
    }
    private val eventObserver = Observer<String> { event ->
        if (isEventInAutoMode && event != "NONE") {
            Log.d(TAG, "AUTO MODE: Received event $event, setting FMOD event parameter.")
            val eventValue = when (event.uppercase(java.util.Locale.US)) {
                "JUMP" -> 1.0f
                "DROP" -> 2.0f
                else -> -1.0f
            }
            if (eventValue != -1.0f) {
                setFmodParameterInternal("Event", eventValue)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isEventInAutoMode && _currentFmodEventParameter.value == eventValue) {
                        Log.d(TAG, "AUTO MODE: Resetting Event parameter to 0.0f")
                        setFmodParameterInternal("Event", 0.0f)
                    }
                }, 500)
            }
        }
    }
    private val hallDirectionObserver = Observer<Int> { direction ->
        if (isHallDirectionInAutoMode) {
            Log.d(TAG, "AUTO MODE: Setting Hall Direction to $direction")
            setFmodParameterInternal("Hall Direction", direction.toFloat())
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "onCreate: MusicService created.")

        org.fmod.FMOD.init(this)
        val fmodIsInitialized: Boolean = org.fmod.FMOD.checkInit()
        Log.d(TAG, "FMOD.checkInit() result in MusicService: $fmodIsInitialized")
        if (!fmodIsInitialized) {
            Log.e(TAG, "FMOD initialization failed in MusicService! Stopping service.")
            stopSelf()
            return
        } else {
            Log.i(TAG, "FMOD initialized successfully in MusicService.")
        }

        val bleServiceIntent = Intent(this, BleService::class.java)
        bindService(bleServiceIntent, bleServiceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "MusicService attempting to bind to BleService.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: MusicService starting.")
        val notification = buildNotification(if (isPlaying()) "Playing Music..." else "Music Service Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "MusicService started in foreground.")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            // Removed: description = "Channel for background music playback service"
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val iconResId = R.drawable.ic_music_notification
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Music Bike Playback")
            .setContentText(contentText)
            .setSmallIcon(iconResId)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateNotification(newContentText: String) {
        if (::notificationManager.isInitialized) {
            notificationManager.notify(ONGOING_NOTIFICATION_ID, buildNotification(newContentText))
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind: MusicService bound by a client.")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: MusicService being destroyed.")
        if (bleServiceBound) {
            bleService?.speed?.removeObserver(speedObserver)
            bleService?.pitch?.removeObserver(pitchObserver)
            bleService?.lastEvent?.removeObserver(eventObserver)
            bleService?.hallDirection?.removeObserver(hallDirectionObserver)
            unbindService(bleServiceConnection)
            bleServiceBound = false
            bleService = null
        }
        nativeStopFMODUpdateThread()
        stopForeground(true)
        org.fmod.FMOD.close()
        Log.d(TAG, "FMOD closed in MusicService.")
        Log.d(TAG, "MusicService onDestroy completed.")
    }

    fun play() {
        Log.d(TAG, "play() called.")
        if (nativeIsFMODPaused()) {
            nativeToggleFMODPlayback()
        }
        updateNotification(if (isPlaying()) "Playing Music..." else "Playback Error")
    }

    fun pause() {
        Log.d(TAG, "pause() called.")
        if (!nativeIsFMODPaused()) {
            nativeToggleFMODPlayback()
        }
        updateNotification("Music Paused")
    }

    fun togglePlayback() {
        Log.d(TAG, "togglePlayback() called.")
        nativeToggleFMODPlayback()
        updateNotification(if (isPlaying()) "Playing Music..." else "Music Paused")
    }

    fun loadBank(masterBankPath: String, stringsBankPath: String) {
        Log.d(TAG, "loadBank() called with master: $masterBankPath, strings: $stringsBankPath")
        nativeStartFMODPlayback(masterBankPath, stringsBankPath)
        updateNotification("Bank Loaded: ${masterBankPath.substringAfterLast('/')}")
    }

    fun setFmodParameter(name: String, value: Float) {
        Log.d(TAG, "setFmodParameter (public): $name to $value")
        setFmodParameterInternal(name, value)
    }

    private fun setFmodParameterInternal(name: String, value: Float) {
        nativeSetFMODParameter(name, value)
        when (name) {
            "Wheel Speed" -> _currentFmodSpeed.postValue(value)
            "Pitch" -> _currentFmodPitch.postValue(value)
            "Event" -> _currentFmodEventParameter.postValue(value)
            "Hall Direction" -> _currentFmodHallDirection.postValue(value)
        }
    }

    fun playFmodEvent() {
        Log.d(TAG, "playFmodEvent() called.")
        nativePlayFMODEvent()
    }

    // CORRECTED isPlaying() function
    fun isPlaying(): Boolean {
        val isPaused = nativeIsFMODPaused()
        // Log.d(TAG, "isPlaying() -> nativeIsFMODPaused() returned: $isPaused. So, playing is ${!isPaused}")
        return !isPaused // Ensure this line exists and is correct
    }


    fun setAutoSpeedMode(enabled: Boolean) {
        isSpeedInAutoMode = enabled
        Log.d(TAG, "Auto Speed Mode set to: $enabled")
        if (enabled) {
            bleService?.speed?.value?.let { currentSpeed ->
                val clampedSpeed = currentSpeed.coerceIn(0f, 25f)
                Log.d(TAG, "AUTO MODE ON: Initializing Wheel Speed to $clampedSpeed")
                setFmodParameterInternal("Wheel Speed", clampedSpeed)
            }
        }
    }

    fun setAutoPitchMode(enabled: Boolean) {
        isPitchInAutoMode = enabled
        Log.d(TAG, "Auto Pitch Mode set to: $enabled")
        if (enabled) {
            bleService?.pitch?.value?.let { currentPitch ->
                val clampedPitch = currentPitch.coerceIn(-45f, 45f)
                Log.d(TAG, "AUTO MODE ON: Initializing Pitch to $clampedPitch")
                setFmodParameterInternal("Pitch", clampedPitch)
            }
        }
    }

    fun setAutoEventMode(enabled: Boolean) {
        isEventInAutoMode = enabled
        Log.d(TAG, "Auto Event Mode set to: $enabled")
        if (!enabled) {
            setFmodParameterInternal("Event", 0.0f)
        }
    }

    fun setAutoHallDirectionMode(enabled: Boolean) {
        isHallDirectionInAutoMode = enabled
        Log.d(TAG, "Auto Hall Direction Mode set to: $enabled")
        if (enabled) {
            bleService?.hallDirection?.value?.let { currentDirection ->
                Log.d(TAG, "AUTO MODE ON: Initializing Hall Direction to $currentDirection")
                setFmodParameterInternal("Hall Direction", currentDirection.toFloat())
            }
        }
    }
}
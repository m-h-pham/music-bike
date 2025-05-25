package com.app.musicbike.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.app.musicbike.R
import com.app.musicbike.databinding.ActivityMainBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.services.MusicService
import com.app.musicbike.ui.adapter.ViewPagerAdapter
import com.app.musicbike.ui.fragments.DevicesFragment
import com.app.musicbike.ui.fragments.MusicFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding

    private var bleServiceBound = false
    private var bleService: BleService? = null
    var isBleServiceConnected = false // Renamed for clarity
        private set

    private var musicServiceBound = false
    private var musicService: MusicService? = null
    var isMusicServiceConnected = false
        private set

    private val bleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected: BleService connection established.")
            val binder = service as? BleService.LocalBinder // Use safe cast
            if (binder != null) {
                bleService = binder.getService() // This should now resolve if BleService.LocalBinder is correct
                bleServiceBound = true
                isBleServiceConnected = true
                Log.d(TAG, "BleService connected variable set: $isBleServiceConnected")
                notifyDevicesFragmentBleServiceReady()
                notifyMusicFragmentBleServiceReady()
            } else {
                Log.e(TAG, "Failed to cast binder to BleService.LocalBinder")
                isBleServiceConnected = false // Ensure this is false if cast fails
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: BleService connection lost.")
            bleService = null
            bleServiceBound = false
            isBleServiceConnected = false
        }
    }

    private val musicServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected: MusicService connection established.")
            val binder = service as? MusicService.LocalBinder // Use safe cast
            if (binder != null) {
                musicService = binder.getService()
                musicServiceBound = true
                isMusicServiceConnected = true
                Log.d(TAG, "MusicService connected variable set: $isMusicServiceConnected")

                val masterBankPath = copyAssetToInternalStorage("Master.bank")
                val stringsBankPath = copyAssetToInternalStorage("Master.strings.bank")
                Log.d(TAG, "MusicService connected, commanding it to load initial banks.")
                musicService?.loadBank(masterBankPath, stringsBankPath)
                notifyMusicFragmentMusicServiceReady()
            } else {
                Log.e(TAG, "Failed to cast binder to MusicService.LocalBinder")
                isMusicServiceConnected = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: MusicService connection lost.")
            musicService = null
            musicServiceBound = false
            isMusicServiceConnected = false
        }
    }

    // FMOD JNI related code has been moved to MusicService
    // companion object { /* ... */ }
    // external fun ...

    private fun copyAssetToInternalStorage(assetName: String): String {
        val file = File(filesDir, assetName)
        if (file.exists()) {
            file.delete()
        }
        try {
            assets.open(assetName).use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetName", e)
            return ""
        }
        return file.absolutePath
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, statusBarHeight, view.paddingRight, view.paddingBottom)
            insets
        }
        toolbar.requestApplyInsets()
        setupViewPager()

        Log.d(TAG, "onCreate: Starting and binding to BleService...")
        startAndBindBleService()

        Log.d(TAG, "onCreate: Starting and binding to MusicService...")
        startAndBindMusicService()

        // FMOD init is now handled by MusicService
    }

    private fun startAndBindBleService() {
        val serviceIntent = Intent(this, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, bleServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startAndBindMusicService() {
        val serviceIntent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, musicServiceConnection, Context.BIND_AUTO_CREATE)
    }

    fun getBleServiceInstance(): BleService? {
        return if (bleServiceBound) bleService else null
    }

    fun getMusicServiceInstance(): MusicService? {
        return if (musicServiceBound) musicService else null
    }

    private fun notifyDevicesFragmentBleServiceReady() {
        val fragment = supportFragmentManager.findFragmentByTag("f2")
        if (fragment is DevicesFragment) {
            fragment.onServiceReady()
        } else {
            Log.w(TAG, "notifyDevicesFragmentBleServiceReady: Could not find DevicesFragment (f2).")
        }
    }

    private fun notifyMusicFragmentBleServiceReady() { // For BleService
        val fragment = supportFragmentManager.findFragmentByTag("f0")
        if (fragment is MusicFragment) {
            fragment.onServiceReady()
        } else {
            Log.w(TAG, "notifyMusicFragmentBleServiceReady: Could not find MusicFragment (f0).")
        }
    }

    private fun notifyMusicFragmentMusicServiceReady() { // For MusicService
        val fragment = supportFragmentManager.findFragmentByTag("f0")
        if (fragment is MusicFragment) {
            fragment.onMusicServiceReady(musicService) // Pass the MusicService instance
        } else {
            Log.w(TAG, "notifyMusicFragmentMusicServiceReady: Could not find MusicFragment (f0).")
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(supportFragmentManager, lifecycle)
        val viewPager: ViewPager2 = binding.viewPager
        val tabLayout: TabLayout = binding.tabLayout
        viewPager.adapter = adapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Music"
                1 -> "Sensors"
                2 -> "Devices"
                else -> null
            }
        }.attach()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bleServiceBound) {
            unbindService(bleServiceConnection)
            bleServiceBound = false
        }
        if (musicServiceBound) {
            unbindService(musicServiceConnection)
            musicServiceBound = false
        }
        // FMOD.close() is now handled by MusicService
        Log.d(TAG, "MainActivity onDestroy completed.")
    }
}
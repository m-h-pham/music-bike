package com.app.musicbike.ui.activities

import android.Manifest // ADDED for Manifest.permission
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager // ADDED for PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast // ADDED for Toast
import androidx.activity.result.contract.ActivityResultContracts // ADDED for permission launcher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding

    private var bleServiceBound = false
    private var bleService: BleService? = null
    var isBleServiceConnected = false
        private set

    private var musicServiceBound = false
    private var musicService: MusicService? = null
    var isMusicServiceConnected = false
        private set

    // --- ADDED: Permission Launcher for POST_NOTIFICATIONS ---
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted.")
                // You can now expect services to show notifications.
                // If services were already started, their existing notifications (if any were attempted)
                // might now appear, or they will appear on their next foreground promotion.
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied by user.")
                Toast.makeText(this, "Notification permission denied. Service indicators may not be visible.", Toast.LENGTH_LONG).show()
            }
        }
    // --- END ADDED ---

    private val bleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected: BleService connection established.")
            val binder = service as? BleService.LocalBinder
            if (binder != null) {
                bleService = binder.getService()
                bleServiceBound = true
                isBleServiceConnected = true
                Log.d(TAG, "BleService connected variable set: $isBleServiceConnected")
                notifyDevicesFragmentBleServiceReady()
                notifyMusicFragmentBleServiceReady() // For BleService to MusicFragment
            } else {
                Log.e(TAG, "Failed to cast binder to BleService.LocalBinder")
                isBleServiceConnected = false
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
            val binder = service as? MusicService.LocalBinder
            if (binder != null) {
                musicService = binder.getService()
                musicServiceBound = true
                isMusicServiceConnected = true
                Log.d(TAG, "MusicService connected variable set: $isMusicServiceConnected")

                lifecycleScope.launch {
                    Log.d(TAG, "Coroutine: Copying assets and loading banks...")
                    val masterBankPath = withContext(Dispatchers.IO) {
                        copyAssetToInternalStorage("Master.bank")
                    }
                    val stringsBankPath = withContext(Dispatchers.IO) {
                        copyAssetToInternalStorage("Master.strings.bank")
                    }

                    if (masterBankPath.isNotEmpty()) {
                        Log.d(TAG, "Coroutine: Commanding MusicService to load initial banks.")
                        musicService?.loadBank(masterBankPath, stringsBankPath)
                    } else {
                        Log.e(TAG, "Coroutine: Failed to get valid bank paths for initial load.")
                    }
                    Log.d(TAG, "Coroutine: Bank loading process finished.")
                }
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

    // FMOD JNI has been moved to MusicService.
    // The companion object for loading libraries is also in MusicService now.
    // The external fun declarations are also in MusicService.

    private fun copyAssetToInternalStorage(assetName: String): String {
        val file = File(filesDir, assetName)
        if (file.exists()) {
            file.delete()
            // Log.d(TAG, "Deleted existing $assetName to ensure updated bank is used.")
        }
        try {
            assets.open(assetName).use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // Log.d(TAG, "Copied asset $assetName to ${file.absolutePath}")
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

        // --- ADDED: Request Notification Permission ---
        checkAndRequestNotificationPermission()
        // --- END ADDED ---

        Log.d(TAG, "onCreate: Starting and binding to BleService...")
        startAndBindBleService()

        Log.d(TAG, "onCreate: Starting and binding to MusicService...")
        startAndBindMusicService()

        // FMOD org.fmod.FMOD.init() and initial FMOD calls are now handled by MusicService
    }

    // --- ADDED: Method to Check and Request Notification Permission ---
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is Android 13 (API 33)
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.i(TAG, "POST_NOTIFICATIONS permission already granted.")
                    // Permission is already granted, services can show notifications.
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // This part is for showing a custom UI to explain why the permission is needed.
                    // For now, we'll log and then request. In a production app, show a dialog.
                    Log.w(TAG, "POST_NOTIFICATIONS: Rationale should be shown. Requesting permission now.")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // Directly ask for the permission for the first time or if rationale not needed.
                    Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No runtime permission needed for POST_NOTIFICATIONS before Android 13
            Log.d(TAG, "POST_NOTIFICATIONS permission not required for this Android version (SDK < 33).")
        }
    }
    // --- END ADDED ---

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
        val fragment = supportFragmentManager.findFragmentByTag("f2") // DevicesFragment is at position 2
        if (fragment is DevicesFragment) {
            fragment.onServiceReady()
        } else {
            Log.w(TAG, "notifyDevicesFragmentBleServiceReady: Could not find DevicesFragment (f2). It might not be created yet.")
        }
    }

    private fun notifyMusicFragmentBleServiceReady() { // For BleService
        val fragment = supportFragmentManager.findFragmentByTag("f0") // MusicFragment is at position 0
        if (fragment is MusicFragment) {
            fragment.onServiceReady() // This is MusicFragment's existing method for BleService
        } else {
            Log.w(TAG, "notifyMusicFragmentBleServiceReady: Could not find MusicFragment (f0). It might not be created yet.")
        }
    }

    private fun notifyMusicFragmentMusicServiceReady() { // For MusicService
        val fragment = supportFragmentManager.findFragmentByTag("f0") // MusicFragment is at position 0
        if (fragment is MusicFragment) {
            fragment.onMusicServiceReady(musicService) // Pass the MusicService instance
        } else {
            Log.w(TAG, "notifyMusicFragmentMusicServiceReady: Could not find MusicFragment (f0). It might not be created yet.")
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
            bleServiceBound = false // Reset flag
            isBleServiceConnected = false
        }
        if (musicServiceBound) {
            unbindService(musicServiceConnection)
            musicServiceBound = false // Reset flag
            isMusicServiceConnected = false
        }
        Log.d(TAG, "MainActivity onDestroy completed.")
        // FMOD.close() is handled by MusicService
    }
}
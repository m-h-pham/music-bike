package com.app.musicbike.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.app.musicbike.databinding.ActivityMainBinding
import com.app.musicbike.services.BleService
import com.app.musicbike.ui.adapter.ViewPagerAdapter
import com.app.musicbike.ui.fragments.DevicesFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity" // Keep TAG for logging
    private lateinit var binding: ActivityMainBinding
    private var bound = false
    private var bleService: BleService? = null
    var isServiceConnected = false
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // --- ADD LOG HERE ---
            Log.d(TAG, "onServiceConnected: Service connection established.")
            // --- END LOG ---

            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            bound = true
            isServiceConnected = true
            Log.d(TAG, "Service connected variable set.") // Existing log

            notifyDevicesFragmentServiceReady() // Call the notification function
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // --- ADD LOG HERE ---
            Log.w(TAG, "onServiceDisconnected: Service connection lost.")
            // --- END LOG ---
            bleService = null
            bound = false
            isServiceConnected = false
            Log.d(TAG, "Service disconnected variable set.") // Existing log
        }
    }

    companion object {
        init {
            try {
                Log.d("FMOD", "Attempting to load FMOD library...")
                System.loadLibrary("fmod")
                System.loadLibrary("fmodstudio")
                System.loadLibrary("musicbike") // YOUR native library!
                Log.d("FMOD", "FMOD library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("FMOD", "Failed to load FMOD library.", e)
            }
        }
    }

    external fun startFMODPlayback(masterBankPath: String, stringsBankPath: String)

    external fun setFMODParameter(paramName: String, value: Float)

    external fun toggleFMODPlayback()

    external fun playFMODEvent()

    external fun isFMODPaused(): Boolean


    private fun copyAssetToInternalStorage(assetName: String): String {
        val file = File(filesDir, assetName)

        // Always delete the old bank file to ensure updated version is copied
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted existing $assetName to ensure updated bank is used.")
        }

        // Copy the asset into internal storage
        assets.open(assetName).use { inputStream ->
            file.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        return file.absolutePath
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupViewPager()
        Log.d(TAG, "onCreate: Calling bindToService...") // Log before binding
        bindToService()

        org.fmod.FMOD.init(this);
        val result = org.fmod.FMOD.checkInit()
        Log.d("FMOD", "FMOD init result: $result")


        val masterBankPath = copyAssetToInternalStorage("Master.bank")
        val stringsBankPath = copyAssetToInternalStorage("Master.strings.bank")

        Log.d(TAG, "Calling startFMODPlayback() with bank paths")

        startFMODPlayback(masterBankPath, stringsBankPath)
        setFMODParameter("Wheel Speed", 0.0f)
    }

    fun getBleServiceInstance(): BleService? {
        return if (bound) bleService else null
    }

    private fun notifyDevicesFragmentServiceReady() {
        // --- ADD LOG HERE ---
        Log.d(TAG, "notifyDevicesFragmentServiceReady: Attempting to find and notify fragment...")
        // --- END LOG ---
        val devicesFragmentPosition = 2
        val fragmentTag = "f$devicesFragmentPosition"
        val fragment = supportFragmentManager.findFragmentByTag(fragmentTag)

        if (fragment is DevicesFragment) {
            Log.d(TAG, "notifyDevicesFragmentServiceReady: Found DevicesFragment, calling onServiceReady().") // Existing log modified slightly
            fragment.onServiceReady()
        } else {
            Log.w(TAG, "notifyDevicesFragmentServiceReady: Could not find DevicesFragment with tag $fragmentTag.") // Existing log modified slightly
        }
    }


    private fun setupViewPager() {
        // ... (rest of setupViewPager is unchanged) ...
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

    private fun bindToService() {
        Intent(this, BleService::class.java).also { intent ->
            Log.d(TAG, "bindToService: Binding...") // Log when bindService is called
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Unbinding service if bound.") // Log before unbinding
        if (bound) {
            unbindService(serviceConnection)
            bound = false
            isServiceConnected = false
        }

        org.fmod.FMOD.close();
    }
}

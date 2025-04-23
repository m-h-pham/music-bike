package com.app.musicbike.ui.adapter

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.app.musicbike.databinding.ListItemDeviceBinding // Import view binding for the item layout

// Define a type alias for the click listener lambda for better readability
typealias DeviceClickListener = (BluetoothDevice) -> Unit

class DeviceListAdapter(private val clickListener: DeviceClickListener) :
    ListAdapter<BluetoothDevice, DeviceListAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    private val TAG = "DeviceListAdapter" // For logging

    // ViewHolder holds references to the views in list_item_device.xml
    class DeviceViewHolder(private val binding: ListItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: BluetoothDevice, clickListener: DeviceClickListener) {
            // Check for BLUETOOTH_CONNECT permission before accessing name/address on API 31+
            // This check ideally should happen before even scanning, but good to be safe here too.
            val context = binding.root.context
            var deviceNameStr = "Name requires Connect permission"
            var deviceAddressStr = device.address ?: "No Address" // Address should usually be available

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                try {
                    deviceNameStr = device.name ?: "Unknown Device"
                    // Address is generally safe, but name needs permission
                } catch (e: SecurityException) {
                    Log.e("DeviceViewHolder", "SecurityException getting device name/address. Check permissions.", e)
                    deviceNameStr = "Permission Error"
                }
            }

            binding.deviceName.text = deviceNameStr
            binding.deviceAddress.text = deviceAddressStr

            // Set the click listener on the whole item view
            binding.root.setOnClickListener {
                Log.d("DeviceViewHolder", "Item clicked: $deviceNameStr ($deviceAddressStr)")
                clickListener(device) // Pass the clicked BluetoothDevice object back
            }
        }

        // Static function to create a ViewHolder instance
        companion object {
            fun from(parent: ViewGroup): DeviceViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemDeviceBinding.inflate(layoutInflater, parent, false)
                return DeviceViewHolder(binding)
            }
        }
    }

    // Called when RecyclerView needs a new ViewHolder
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        Log.d(TAG, "onCreateViewHolder called")
        return DeviceViewHolder.from(parent)
    }

    // Called by RecyclerView to display the data at the specified position
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val item = getItem(position) // Get the BluetoothDevice object for this position
        Log.d(TAG, "onBindViewHolder for position $position, device: ${item.address}")
        holder.bind(item, clickListener) // Bind data to the ViewHolder
    }
}

// DiffUtil helps the ListAdapter determine differences between lists efficiently
class DeviceDiffCallback : DiffUtil.ItemCallback<BluetoothDevice>() {
    // Check if items represent the same object (usually by unique ID)
    override fun areItemsTheSame(oldItem: BluetoothDevice, newItem: BluetoothDevice): Boolean {
        return oldItem.address == newItem.address // MAC address is a unique identifier
    }

    // Check if the contents of the items are the same (if items are the same object)
    override fun areContentsTheSame(oldItem: BluetoothDevice, newItem: BluetoothDevice): Boolean {
        // We only care about name for display purposes, address checked above
        // Note: Name can change, so this might cause unnecessary redraws if only name changes
        // For simplicity, we compare address again (if addresses match, content is same for our purpose)
        return oldItem.address == newItem.address // Simplified check
        // return oldItem == newItem // Could do full object comparison if needed
    }
}

package com.ubicomplab.bluetoothlocation;

import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.Manifest;
import android.content.Context;


import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.RecyclerView;


import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(BluetoothDevice device, int position);
    }

    private final List<BluetoothDevice> devices;
    private final OnItemClickListener listener;

    public DeviceListAdapter(List<BluetoothDevice> devices, OnItemClickListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Using a standard Android layout for list items. You can customize this layout as needed.
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        BluetoothDevice device = devices.get(position);
        // Use the context from the holder to perform permission checks.
        Context context = holder.itemView.getContext();
        String deviceName;
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            // If the BLUETOOTH_CONNECT permission is not granted, set a default message.
            deviceName = "Permission Required";
        } else {
            // If permission is granted, use the device name if available, otherwise "Unnamed Device".
            deviceName = (device.getName() != null) ? device.getName() : "Unnamed Device";
        }

        // Set the text for this list item.
        holder.textView.setText(deviceName);

        // Set a content description for accessibility,
        // so screen readers announce the device number and name.
        String contentDescription = "Device " + (position + 1) + ": " + deviceName;
        holder.itemView.setContentDescription(contentDescription);

        // Set the item click listener
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(device, position);
            }
        });
    }


    @Override
    public int getItemCount() {
        return devices.size();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            // The simple_list_item_1 layout contains a TextView with the ID "text1".
            textView = itemView.findViewById(android.R.id.text1);
        }
    }
}

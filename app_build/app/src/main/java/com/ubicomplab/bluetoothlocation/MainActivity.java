package com.ubicomplab.bluetoothlocation;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;

public class MainActivity extends AppCompatActivity {

    private Context this_context;
    private String uniqueID;
    Button bleScanButton;
    private View connectionIndicator;
    private View labelIndicator;
    private boolean bleConnected;

    // BLE adapter to list off BLE devices on screen.
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private List<BluetoothDevice> mDeviceList;  // List of devices to query using the clicked device name.
    private ArrayAdapter<String> mDeviceListAdapter;  // List of strings to display on the screen.
    private ListView mDeviceListView;
    private List<BluetoothDevice> filteredDeviceList;
    private String searchFilter;
    private boolean currentlyScanning = false;
    private String formattedDateTime;

    private ConcurrentLinkedQueue<String> locationQueue = new ConcurrentLinkedQueue<>();
    private Thread locationFileWritingThread = null;

    private TextView textView;
    private BroadcastReceiver updateReceiver;
    private TextView locationIndicator;
    private volatile boolean keepRunning = true;
    DateTimeFormatter formatter;

    private static final int MULTIPLE_PERMISSIONS_REQUEST_CODE = 123;
    private static final int BLE_PERMISSION_REQUEST_CODE = 1;


    // Disconnection dialog which is used to confirm whether user intends to disconnect.
    private void showDisconnectDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect")
                .setMessage("Are you sure you want to disconnect?")
                .setPositiveButton("Disconnect", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // Perform disconnection
                        disconnectDevice();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disconnectDevice() {
        // Stop the BLE service
        Intent serviceIntent = new Intent(this, BleService.class);
        stopService(serviceIntent);
        connectionIndicator.setBackgroundColor(Color.RED);
        labelIndicator.setBackgroundColor(Color.BLUE);
        bleScanButton.setEnabled(true);
        bleScanButton.setText("Start Scanning");
        bleConnected = false;
    }


    public static class SensorReadingPacket {
        public int sensorIndex;
        public int packetIndex;
        public int readIndex;
        public long peripheralTimestamp;
        public long androidTimestamp;
        public int[] payload;

        public SensorReadingPacket(int sensorIndex, int packetIndex, int readIndex, long peripheralTimestamp, long androidTimestamp, int[] payload) {
            this.sensorIndex = sensorIndex;
            this.packetIndex = packetIndex;
            this.readIndex = readIndex;
            this.peripheralTimestamp = peripheralTimestamp;
            this.androidTimestamp = androidTimestamp;
            this.payload = payload;
        }

        public String getAsCSVRow() {
            StringBuilder csvRow = new StringBuilder();
            csvRow.append(sensorIndex).append(',')
                    .append(packetIndex).append(',')
                    .append(readIndex).append(',')
                    .append(peripheralTimestamp).append(',')
                    .append(androidTimestamp);

            for (int value : payload) {
                csvRow.append(',').append(value);
            }

            return csvRow.toString();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start your Bluetooth operations
                startScanning();
            } else {
                // Permission denied, handle accordingly
                Toast.makeText(this, "Bluetooth permissions are required for scanning.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean checkAndRequestPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_PHONE_STATE,
        };

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this,
                    permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                Log.i("permissions", permission + "not granted.");
                break;
            }
        }

        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions,
                    MULTIPLE_PERMISSIONS_REQUEST_CODE);
            Log.i("permissions", "Permissions not granted!");
            return false;
        } else {
            Log.i("permissions", "Permissions granted!");
            return true;
        }
    }

    private void handlePermissionsNotGranted(String permission) {
        Log.i("Permissions",
                permission + "not granted. Discovered in permission check before function call.");
    }

    private void filterDeviceList(String text) {
        Log.i("Filtered device list", "filtered.");
        filteredDeviceList.clear();
        List<String> filteredListNames = new ArrayList<>();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }

        for (BluetoothDevice device : mDeviceList) {
            if (device.getName() != null && device.getName().toLowerCase().contains(
                    text.toLowerCase())) {
                filteredDeviceList.add(device);
                filteredListNames.add(device.getName());
            }
        }

        mDeviceListAdapter.clear();
        mDeviceListAdapter.addAll(filteredListNames);
        mDeviceListAdapter.notifyDataSetChanged();
    }


    public boolean isBluetoothEnabled() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e("Bluetooth", "Device doesn't support Bluetooth");
            return false;
        }
        return bluetoothAdapter.isEnabled();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this_context = this;

        checkAndRequestPermissions();
        String testfile = getExternalFilesDir(null) + "/" + formattedDateTime + "test.csv";
        Log.i("FILEPATH:", testfile + "");

        textView = (TextView) findViewById(R.id.textView);

        // Write a unique id to storage for communicating with the server.
        File phoneIdFile = new File(getExternalFilesDir(null), "phone_id.txt");
        if (phoneIdFile.exists()) {
            // File already exists, do not overwrite
            Log.i("ID File", "Already exists!");
            StringBuilder stringBuilder = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(phoneIdFile);
                 InputStreamReader isr = new InputStreamReader(fis);
                 BufferedReader br = new BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    stringBuilder.append(line);
                }
                uniqueID = stringBuilder.toString();
                textView.setText(uniqueID);
            } catch (IOException e) {
                Log.e("MainActivity", "Error reading from file", e);
                textView.setText("failed to read phone ID");
                uniqueID = "-1";
            }
        } else {
            // File does not exist, create and store the unique ID
            uniqueID = UUID.randomUUID().toString();
            try (FileOutputStream fos = new FileOutputStream(phoneIdFile)) {
                fos.write(uniqueID.getBytes());
                Toast.makeText(this, "Unique ID stored in " + phoneIdFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                textView.setText(uniqueID);
            } catch (IOException e) {
                Log.e("MainActivity", "Error writing to file", e);
                Toast.makeText(this, "Failed to write to file", Toast.LENGTH_SHORT).show();
                textView.setText("failed to create phone ID");
                uniqueID = "-1";
            }
        }

        locationIndicator = findViewById(R.id.locationIndicator);
        mDeviceList = new ArrayList<>();
        mDeviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mDeviceListView = findViewById(R.id.deviceListView);
        mDeviceListView.setAdapter(mDeviceListAdapter);
        EditText searchEditText = findViewById(R.id.searchEditText);
        searchFilter = "";
        searchEditText.setText(searchFilter);
        filteredDeviceList = new ArrayList<>();

        // Format it to a human-readable string
        formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH:mm:ss");

        // Set up the TextWatcher
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Do nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Do nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchFilter = s.toString();
                filterDeviceList(s.toString());
            }
        });

        /* Connect to a device when clicked form the list present in the UI */
        mDeviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.i("onItemClick", "Clicked device at position " + position);
                //BluetoothDevice device = mDeviceList.get(position);
                BluetoothDevice device = filteredDeviceList.get(position);
                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_SCAN);
                    return;
                }
                stopScanning();
                bleScanButton.setEnabled(false);
                connectToDevice(device);
            }
        });

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Find the button by its ID
        bleScanButton = findViewById(R.id.scanButton);
        connectionIndicator = findViewById(R.id.connectionIndicator);
        labelIndicator = findViewById(R.id.labelIndicator);
        labelIndicator.setBackgroundColor(Color.BLUE);
        bleConnected = false;

        bleScanButton.setBackgroundColor(Color.GREEN);



        // Set the click listener
        bleScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bleConnected) {
                    showDisconnectDialog();
                } else {
                    // Check permissions and start scanning for BLE device.
                    if (currentlyScanning) {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this,
                                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                            handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_SCAN);
                            return;
                        }
                        bleScanButton.setText("Start Scanning");
                        stopScanning();

                    } else {
                        if (isBluetoothEnabled()) {
                            // Check for Bluetooth permissions
                            if (ContextCompat.checkSelfPermission(this_context, Manifest.permission.BLUETOOTH_SCAN)
                                    != PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(this_context, Manifest.permission.BLUETOOTH_CONNECT)
                                            != PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(this_context, Manifest.permission.BLUETOOTH_ADVERTISE)
                                            != PackageManager.PERMISSION_GRANTED) {
                                ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_ADVERTISE
                                }, BLE_PERMISSION_REQUEST_CODE);
                            } else {
                                // Permissions already granted, start your Bluetooth operations
                                startScanning();
                            }

                            bleScanButton.setText("Stop Scanning");
                            currentlyScanning = true;
                            Toast.makeText(MainActivity.this,
                                    "Button Clicked: attempting to scan.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "Bluetooth is OFF, please turn it on!", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        });



        // Initialize and register the BroadcastReceiver to update UI elements based on BLE activity.
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if ("com.example.ACTION_UPDATE_UI".equals(action)) {
                    int rearReading = intent.getIntExtra("rearReading", 0);
                    int sideReading = intent.getIntExtra("sideReading", 0);
                    long sensorTime1 = intent.getLongExtra("sensorTime1", 0);
                    long sensorTime2 = intent.getLongExtra("sensorTime2", 0);

                    // Format the readings to a fixed width of 4 characters
                    String formattedRear = String.format("%4d", rearReading);
                    String formattedSide = String.format("%4d", sideReading);
                    String delay = String.format("%4d", sensorTime1 - sensorTime2);
                    textView.setText("Rear: " + formattedRear + " side: " + formattedSide + " Delay " + delay);
                } else if ("com.example.ACTION_UPDATE_LOCATION_UI".equals(action)) {
                    String locationStr = intent.getStringExtra("location");
                    locationIndicator.setText(locationStr);
                } else if ("com.example.ACTION_RECONNECTING".equals(action)) {
                    //bleScanButton.setBackgroundColor(Color.parseColor("#FFFF00")); // sets background color to Yellow
                    connectionIndicator.setBackgroundColor(Color.YELLOW);
                    bleConnected = false;
                } else if ("com.example.ACTION_DISCONNECTED".equals(action)) {
                    //bleScanButton.setBackgroundColor(Color.parseColor("#FF0000")); // sets background color to red
                    connectionIndicator.setBackgroundColor(Color.RED);
                    bleScanButton.setEnabled(true);
                    bleScanButton.setText("Start Scanning");
                    bleConnected = false;
                } else if ("com.example.ACTION_CONNECTED".equals(action)) {
                    connectionIndicator.setBackgroundColor(Color.GREEN);
                    bleScanButton.setText("Disconnect");
                    bleScanButton.setEnabled(true);
                    bleConnected = true;
                }
            }
        };
        IntentFilter updateUIFilter = new IntentFilter("com.example.ACTION_UPDATE_UI");
        updateUIFilter.addAction("com.example.ACTION_UPDATE_LOCATION_UI");
        updateUIFilter.addAction("com.example.ACTION_CONNECTED");
        updateUIFilter.addAction("com.example.ACTION_DISCONNECTED");
        updateUIFilter.addAction("com.example.ACTION_RECONNECTING");
        registerReceiver(updateReceiver, updateUIFilter, RECEIVER_EXPORTED);

    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (ActivityCompat.checkSelfPermission(
                    MainActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            // Add devices to the device list to populate the UI.
            if (device.getName() != null && !mDeviceList.contains(device)) {
                mDeviceList.add(device);
                mDeviceListAdapter.add(device.getName());
                // Not sure if this is necessary.
                mDeviceListAdapter.notifyDataSetChanged();
                // Upon change to the device list, filter the device list for what is in search bar.
                filterDeviceList(searchFilter);
                Log.i("onScanResult", "added and Filtered scan results");
                for (int i = 0; i < mDeviceList.toArray().length; i++) {
                    BluetoothDevice printDevice = mDeviceList.get(i);
                    Log.i("onScanResult", "Device: " + printDevice.getName() + " position: " + i);
                }

            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.i("BLEScan", "Scan Failed!");
        }
    };

    // Initiate scanning to find BLE device and connect.
    private void startScanning() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED) {

            // Scan settings allow for higher throughput on BLE.
            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            // Start the BLE scanner.
            mBluetoothLeScanner.startScan(null, scanSettings, mScanCallback);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    private void stopScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mBluetoothLeScanner.stopScan(mScanCallback);
        // Clear the device list and notify the adapter
        mDeviceList.clear();
        mDeviceListAdapter.clear();
        mDeviceListAdapter.notifyDataSetChanged();
        bleScanButton.setText("Start Scanning");
        currentlyScanning = false;
    }


    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            handlePermissionsNotGranted(android.Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        // Starts bluetooth service using the device selected.
        if (device != null) {
            Intent serviceIntent = new Intent(this, BleService.class);
            LocalDateTime now = LocalDateTime.now();
            formattedDateTime = now.format(formatter);
            serviceIntent.putExtra("BluetoothDevice", device);
            serviceIntent.putExtra("startTime", formattedDateTime);
            // Starts foregrounded BLE and location service.
            startService(serviceIntent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // TODO moved to service!
        //closeGatt();
        unregisterReceiver(updateReceiver);
        // Stop the BLE service
        Intent serviceIntent = new Intent(this, BleService.class);
        stopService(serviceIntent);
    }

}
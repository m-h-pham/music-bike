package com.ubicomplab.bluetoothlocation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;

public class BleService extends Service {
    private static final String CHANNEL_ID = "SMARTHANDLEBAR_BLE_service_channel";
    UUID MY_SERVICE_UUID = UUID.fromString("10336bc0-c8f9-4de7-b637-a68b7ef33fc9");
    UUID MY_CHARACTERISTIC_UUID = UUID.fromString("43336bc0-c8f9-4de7-b637-a68b7ef33fc9");
    // The fixed standard UUID for notifications.
    UUID YOUR_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private BluetoothGatt mBluetoothGatt;
    private PowerManager.WakeLock wakeLock;

    // Location variables
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;
    private ConcurrentLinkedQueue<String> locationQueue = new ConcurrentLinkedQueue<>();
    private Thread locationFileWritingThread = null;
    private File locationOutputFile;
    private int restartCounter;

    private BlockingQueue<MainActivity.SensorReadingPacket> rearPacketQueueBLE = new LinkedBlockingQueue<>();
    private BlockingQueue<MainActivity.SensorReadingPacket> sidePacketQueueBLE = new LinkedBlockingQueue<>();
    private Thread rearSensorFileWritingThread = null;
    private Thread sideSensorFileWritingThread = null;
    private File rearSensorOutputFile;
    private File sideSensorOutputFile;

    private int firstPayloadInt1 = 0;
    private int firstPayloadInt2 = 0;
    private long sensorTime1 = 0;
    private long sensorTime2 = 0;
    private int previousPacketIndex = -1; // Initialize to an invalid index
    private long previouspacketTimestamp = -1; // Initialize to an invalid index

    private volatile boolean keepRunning = true;
    DateTimeFormatter formatter;
    private String formattedDateTime;

    private boolean hasAttemptedReconnect = false;
    private Handler reconnectionHandler = new Handler(Looper.getMainLooper());
    private static final long RECONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private Runnable reconnectionTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (hasAttemptedReconnect) {
                // Reconnection attempt timed out
                closeGatt();
            }
        }
    };

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                //handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
                return;
            }
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            Intent disconnectIntent = new Intent("com.example.ACTION_DISCONNECTED");
            sendBroadcast(disconnectIntent);
        }
    }

    private void handleReconnection(BluetoothGatt gatt) {
        hasAttemptedReconnect = true;
        // Attempt to reconnect
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        boolean isReconnecting = gatt.connect();

        Intent disconnectIntent = new Intent("com.example.ACTION_RECONNECTING");
        sendBroadcast(disconnectIntent);

        if (!isReconnecting) {
            // If reconnection fails immediately, close GATT
            closeGatt();
        } else {
            // Start a timeout for the reconnection attempt
            reconnectionHandler.postDelayed(reconnectionTimeoutRunnable, RECONNECTION_TIMEOUT_MS);
        }
    }

    // TODO ucomment for URI mediastore instead of filepath.
    private void writeLineToFile(String line, File outputFile) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile, true))) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            // Handle IOException
            e.printStackTrace();
        }
    }

    private synchronized boolean isFileWritingThreadRunning(Thread fileWritingThread) {
        return fileWritingThread != null && fileWritingThread.isAlive();
    }

    // This writer thread is used when the BLE callback packages packets into SensorReadingPacket.
    private synchronized Thread startBLEPacketFileWritingThread(Thread thread,
                                                                BlockingQueue<MainActivity.SensorReadingPacket> queue,
                                                                File outputFile,
                                                                String threadName) {
        // if thread is already running just return it.
        if (isFileWritingThreadRunning(thread)) {
            return thread;
        }
        keepRunning = true;

        thread = new Thread(() -> {
            while (keepRunning) {
                while (!queue.isEmpty()) {
                    MainActivity.SensorReadingPacket packet = queue.poll();
                    if (packet != null) {
                        String row = packet.getAsCSVRow();
                        writeLineToFile(row, outputFile);
                    } else {
                        Log.i("in thread", "Queue.poll() was null....");
                    }
                }

                // Optional: Sleep a bit if queue is empty to reduce CPU usage
                try {
                    Thread.sleep(10); // Sleep for 10 milliseconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, threadName);
        thread.start();
        return thread;
    }

    private synchronized Thread stopFileWritingThread(Thread thread) {
        if (!isFileWritingThreadRunning(thread)) {
            return null; // The thread is not running
        }
        keepRunning = false;
        thread.interrupt();
        try {
            thread.join(); // Wait for the thread to finish
            Log.i("STOP THREAD", "thread joined.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.i("STOP THREAD", "Could not join thread.");
        }
        // only necessary if returns void using global thread variable.
        // thread = null; // Clear the thread reference
        return null;
    }

    private void startBLEFileWriterThreads() {
        if (!isFileWritingThreadRunning(sideSensorFileWritingThread)) {
            sideSensorFileWritingThread = startBLEPacketFileWritingThread(
                    sideSensorFileWritingThread, sidePacketQueueBLE,
                    sideSensorOutputFile, "sideSensorThread");
        }
        if (!isFileWritingThreadRunning(rearSensorFileWritingThread)) {
            rearSensorFileWritingThread = startBLEPacketFileWritingThread(
                    rearSensorFileWritingThread, rearPacketQueueBLE,
                    rearSensorOutputFile, "rearSensorThread");
        }
    }

    private synchronized Thread startStringRowFileWritingThread(Thread thread,
                                                                ConcurrentLinkedQueue<String> queue,
                                                                File outputFile,
                                                                String threadName) {
        // if thread is already running just return it.
        if (isFileWritingThreadRunning(thread)) {
            return thread;
        }
        keepRunning = true;

        thread = new Thread(() -> {
            while (keepRunning) {
                while (!queue.isEmpty()) {
                    String polledValue = queue.poll();
                    writeLineToFile(polledValue, outputFile);
                }
                try {
                    Thread.sleep(10); // Sleep for 10 milliseconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, threadName);
        thread.start();
        return thread;
    }

    private void startLocationFileWriterThread() {
        if (!isFileWritingThreadRunning(locationFileWritingThread)) {
            locationFileWritingThread = startStringRowFileWritingThread(
                    locationFileWritingThread, locationQueue,
                    locationOutputFile, "locationThread" + restartCounter);
            restartCounter++;
        }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BLE", "Connected to GATT server.");
                startBLEFileWriterThreads();
                startLocationFileWriterThread();
                Intent disconnectIntent = new Intent("com.example.ACTION_CONNECTED");
                sendBroadcast(disconnectIntent);
                if (ActivityCompat.checkSelfPermission(BleService.this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("BLE", "BLUETOOTH_CONNECT permission not granted");
                    stopSelf();
                    return;
                }

                gatt.discoverServices();
                boolean mtuRequested = gatt.requestMtu(24);
                Log.i("BLE", "MTU size change requested: " + mtuRequested);

                // Handle reconnection attempts and logging
                hasAttemptedReconnect = false;
                reconnectionHandler.removeCallbacks(reconnectionTimeoutRunnable);

                mBluetoothGatt.discoverServices();
                Log.i("BLE", "Attempting to start service discovery");

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BLE", "Disconnected from GATT server.");
                sideSensorFileWritingThread = stopFileWritingThread(sideSensorFileWritingThread);
                rearSensorFileWritingThread = stopFileWritingThread(rearSensorFileWritingThread);
                locationFileWritingThread = stopFileWritingThread(locationFileWritingThread);
                locationQueue.clear(); // Clear the data queue

                if (!hasAttemptedReconnect) {
                    handleReconnection(gatt);
                } else {
                    closeGatt();
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            Log.i("BLE", "onMtuChanged callback triggered");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "MTU size changed successfully to " + mtu);
            } else {
                Log.e("BLE", "Failed to change MTU size, status: " + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "GATT SUCCESS, looking for correct service and characteristic.");
                for (BluetoothGattService gattService : gatt.getServices()) {
                    if (gattService.getUuid().equals(MY_SERVICE_UUID)) {
                        BluetoothGattCharacteristic characteristic =
                                gattService.getCharacteristic(MY_CHARACTERISTIC_UUID);
                        if (ActivityCompat.checkSelfPermission(BleService.this,
                                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            Log.e("BLE", "BLUETOOTH_CONNECT permission not granted");
                            stopSelf();
                            return;
                        }
                        gatt.setCharacteristicNotification(characteristic, true);
                        BluetoothGattDescriptor desc = characteristic.getDescriptor(YOUR_DESCRIPTOR_UUID);
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(desc);
                        Log.i("BLE", "Wrote descriptor to enable notifications.");
                    }
                }
            } else {
                Log.w("BLE", "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (MY_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                long androidTime = System.currentTimeMillis();

                int combined = data[0] & 0xFF;
                int sensorIndex = (combined >> 4) & 0x0F;
                int packetIndex = combined & 0x0F;
                int readIndex = data[1] & 0xFF;
                long peripheralTimestamp = ((data[2] & 0xFFL) << 24) |
                        ((data[3] & 0xFFL) << 16) |
                        ((data[4] & 0xFFL) << 8) |
                        (data[5] & 0xFFL);

                int payloadStartingIndex = 6;
                int packetMissed = -1;
                long currentPacketDelay = -1;

                if (sensorIndex == 1) {
                    firstPayloadInt1 = ((data[payloadStartingIndex] & 0xFF) << 8) | (data[payloadStartingIndex + 1] & 0xFF);
                    sensorTime1 = peripheralTimestamp;
                } else if (sensorIndex == 2) {
                    firstPayloadInt2 = ((data[payloadStartingIndex] & 0xFF) << 8) | (data[payloadStartingIndex + 1] & 0xFF);
                    sensorTime2 = peripheralTimestamp;
                }

                int[] payload = new int[(data.length - payloadStartingIndex) / 2];
                for (int i = 0; i < payload.length; i++) {
                    int index = payloadStartingIndex + i * 2;
                    payload[i] = ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
                }

                if (previousPacketIndex != -1) {
                    currentPacketDelay = peripheralTimestamp - previouspacketTimestamp;
                    if ((previousPacketIndex == 0 && packetIndex != 1) ||
                            (previousPacketIndex == 1 && packetIndex != 2) ||
                            (previousPacketIndex == 2 && packetIndex != 0)) {
                        packetMissed = 1;
                    } else {
                        packetMissed = 0;
                    }
                }
                previousPacketIndex = packetIndex;
                previouspacketTimestamp = peripheralTimestamp;



                //Log.i("Received data length: " + data.length, "Sensor: " + sensorIndex + " packet " + packetIndex + " read index: " + readIndex + " at time " + androidTime + " peripheral timestamp was: " + peripheralTimestamp + " first value was rear: " + firstPayloadInt1 + " Side: " + firstPayloadInt2);
                Log.i("BLE", "packet missed: " + packetMissed + " packet delay:  " + currentPacketDelay + " Sensor: " + sensorIndex + " packet " + packetIndex + " read index: " + readIndex + " rear queue len:" + rearPacketQueueBLE.size() + " side queue len:" + sidePacketQueueBLE.size());
                MainActivity.SensorReadingPacket packet = new MainActivity.SensorReadingPacket(sensorIndex, packetIndex, readIndex, peripheralTimestamp, androidTime, payload);
                if (sensorIndex == 1) {
                    rearPacketQueueBLE.offer(packet);
                } else if (sensorIndex == 2) {
                    sidePacketQueueBLE.offer(packet);
                }

                Intent intent = new Intent("com.example.ACTION_UPDATE_UI");
                intent.putExtra("rearReading", firstPayloadInt1);
                intent.putExtra("sideReading", firstPayloadInt2);
                intent.putExtra("sensorTime1", sensorTime1);
                intent.putExtra("sensorTime2", sensorTime2);
                sendBroadcast(intent);
            }
        }
    };

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "BLE Service Channel";
            String description = "Channel for BLE service notifications";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        restartCounter = 0;
        createNotificationChannel();
        initializeLocationTracking();
    }

    private void initializeLocationTracking() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(5000)
                .build();

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);
        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(new OnSuccessListener<LocationSettingsResponse>() {
            @Override
            public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                Log.i("LOCATION", "Successfully got location setting response");
            }
        });
        task.addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                if (e instanceof ResolvableApiException) {
                    // Handle location settings not satisfied
                }
            }
        });

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    Log.i("LOCATION", "Null result...");
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();
                        String locationString = String.format(Locale.US, "%s -- %s", latitude, longitude);
                        long timestamp = System.currentTimeMillis();
                        String locationRow = timestamp + "," + locationString;
                        if (locationQueue != null) {
                            locationQueue.offer(locationRow);
                        }
                        Intent intent = new Intent("com.example.ACTION_UPDATE_LOCATION_UI");
                        intent.putExtra("location", locationString);
                        sendBroadcast(intent);
                        Log.i("LOCATION", "Got a location at " + latitude + " " + longitude);
                    }
                }
            }
        };
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i("LOCATION", "Could not get permission.");
            return;
        }
        mFusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void stopLocationUpdates() {
        if (mFusedLocationClient != null) {
            mFusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BLE Service")
                .setContentText("Receiving BLE packets")
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, notification);
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::BleWakeLock");
        wakeLock.acquire();

        BluetoothDevice device = intent.getParcelableExtra("BluetoothDevice");
        formattedDateTime = intent.getStringExtra("startTime");

        if (formattedDateTime == null) {
            Log.i("service", "FormattedDateTime was not passed to the service correctly...");
            formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH:mm:ss");
            LocalDateTime now = LocalDateTime.now();
            formattedDateTime = now.format(formatter);
        }
        String rearFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_rear.csv";
        String sideFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_side.csv";
        String locationFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_location.csv";


        Log.i("FILEPATH:", rearFilename + "");
        rearSensorOutputFile = new File(rearFilename);
        sideSensorOutputFile = new File(sideFilename);
        locationOutputFile = new File(locationFilename);

        if (device != null) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                //handlePermissionsNotGranted(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
            // Request high priority connection to potentially reduce the connection interval
            mBluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        }

        // Start location updates
        startLocationUpdates();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closeGatt();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        sideSensorFileWritingThread = stopFileWritingThread(sideSensorFileWritingThread);
        rearSensorFileWritingThread = stopFileWritingThread(rearSensorFileWritingThread);
        stopLocationUpdates();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    public class LocalBinder extends Binder {
        BleService getService() {
            return BleService.this;
        }
    }
}

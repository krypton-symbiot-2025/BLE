package com.example.ble;



import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BLEMesh";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSION_LOCATION = 2;
    private static final long SCAN_DURATION_MS = 10000;
    private static final long ADVERTISE_DURATION_MS = 10000;
    private static final UUID SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private Handler handler = new Handler();
    private boolean isScanning = false;
    private Map<String, Integer> rssiMap = new HashMap<>();

    private TextView statusText;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.enable();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSION_LOCATION);
            }
        }

        if (bluetoothAdapter.isMultipleAdvertisementSupported()) {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        }

        startRoleSwitching();
    }

    private void startRoleSwitching() {
        handler.post(scanRunnable);
    }

    private Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isScanning) {
                startScanning();
                isScanning = true;
                handler.postDelayed(scanRunnable, SCAN_DURATION_MS);
            } else {
                stopScanning();
                startAdvertising();
                isScanning = false;
                handler.postDelayed(scanRunnable, ADVERTISE_DURATION_MS);
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void startScanning() {
        statusText.setText("Scanning...");
        bluetoothAdapter.getBluetoothLeScanner().startScan(scanCallback);
    }

    @SuppressLint("MissingPermission")
    private void stopScanning() {
        statusText.setText("Stopped Scanning");
        bluetoothAdapter.getBluetoothLeScanner().stopScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            rssiMap.put(device.getAddress(), rssi);
            Log.d(TAG, "Found device: " + device.getAddress() + " RSSI: " + rssi);
        }
    };

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        statusText.setText("Advertising...");
        if (advertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(0)
                .build();

        byte[] compressedData = compressData();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), compressedData)
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private byte[] compressData() {
        ByteBuffer buffer = ByteBuffer.allocate(31);
        for (Map.Entry<String, Integer> entry : rssiMap.entrySet()) {
            if (buffer.remaining() < 7) break;
            String address = entry.getKey().replace(":", "");
            int rssi = entry.getValue();
            byte[] addrBytes = hexStringToByteArray(address.substring(6)); // last 3 bytes
            buffer.put(addrBytes);
            buffer.put((byte) rssi);
        }
        rssiMap.clear(); // clear after relaying
        return buffer.array();
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "Advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Advertising failed with error code: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanning();
        if (advertiser != null) {
            advertiser.stopAdvertising(advertiseCallback);
        }
    }
}







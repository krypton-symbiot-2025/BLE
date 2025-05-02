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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BLEMesh";
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
    private Set<String> seenMessages = new HashSet<>();
    private int currentTxPowerIndex = 0;
    private static final int[] TX_POWER_LEVELS = {
            AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    };
    private static final String[] TX_POWER_LABELS = {
            "ULTRA_LOW", "LOW", "MEDIUM", "HIGH"
    };



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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_PERMISSION_LOCATION);
        }

        if (bluetoothAdapter.isMultipleAdvertisementSupported()) {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        } else {
            Log.e(TAG, "BLE Advertiser not supported on this device.");
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

            runOnUiThread(() -> statusText.append("\n" + device.getAddress() + " RSSI: " + rssi));

            byte[] serviceData = null;
            if (result.getScanRecord() != null) {
                serviceData = result.getScanRecord().getServiceData(new ParcelUuid(SERVICE_UUID));
            }
            if (serviceData != null) {
                parseReceivedData(serviceData);
            }
        }
    };

    private void parseReceivedData(byte[] data) {
        if (data.length < 4) return;
        String uuid = bytesToHex(Arrays.copyOfRange(data, 0, 4));
        if (seenMessages.contains(uuid)) return;

        seenMessages.add(uuid);  // Mark as seen

        int index = 4;
        while (index + 4 <= data.length) {
            byte[] addrPart = Arrays.copyOfRange(data, index, index + 3);
            int rssi = data[index + 3];
            String macSuffix = bytesToHex(addrPart);
            String fakeMac = "XX:XX:XX:" + macSuffix.substring(0, 2) + ":" + macSuffix.substring(2, 4) + ":" + macSuffix.substring(4);

            rssiMap.put(fakeMac, rssi);
            runOnUiThread(() -> statusText.append("\nRelayed: " + fakeMac + " RSSI: " + rssi));

            index += 4;
        }
    }


    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        if (advertiser == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Cannot advertise: advertiser is null or Bluetooth is off");
            return;
        }

        int txPower = TX_POWER_LEVELS[currentTxPowerIndex];
        String txPowerLabel = TX_POWER_LABELS[currentTxPowerIndex];

        statusText.setText("Advertising... Tx Power: " + txPowerLabel);
        Log.d(TAG, "Advertising with Tx Power: " + txPowerLabel);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(txPower)
                .setConnectable(false)
                .setTimeout((int) ADVERTISE_DURATION_MS)
                .build();

        byte[] compressedData = compressData();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), compressedData)
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);

        // Cycle to next power level for next round
        currentTxPowerIndex = (currentTxPowerIndex + 1) % TX_POWER_LEVELS.length;
    }


    private byte[] compressData() {
        ByteBuffer buffer = ByteBuffer.allocate(24); // enough for 3 relays

        String uuid = generateUUID(); // 4-byte unique ID
        buffer.put(hexStringToByteArray(uuid));

        for (Map.Entry<String, Integer> entry : rssiMap.entrySet()) {
            if (buffer.remaining() < 4) break;
            String address = entry.getKey().replace(":", "");
            int rssi = entry.getValue();
            if (address.length() >= 12) {
                byte[] addrBytes = hexStringToByteArray(address.substring(6));
                if (addrBytes.length == 3) {
                    buffer.put(addrBytes);
                    buffer.put((byte) rssi);
                }
            }
        }

        rssiMap.clear();
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private String generateUUID() {
        int uuid = (int) (System.currentTimeMillis() & 0xFFFFFFFF);
        return String.format("%08X", uuid);
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








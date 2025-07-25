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
import android.text.TextUtils;
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
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;



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

    private Map<String, String> devicePayloadMap = new HashMap<>();

    private static final int MAX_NAME_LENGTH = 8;
    private static final int UUID_LENGTH = 2; // bytes
    private static final int RELAY_ENTRY_SIZE = 4; // 3-byte MAC suffix + 1 RSSI
    private static final byte DISTRESS_FLAG = (byte) 0xFF;



    private Map<String, Integer> deviceLineMap = new HashMap<>(); // Tracks line index in statusText

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
        deviceLineMap.clear();
        bluetoothAdapter.getBluetoothLeScanner().startScan(scanCallback);
    }

    @SuppressLint("MissingPermission")
    private void stopScanning() {
        statusText.setText("Stopped Scanning");
        deviceLineMap.clear();
        bluetoothAdapter.getBluetoothLeScanner().stopScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String mac = device.getAddress();
            int rssi = result.getRssi();

            byte[] serviceData = null;
            if (result.getScanRecord() != null) {
                serviceData = result.getScanRecord().getServiceData(new ParcelUuid(SERVICE_UUID));
            }

            String payloadHex = (serviceData != null) ? bytesToHex(serviceData) : "";

            boolean rssiChanged = !rssiMap.containsKey(mac) || Math.abs(rssiMap.get(mac) - rssi) > 1;
            boolean payloadChanged = !devicePayloadMap.containsKey(mac) || !devicePayloadMap.get(mac).equals(payloadHex);

            if (rssiChanged || payloadChanged) {
                rssiMap.put(mac, rssi);
                devicePayloadMap.put(mac, payloadHex);

                String updatedLine = mac + " RSSI: " + rssi;
                Log.d(TAG, "Updated device: " + updatedLine);

                runOnUiThread(() -> {
                    String[] lines = statusText.getText().toString().split("\n");

                    int index = deviceLineMap.containsKey(mac) ? deviceLineMap.get(mac) : -1;


                    if (index >= 0 && index < lines.length) {
                        lines[index] = updatedLine;
                    } else {
                        index = lines.length;
                        lines = Arrays.copyOf(lines, index + 1);
                        lines[index] = updatedLine;
                        deviceLineMap.put(mac, index);
                    }

                    statusText.setText(TextUtils.join("\n", lines));
                });

            }

            if (serviceData != null) {
                parseReceivedData(serviceData); // handles relayed devices
            }
        }
    };

    private void parseReceivedData(byte[] data) {
        if (data.length < 3) return;

        String uuid = bytesToHex(Arrays.copyOfRange(data, 0, UUID_LENGTH)); // UUID_LENGTH = 2
        if (seenMessages.contains(uuid)) return;
        seenMessages.add(uuid);

        int index = UUID_LENGTH;
        byte header = data[index++];

        // Check distress-only flag
        if (header == DISTRESS_FLAG) {
            String distressLine = "⚠️ Distress UUID: " + uuid;
            runOnUiThread(() -> appendOrUpdateLine(uuid, distressLine));
            return;
        }

        // Normal payload
        int nameLen = header & 0xFF;
        if (index + nameLen > data.length) return;

        String deviceName = new String(Arrays.copyOfRange(data, index, index + nameLen), StandardCharsets.UTF_8);
        index += nameLen;

        String fromLine = "From: " + deviceName + " [UUID: " + uuid + "]";
        runOnUiThread(() -> appendOrUpdateLine(uuid, fromLine));

        while (index + 4 <= data.length) {
            byte[] addrPart = Arrays.copyOfRange(data, index, index + 3);
            int rssi = data[index + 3];
            String macSuffix = bytesToHex(addrPart);
            String fakeMac = "XX:XX:XX:" + macSuffix.substring(0, 2) + ":" + macSuffix.substring(2, 4) + ":" + macSuffix.substring(4);

            rssiMap.put(fakeMac, (int) rssi);
            String relayedLine = "Relayed: " + fakeMac + " RSSI: " + rssi;
            runOnUiThread(() -> appendOrUpdateLine(fakeMac, relayedLine));

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
        deviceLineMap.clear();
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


    @SuppressLint("MissingPermission")
    private byte[] compressData() {
        String uuidHex = generateUUID();
        byte[] uuidBytes = hexStringToByteArray(uuidHex); // 2 bytes

        boolean distressOnly = isInDistress(); // Your custom condition

        int nameLength = 0;
        byte[] nameBytes = new byte[0];

        if (!distressOnly) {
            nameBytes = bluetoothAdapter.getName().getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > MAX_NAME_LENGTH) {
                nameBytes = Arrays.copyOf(nameBytes, MAX_NAME_LENGTH);
            }
            nameLength = nameBytes.length;
        }

        int maxRelayCount = (23 - UUID_LENGTH - (distressOnly ? 1 : (1 + nameLength))) / RELAY_ENTRY_SIZE;
        int relayBytes = 0;

        for (String mac : rssiMap.keySet()) {
            if (relayBytes + RELAY_ENTRY_SIZE > maxRelayCount * RELAY_ENTRY_SIZE) break;
            relayBytes += RELAY_ENTRY_SIZE;
        }

        ByteBuffer buffer = ByteBuffer.allocate(UUID_LENGTH + (distressOnly ? 1 : (1 + nameLength + relayBytes)));

        buffer.put(uuidBytes, 0, UUID_LENGTH);

        if (distressOnly) {
            buffer.put(DISTRESS_FLAG);
        } else {
            buffer.put((byte) nameLength);
            buffer.put(nameBytes);

            int count = 0;
            for (Map.Entry<String, Integer> entry : rssiMap.entrySet()) {
                if (count >= maxRelayCount) break;
                String address = entry.getKey().replace(":", "");
                int rssi = entry.getValue();
                if (address.length() >= 12) {
                    byte[] suffix = hexStringToByteArray(address.substring(6));
                    if (suffix.length == 3) {
                        buffer.put(suffix);
                        buffer.put((byte) rssi);
                        count++;
                    }
                }
            }
        }

        rssiMap.clear();
        return Arrays.copyOf(buffer.array(), buffer.position());
    }



    private String generateUUID() {
        int uuid = (int) (System.currentTimeMillis() & 0xFFFF);
        return String.format("%04X", uuid); // 2-byte UUID in hex
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
    private void appendOrUpdateLine(String key, String newLine) {
        String text = statusText.getText().toString();
        String[] lines = text.isEmpty() ? new String[0] : text.split("\n");

        int idx = deviceLineMap.containsKey(key) ? deviceLineMap.get(key) : -1;

        if (idx >= 0 && idx < lines.length) {
            lines[idx] = newLine;
        } else {
            // Append new line
            lines = Arrays.copyOf(lines, lines.length + 1);
            lines[lines.length - 1] = newLine;
            deviceLineMap.put(key, lines.length - 1);
        }

        statusText.setText(android.text.TextUtils.join("\n", lines));
    }
    private boolean isInDistress() {
        return false; // or use a variable, e.g., distressModeEnabled
    }


}








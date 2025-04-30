package com.example.ble;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.util.Log;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // Define constants for request codes
    private static final int REQUEST_PERMISSION_LOCATION = 2;

    // BluetoothAdapter is the core class that will allow us to manage Bluetooth on the device
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;

    // Declare an ActivityResultLauncher to handle the result of the Bluetooth enabling request
    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // If Bluetooth was enabled, show a success message
                    Toast.makeText(this, getString(R.string.bluetooth_enabled), Toast.LENGTH_SHORT).show();
                    startBLEScan(); // Start scanning after Bluetooth is enabled
                } else {
                    // If user declined, show a message that Bluetooth couldn't be enabled
                    Toast.makeText(this, getString(R.string.bluetooth_not_enabled), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create a simple button dynamically (no XML needed for quick test)
        Button button = new Button(this);
        button.setText(getString(R.string.activate_ble_button));  // Use string resource for button text

        // Set the layout of the activity with the button
        setContentView(button);

        // Initialize BluetoothManager to access BluetoothAdapter (allows communication with Bluetooth hardware)
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            // Get BluetoothAdapter from the BluetoothManager system service
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Set a click listener on the button to trigger BLE activation
        button.setOnClickListener(v -> {
            // Check if the device supports Bluetooth
            if (bluetoothAdapter == null) {
                // Show a message if Bluetooth is not supported on the device
                Toast.makeText(MainActivity.this, getString(R.string.bluetooth_not_supported), Toast.LENGTH_SHORT).show();
                return; // Exit if Bluetooth is not available
            }

            // Check if the app has permission to access the location (needed for BLE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Request location permission if not granted
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSION_LOCATION);
            } else {
                // If permission is already granted, enable Bluetooth
                activateBluetooth();
            }
        });
    }

    // Method to activate Bluetooth if it's not already enabled
    private void activateBluetooth() {
        try {
            // Check if Bluetooth is enabled
            if (!bluetoothAdapter.isEnabled()) {
                // If Bluetooth is off, request to enable it
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                enableBluetoothLauncher.launch(enableBtIntent); // Launch the ActivityResultLauncher
            } else {
                // If Bluetooth is already on, show a toast message
                Toast.makeText(this, getString(R.string.bluetooth_already_on), Toast.LENGTH_SHORT).show();
                startBLEScan(); // Directly start scanning if already enabled
            }
        } catch (SecurityException e) {
            // Handle the case where Bluetooth permissions might be missing or denied
            Toast.makeText(this, "Security Exception: Please grant Bluetooth permissions.", Toast.LENGTH_LONG).show();
        }
    }

    private void startBLEScan() {
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            bleScanner.startScan(scanCallback);
            Toast.makeText(this, "BLE scanning started...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "BLE Scanner not available", Toast.LENGTH_SHORT).show();
        }
    }

    // Handle the result of the permission request
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            // Ensure we handle the non-nullable parameter properly
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // If granted, activate Bluetooth
                activateBluetooth();
            } else {
                // If permission is denied, show a message indicating Bluetooth won't work without location permission
                Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 👇 ScanCallback defined at the end for clarity
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            String deviceName = result.getDevice().getName();
            String deviceAddress = result.getDevice().getAddress();
            Log.d("BLE_SCAN", "Device found: " + deviceName + " [" + deviceAddress + "]");
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e("BLE_SCAN", "Scan failed with error code: " + errorCode);
        }
    };


};




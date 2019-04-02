/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private LinearLayout mLayoutMain;
    private TextView mConnectionState;
    private Button mButtonChangeLogname;
    private Button mButtonActive;
    private TextView mTVLogname;

    private String mDeviceName;
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;

    private BluetoothGattService mServiceIMU = null;
    private BluetoothGattCharacteristic mChrIMUActive = null;
    private BluetoothGattCharacteristic mChrIMULogname = null;

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    private BluetoothGattService getGattService(List<BluetoothGattService> gattServices, UUID uuid) {
        if (gattServices == null) return null;

        for (BluetoothGattService gattService : gattServices) {
            if (gattService.getUuid().equals(uuid)) {
                return gattService;
            }
        }

        return null;
    }

    enum SetupButtonState {
        UNKNOWN,
        ON,
        OFF,
    }

    private void setupButtonSetState(SetupButtonState s) {
        switch (s) {
            case UNKNOWN:
                mButtonActive.setText(getString(R.string.unknown));
                mButtonActive.setEnabled(false);
                mLayoutMain.setBackgroundColor(Color.TRANSPARENT);
                break;

            case ON:
                mButtonActive.setText(getString(R.string.on));
                mButtonActive.setEnabled(true);
                mLayoutMain.setBackgroundColor(Color.parseColor("#ccff90"));
                mButtonActive.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mChrIMUActive.setValue(new byte[]{0x00});
                        mBluetoothLeService.writeCharacteristic(mChrIMUActive);
                    }
                });
                break;

            case OFF:
                mButtonActive.setText(getString(R.string.off));
                mButtonActive.setEnabled(true);
                mLayoutMain.setBackgroundColor(Color.parseColor("#f28b82"));
                mButtonActive.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mChrIMUActive.setValue(new byte[]{0x01});
                        mBluetoothLeService.writeCharacteristic(mChrIMUActive);
                    }
                });
                break;
        }
    }

    private void lognameSetText(String s) {
        if (s != null) {
            mTVLogname.setText(s);
        }
        else {
            mTVLogname.setText(getString(R.string.unknown));
        }
    }

    private void setupIMUActive() {
        try {
            mChrIMUActive = mServiceIMU.getCharacteristic(UUID.fromString(SampleGattAttributes.IMULOGGER_ACTIVE));
            if (!mBluetoothLeService.readCharacteristic(mChrIMUActive)) {
                Log.e(TAG, "can't read chr-active");
            }
            if (!mBluetoothLeService.setCharacteristicNotification(mChrIMUActive, true)) {
                Log.e(TAG, "can't enable chr-active notifications");
            }
        } catch (Exception e) {
            e.printStackTrace();
            setupButtonSetState(SetupButtonState.UNKNOWN);
        }
    }

    private void setupIMULogname() {
        try {
            mChrIMULogname = mServiceIMU.getCharacteristic(UUID.fromString(SampleGattAttributes.IMULOGGER_LOGNAME));
            if (!mBluetoothLeService.readCharacteristic(mChrIMULogname)) {
                Log.e(TAG, "can't read chr-logname");
            }
            if (!mBluetoothLeService.setCharacteristicNotification(mChrIMULogname, true)) {
                Log.e(TAG, "can't enable chr-logname notifications");
            }
        } catch (Exception e) {
            e.printStackTrace();
            lognameSetText(null);
        }
    }

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                List<BluetoothGattService> gattServices = mBluetoothLeService.getSupportedGattServices();

                mServiceIMU = getGattService(gattServices, UUID.fromString(SampleGattAttributes.IMULOGGER_SERVICE));
                setupIMUActive();
                setupIMULogname();
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                try {
                    UUID uuidSvc = UUID.fromString(intent.getStringExtra(BluetoothLeService.EXTRA_SVC));
                    UUID uuidChr = UUID.fromString(intent.getStringExtra(BluetoothLeService.EXTRA_CHR));
                    byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);

                    if (uuidSvc.equals(mServiceIMU.getUuid())) {
                        if (mChrIMUActive != null && uuidChr.equals(mChrIMUActive.getUuid())) {
                            setupButtonSetState(data[0] == 0x01 ? SetupButtonState.ON : SetupButtonState.OFF);
                        }
                        else if (mChrIMULogname != null && uuidChr.equals(mChrIMULogname.getUuid())) {
                            lognameSetText(new String(data));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private void clearUI() {
        setupButtonSetState(SetupButtonState.UNKNOWN);
    }

    private void showLognameDialog(Context c) {
        final EditText editText = new EditText(c);

        InputFilter[] filterArray = new InputFilter[1];
        filterArray[0] = new InputFilter.LengthFilter(32);
        editText.setFilters(filterArray);

        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle("Set log name")
                .setView(editText)
                .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = String.valueOf(editText.getText());
                        mChrIMULogname.setValue(name.getBytes());
                        mBluetoothLeService.writeCharacteristic(mChrIMULogname);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        mLayoutMain = (LinearLayout)findViewById(R.id.main);
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mButtonChangeLogname = (Button) findViewById(R.id.button_change_logname);
        mButtonChangeLogname.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLognameDialog(DeviceControlActivity.this);
            }
        });
        mTVLogname = (TextView) findViewById(R.id.logname);
        lognameSetText(null);
        mButtonActive = (Button) findViewById(R.id.button_active);
        setupButtonSetState(SetupButtonState.UNKNOWN);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}

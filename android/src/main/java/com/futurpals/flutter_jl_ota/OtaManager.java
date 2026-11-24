package com.futurpals.flutter_jl_ota;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.futurpals.flutter_jl_ota.ble.BleManager;
import com.futurpals.flutter_jl_ota.ble.interfaces.BleEventCallback;
import com.futurpals.flutter_jl_ota.ble.interfaces.OnWriteDataCallback;
import com.jieli.jl_bt_ota.constant.StateCode;
import com.jieli.jl_bt_ota.impl.BluetoothOTAManager;
import com.jieli.jl_bt_ota.interfaces.BtEventCallback;
import com.jieli.jl_bt_ota.model.base.BaseError;
import com.jieli.jl_bt_ota.util.JL_Log;

import java.util.Objects;
import java.util.UUID;

public class OtaManager extends BluetoothOTAManager {
    private static final String TAG = "OtaManager";

    private final Context context;
    private final BleManager bleManager;
    public String mac;
    public String deviceName;
    private OtaStatusCallback otaStatusCallback;
    private boolean canStartOta = false;
    private boolean released = false;
    private BluetoothDevice lastBleDevice;
    private int lastBleStatus = -1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BtEventCallback sdkEventCallback = new BtEventCallback() {
        @Override
        public void onConnection(BluetoothDevice device, int status) {
            // Let the SDK finish dispatching its initialization event before
            // starting another SDK operation from our listener.
            mainHandler.post(() -> {
                if (released) return;
                if (status != StateCode.CONNECTION_OK) {
                    setCanStartOta(false);
                    return;
                }
                if (lastBleStatus != StateCode.CONNECTION_OK
                        || !Objects.equals(device, bleManager.getConnectedBtDevice())) return;
                if (!checkDeviceIsCertify(device) || getDeviceInfo(device) == null) {
                    Log.w(TAG, "SDK connection event arrived before authentication and device information were ready");
                    return;
                }
                Log.d(TAG, "SDK ready: authentication complete, device information available");
                setCanStartOta(true);
            });
        }

        @Override
        public void onError(BaseError error) {
            mainHandler.post(() -> {
                if (released) return;
                setCanStartOta(false);
                if (otaStatusCallback != null) {
                    otaStatusCallback.onPreparationError("OTA SDK initialization failed ("
                            + error.getCode() + "): " + error.getMessage());
                }
            });
        }
    };

    public OtaManager(Context context, String mac, String deviceName) {
        super(context);
        if (context == null) {
            throw new IllegalStateException("Context is required to initialize OtaManager");
        }
        this.context = context;
        this.mac = mac;
        this.deviceName = deviceName != null ? deviceName : "";
        this.bleManager = new BleManager(context, mac, this.deviceName);
        Log.d(TAG, "Initialized with mac: " + mac + ", deviceName: " + deviceName);

        // The plugin configures the SDK and registers its listener before scanning.
        registerBluetoothCallback(sdkEventCallback);
        registerBleEventCallback();
    }

    // Starts BLE scanning.
    public boolean startLeScan(long timeout) {
        try {
            boolean started = bleManager.startLeScan(timeout);
            Log.d(TAG, "BLE scan started: " + started + ", timeout: " + timeout + "ms");
            return started;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BLE scan: " + e.getMessage(), e);
            return false;
        }
    }

    public void stopLeScan() {
        try {
            bleManager.stopLeScan();
            Log.d(TAG, "Stopped BLE scan");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop BLE scan: " + e.getMessage(), e);
        }
    }

    // Registers BLE event callbacks.
    private void registerBleEventCallback() {
        bleManager.registerBleEventCallback(new BleEventCallback() {
            @Override
            public void onAdapterChange(boolean enabled) {
                if (!enabled && otaStatusCallback != null) {
                    otaStatusCallback.onPreparationError("Bluetooth was disabled while preparing OTA");
                }
            }

            @Override
            public void onDiscoveryBleChange(boolean started) {
                if (started) return;
                // Connecting stops the scan before setting its connecting-device field.
                mainHandler.post(() -> {
                    if (otaStatusCallback != null && !canStartOta
                            && !bleManager.isConnecting()
                            && bleManager.getConnectedBtDevice() == null) {
                        otaStatusCallback.onPreparationError(
                                "BLE scan ended without connecting to the target device");
                    }
                });
            }

            @Override
            public void onBleConnection(BluetoothDevice device, int status) {
                if (released || device == null) return;
                int connectStatus = changeConnectStatus(status);
                // Repeating CONNECTION_OK restarts the SDK authentication handshake.
                if (Objects.equals(lastBleDevice, device) && lastBleStatus == connectStatus) return;
                lastBleDevice = device;
                lastBleStatus = connectStatus;
                setCanStartOta(false);
                JL_Log.d(TAG, "onBleConnection - device: " + device.getAddress() + ", status: " + connectStatus);

                // Forward the device connection state to the OTA SDK.
                onBtDeviceConnection(device, connectStatus);

                // BLE readiness starts authentication; only the SDK's onConnection
                // callback above can allow an upgrade after initialization.
                if (connectStatus == StateCode.CONNECTION_DISCONNECT) {
                    if (otaStatusCallback != null) {
                        otaStatusCallback.onPreparationError("BLE connection failed while preparing OTA");
                    }
                }
            }

            @Override
            public void onBleDataNotification(BluetoothDevice device, UUID serviceUuid, UUID characteristicsUuid, byte[] data) {
                Log.d(TAG, "onBleDataNotification - device: " + device.getAddress() + ", data length: " + (data != null ? data.length : 0));
                onReceiveDeviceData(device, data);
            }

            @Override
            public void onBleDataBlockChanged(BluetoothDevice device, int block, int status) {
                Log.d(TAG, "onBleDataBlockChanged - device: " + device.getAddress() + ", block: " + block + ", status: " + status);
                onMtuChanged(getConnectedBluetoothGatt(), block, status);
            }
        });
    }

    // Sets whether OTA can start.
    private void setCanStartOta(boolean canStart) {
        if (this.canStartOta != canStart) {
            this.canStartOta = canStart;
            Log.d(TAG, "OTA canStart updated: " + canStart);
            if (otaStatusCallback != null) {
                otaStatusCallback.onCanStartOtaChanged(canStart);
            }
        }
    }

    // Sets the OTA state callback.
    public void setOtaStatusCallback(OtaStatusCallback callback) {
        this.otaStatusCallback = callback;
        // Immediately report the current state.
        if (callback != null) {
            callback.onCanStartOtaChanged(canStartOta);
        }
    }

    @Override
    public BluetoothDevice getConnectedDevice() {
        BluetoothDevice device = bleManager.getConnectedBtDevice();
        Log.d(TAG, "getConnectedDevice: " + (device != null ? device.getAddress() : "null"));
        return device;
    }

    @Override
    public BluetoothGatt getConnectedBluetoothGatt() {
        BluetoothDevice device = getConnectedDevice();
        BluetoothGatt gatt = device != null ? bleManager.getConnectedBtGatt(device) : null;
        Log.d(TAG, "getConnectedBluetoothGatt: " + (gatt != null ? "available" : "null"));
        return gatt;
    }

    @Override
    public void connectBluetoothDevice(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "connectBluetoothDevice: device is null");
            return;
        }
        try {
            boolean success = bleManager.connectBleDevice(device);
            Log.d(TAG, "connectBluetoothDevice - device: " + device.getAddress() + ", success: " + success);
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect device: " + device.getAddress() + ", error: " + e.getMessage(), e);
        }
    }

    public void reConnect(String address, boolean isUseAdv) {
        if (address == null || address.isEmpty()) {
            Log.w(TAG, "reConnect: address is invalid");
            return;
        }
        try {
            bleManager.reconnectDevice(address, isUseAdv);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reconnect: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnectBluetoothDevice(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "disconnectBluetoothDevice: device is null");
            return;
        }
        try {
            bleManager.disconnectBleDevice(device);
            Log.d(TAG, "disconnectBluetoothDevice - device: " + device.getAddress());
        } catch (Exception e) {
            Log.e(TAG, "Failed to disconnect device: " + device.getAddress() + ", error: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean sendDataToDevice(BluetoothDevice device, byte[] data) {
        if (device == null || data == null || data.length == 0) {
            Log.w(TAG, "sendDataToDevice: invalid parameters - device: " + device + ", data length: " + (data != null ? data.length : 0));
            return false;
        }
        try {
            bleManager.writeDataByBleAsync(device, BleManager.BLE_UUID_SERVICE, BleManager.BLE_UUID_WRITE, data, new OnWriteDataCallback() {
                @Override
                public void onBleResult(BluetoothDevice device, UUID serviceUUID, UUID characteristicUUID, boolean result, byte[] data) {
                    Log.d(TAG, "sendDataToDevice result - device: " + device.getAddress() + ", success: " + result);
                }
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send data to device: " + device.getAddress() + ", error: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        canStartOta = false;
        otaStatusCallback = null;
        unregisterBluetoothCallback(sdkEventCallback);
        mainHandler.removeCallbacksAndMessages(null);
        super.release();
        try {
            bleManager.destroy();
            canStartOta = false;
            mac = "";
            deviceName = "";
            otaStatusCallback = null;
            Log.d(TAG, "Resources released");
        } catch (Exception e) {
            Log.e(TAG, "Failed to release resources: " + e.getMessage(), e);
        }
    }

    private int changeConnectStatus(int status) {
        switch (status) {
            case BluetoothProfile.STATE_CONNECTED:
                return StateCode.CONNECTION_OK;
            case BluetoothProfile.STATE_CONNECTING:
                return StateCode.CONNECTION_CONNECTING;
            case BluetoothProfile.STATE_DISCONNECTED:
            case BluetoothProfile.STATE_DISCONNECTING:
            default:
                return StateCode.CONNECTION_DISCONNECT;
        }
    }
}

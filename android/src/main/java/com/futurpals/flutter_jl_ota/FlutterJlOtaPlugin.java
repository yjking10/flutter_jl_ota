package com.futurpals.flutter_jl_ota;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.futurpals.flutter_jl_ota.tool.AppUtil;
import com.futurpals.flutter_jl_ota.tool.ConfigHelper;
import com.jieli.jl_bt_ota.constant.JL_Constant;
import com.jieli.jl_bt_ota.interfaces.IActionCallback;
import com.jieli.jl_bt_ota.interfaces.IUpgradeCallback;
import com.jieli.jl_bt_ota.model.BluetoothOTAConfigure;
import com.jieli.jl_bt_ota.model.base.BaseError;
import com.jieli.jl_bt_ota.model.response.TargetInfoResponse;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;


import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * FlutterJlOtaPlugin
 * Flutter plugin entry point for the JL OTA flow.
 */
public class FlutterJlOtaPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private static final String TAG = "FlutterJlOtaPlugin";
    private static final long SCAN_TIMEOUT_MS = 20_000L;
    // Allow the scan (20s) and BLE connection setup (40s) to finish.
    private static final long PREPARATION_TIMEOUT_MS = 60_000L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MethodChannel channel;
    private Context context;
    private OtaManager otaManager;
    private boolean otaStartRequested = false;
    private boolean waitingForConnection = false;
    private Runnable preparationTimeout;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_jl_ota");
        channel.setMethodCallHandler(this);
        context = flutterPluginBinding.getApplicationContext();
        ConfigHelper.initialize(context); // Initialize ConfigHelper.
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    }

    @Override
    public void onDetachedFromActivity() {
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        releaseOtaManager();
        channel.setMethodCallHandler(null);
        channel = null;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;

            case "getSdkVersion":
                result.success(String.format(
                        Locale.US,
                        "V%s(%d)",
                        JL_Constant.getLibVersionName(),
                        JL_Constant.getLibVersionCode()
                ));
                break;

            case "isOta":
                result.success(otaManager != null && otaManager.isOTA());
                break;

            case "startScan":
                if (!ensureBluetoothAvailable(result)) return;
                if (!ensureScanPermission(result)) return;
                initOtaManagerIfNeeded("", ""); // No specific UUID or name is required for scanning.
                if (otaManager.startLeScan(SCAN_TIMEOUT_MS)) {
                    result.success(true);
                } else {
                    releaseOtaManager();
                    result.error("BLE_SCAN_FAILED", "BLE scanning could not be started", null);
                }
                break;

            case "stopScan":
                if (otaManager == null) {
                    result.success(false);
                    return;
                }
                otaManager.stopLeScan();
                result.success(true);
                break;

            case "connectDevice":
                if (!ensureBluetoothAvailable(result)) return;
                String uuid = call.argument("uuid");
                String deviceName = call.argument("deviceName");
                if (uuid != null && !uuid.isEmpty()) {
                    initOtaManagerIfNeeded(uuid, deviceName != null ? deviceName : "");
                    BluetoothDevice device = getBluetoothDeviceById(uuid);
                    if (device != null) {
                        otaManager.connectBluetoothDevice(device);
                        result.success(true);
                    } else {
                        result.error("DEVICE_NOT_FOUND", "Device with UUID " + uuid + " not found", null);
                    }
                } else {
                    result.error("INVALID_UUID", "UUID is invalid or empty", null);
                }
                break;

            case "getDeviceInfo":
                if (!ensureBluetoothAvailable(result)) return;
                if (otaManager == null) {
                    result.error("NOT_INITIALIZED", "OTA manager not initialized", null);
                    return;
                }
                otaManager.queryMandatoryUpdate(new IActionCallback<TargetInfoResponse>() {
                    @Override
                    public void onSuccess(TargetInfoResponse response) {
                        Log.d(TAG, "getDeviceInfo success, needForcedUpdate: " + true);
                        result.success(true);
                    }

                    @Override
                    public void onError(BaseError error) {
                        Log.e(TAG, "getDeviceInfo error: " + error.getMessage());
                        result.error("GET_DEVICE_INFO_FAILED", "Error getting device info: " + error.getMessage(), null);
                    }
                });
                break;

            case "startOtaUpdate":
                if (!ensureBluetoothAvailable(result)) return;
                if (!ensureScanPermission(result)) return;
                if (waitingForConnection || otaStartRequested
                        || (otaManager != null && otaManager.isOTA())) {
                    result.error("OTA_IN_PROGRESS", "An OTA request is already in progress", null);
                    return;
                }
                String otaUuid = call.argument("uuid");
                String filePath = call.argument("filePath");
                String otaDeviceName = call.argument("deviceName");
                if (otaUuid != null && !otaUuid.isEmpty() && filePath != null && !filePath.isEmpty()) {
                    try {
                        initOtaManagerIfNeeded(otaUuid, otaDeviceName != null ? otaDeviceName : "");
                        startOtaUpdate(otaUuid, filePath, result);
                    } catch (RuntimeException error) {
                        releaseOtaManager();
                        Log.e(TAG, "Failed to prepare OTA", error);
                        result.error("OTA_START_FAILED", error.getMessage(), null);
                    }
                } else {
                    result.error("INVALID_PARAMS", "UUID or filePath is invalid or empty", null);
                }
                break;

            case "cancelOtaUpdate":
                if (otaManager == null) {
                    result.success(false);
                    return;
                }
                if (waitingForConnection || !otaManager.isOTA()) {
                    releaseOtaManager();
                    invokeProgress(0, "CANCELED");
                } else {
                    otaManager.cancelOTA();
                }
                result.success(true); // Dual-bank OTA supports cancellation.
                break;

            default:
                result.notImplemented();
                break;
        }
    }

    private boolean ensureBluetoothAvailable(@NonNull Result result) {
        if (!AppUtil.checkHasConnectPermission(context)) {
            result.error("MISSING_PERMISSION", "Bluetooth connect permission is required", null);
            return false;
        }
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            result.error("BLUETOOTH_NOT_AVAILABLE", "Bluetooth is not available or not enabled", null);
            return false;
        }
        return true;
    }

    private boolean ensureScanPermission(@NonNull Result result) {
        if (!AppUtil.checkHasScanPermission(context)) {
            result.error("MISSING_PERMISSION",
                    "Bluetooth scan or legacy Android location permission is required", null);
            return false;
        }
        return true;
    }

    // Initializes OtaManager or recreates it when the target UUID changes.
    private void initOtaManagerIfNeeded(String mac, String deviceName) {
        if (otaManager == null) {
            otaManager = new OtaManager(context, mac, deviceName);
            configureOtaManager();
            Log.d(TAG, "OtaManager initialized with mac: " + mac);
        } else if (!mac.isEmpty() && !otaManager.mac.equals(mac)) {
            releaseOtaManager();
            otaManager = new OtaManager(context, mac, deviceName);
            configureOtaManager();
            Log.d(TAG, "OtaManager reinitialized with new mac: " + mac);
        }
    }

    // Configures the default OtaManager options.
    private void configureOtaManager() {
        BluetoothOTAConfigure config = BluetoothOTAConfigure.createDefault()
                .setPriority(BluetoothOTAConfigure.PREFER_BLE)
                .setUseAuthDevice(true)
                .setBleIntervalMs(500)
                .setTimeoutMs(3000)
                .setMtu(500)
                .setNeedChangeMtu(false)
                .setUseReconnect(true)
                .setFirmwareFilePath("");
        otaManager.configure(config);
    }

    // Starts the OTA update.
    private void startOtaUpdate(String uuid, String filePath, Result result) {
        final OtaManager manager = otaManager;
        otaStartRequested = false;
        waitingForConnection = true;
        preparationTimeout = () -> failPreparation(manager, "OTA connection preparation timed out");
        mainHandler.postDelayed(preparationTimeout, PREPARATION_TIMEOUT_MS);
        manager.setOtaStatusCallback(new OtaStatusCallback() {
            @Override
            public void onCanStartOtaChanged(boolean canStartOta) {
                if (otaManager != manager || !waitingForConnection || !canStartOta
                        || otaStartRequested || manager.isOTA()) return;

                clearPreparationTimeout();
                waitingForConnection = false;
                otaStartRequested = true;
                Log.d(TAG, "SDK authentication and initialization ready, starting upgrade for: " + uuid);
                try {
                    startOtaWithFile(filePath);
                } catch (RuntimeException error) {
                    releaseOtaManager();
                    invokeProgress(0, "ERROR: " + error.getMessage());
                    return;
                }

            }

            @Override
            public void onPreparationError(String reason) {
                failPreparation(manager, reason);
            }
        });
        if (waitingForConnection && !manager.startLeScan(SCAN_TIMEOUT_MS)) {
            releaseOtaManager();
            result.error("BLE_SCAN_FAILED", "BLE scanning could not be started", null);
            return;
        }
        // This acknowledges preparation; STARTED reports the actual SDK upgrade.
        result.success(true);
    }

    private void clearPreparationTimeout() {
        if (preparationTimeout != null) {
            mainHandler.removeCallbacks(preparationTimeout);
            preparationTimeout = null;
        }
    }

    private void failPreparation(OtaManager manager, String reason) {
        if (otaManager != manager || !waitingForConnection) return;
        Log.e(TAG, reason);
        releaseOtaManager();
        invokeProgress(0, "ERROR: " + reason);
    }

    private void releaseOtaManager() {
        clearPreparationTimeout();
        waitingForConnection = false;
        otaStartRequested = false;
        OtaManager manager = otaManager;
        // Ignore callbacks from the old request while releasing its resources.
        otaManager = null;
        if (manager != null) {
            manager.setOtaStatusCallback(null);
            try {
                manager.release();
            } catch (RuntimeException error) {
                Log.e(TAG, "Failed to release OTA resources", error);
            }
        }
    }

    private void startOtaWithFile(String filePath) {
        final OtaManager manager = otaManager;
        Log.i(TAG, "Start to Set: " + filePath);
        manager.getBluetoothOption().setFirmwareFilePath(filePath);
        manager.startOTA(new IUpgradeCallback() {
            @Override
            public void onStartOTA() {
                if (otaManager != manager) return;
                Log.d(TAG, "OTA started");
                invokeProgress(0, "STARTED");
            }

            @Override
            public void onNeedReconnect(String addr, boolean isNewReconnectWay) {
                if (otaManager != manager) return;
                if (manager.getBluetoothOption().isUseReconnect()) {
                    Log.d(TAG, "Reconnecting to: " + addr + ", new way: " + isNewReconnectWay);
                    manager.reConnect(addr, isNewReconnectWay);
                }
            }

            @Override
            public void onProgress(int type, float progress) {
                if (otaManager != manager) return;
                int progressInt = (int) progress;
                String status = type == 0 ? "DOWNLOADING_LOADER" : "UPGRADING_FIRMWARE";
                Log.d(TAG, "OTA progress: " + progressInt + "%, type: " + type);
                invokeProgress(progressInt, status);
            }

            @Override
            public void onStopOTA() {
                if (otaManager != manager) return;
                Log.d(TAG, "OTA completed");
                invokeProgress(100, "COMPLETED");
                releaseOtaManager();
            }

            @Override
            public void onCancelOTA() {
                if (otaManager != manager) return;
                Log.d(TAG, "OTA canceled");
                releaseOtaManager();
                invokeProgress(0, "CANCELED");
            }

            @Override
            public void onError(BaseError error) {
                if (otaManager != manager) return;
                Log.e(TAG, "OTA error: " + error.getMessage());
                releaseOtaManager();
                invokeProgress(0, "ERROR: " + error.getMessage());
            }
        });
    }


    // Sends progress updates through the MethodChannel.
    private void invokeProgress(int progress, String status) {
        mainHandler.post(() -> {
            if (channel == null) return;
            Map<String, Object> response = new HashMap<>();
            response.put("progress", progress);
            response.put("status", status);
            channel.invokeMethod("otaProgress", response);
        });
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice getBluetoothDeviceById(String remoteID) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        try {
            return bluetoothAdapter.getRemoteDevice(remoteID);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid remoteID: " + remoteID, e);
            return null;
        }
    }
}

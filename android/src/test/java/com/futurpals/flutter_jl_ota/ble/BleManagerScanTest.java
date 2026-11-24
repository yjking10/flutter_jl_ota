package com.futurpals.flutter_jl_ota.ble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;

import com.futurpals.flutter_jl_ota.tool.ConfigHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BleManagerScanTest {
    private Application application;
    private BleManager manager;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        ConfigHelper.initialize(application);
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true);
        shadowOf(application).denyPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT);
        manager = new BleManager(application, "AA:BB:CC:DD:EE:FF", "WaveNote");
    }

    @After
    public void tearDown() {
        manager.destroy();
    }

    @Test
    @Config(sdk = {31, 33})
    public void nearbyDevicesPermissionStartsScanWithoutLocation() {
        shadowOf(application).grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT);

        assertTrue(manager.startLeScan(20_000));
        assertTrue(manager.isBleScanning());
    }

    @Test
    public void missingScanPermissionDoesNotStartScan() {
        shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);

        assertFalse(manager.startLeScan(20_000));
        assertFalse(manager.isBleScanning());
    }

    @Test
    public void missingConnectPermissionDoesNotStartScan() {
        shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_SCAN);

        assertFalse(manager.startLeScan(20_000));
        assertFalse(manager.isBleScanning());
    }

    @Test
    public void disabledBluetoothDoesNotStartScan() {
        shadowOf(application).grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT);
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false);

        assertFalse(manager.startLeScan(20_000));
    }

    @Test
    @Config(sdk = 30)
    public void android11RequiresFineLocation() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);

        assertFalse(manager.startLeScan(20_000));
    }

    @Test
    @Config(sdk = 30)
    public void android11StartsScanWithFineLocation() {
        shadowOf(application).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        assertTrue(manager.startLeScan(20_000));
    }

    @Test
    @Config(sdk = 28)
    public void android9StartsScanWithCoarseLocation() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);

        assertTrue(manager.startLeScan(20_000));
    }

    @Test
    public void destroyClosesPendingGattAndIgnoresItsLateConnection() {
        shadowOf(application).grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT);
        BluetoothDevice device = mock(BluetoothDevice.class);
        BluetoothGatt gatt = mock(BluetoothGatt.class);
        when(device.getAddress()).thenReturn("AA:BB:CC:DD:EE:FF");
        when(gatt.getDevice()).thenReturn(device);
        when(device.connectGatt(eq(application), eq(false), any(),
                eq(BluetoothDevice.TRANSPORT_LE))).thenReturn(gatt);
        assertTrue(manager.connectBleDevice(device));
        ArgumentCaptor<BluetoothGattCallback> callback = ArgumentCaptor.forClass(BluetoothGattCallback.class);
        verify(device).connectGatt(eq(application), eq(false), callback.capture(),
                eq(BluetoothDevice.TRANSPORT_LE));

        manager.destroy();

        verify(gatt).disconnect();
        verify(gatt).close();
        callback.getValue().onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED);
        verify(gatt, never()).discoverServices();
        verify(gatt, atLeastOnce()).close();
        assertFalse(manager.isConnecting());
    }
}

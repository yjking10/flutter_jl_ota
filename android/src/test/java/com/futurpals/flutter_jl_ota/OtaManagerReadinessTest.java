package com.futurpals.flutter_jl_ota;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Looper;

import com.futurpals.flutter_jl_ota.ble.BleManager;
import com.futurpals.flutter_jl_ota.ble.interfaces.BleEventCallback;
import com.jieli.jl_bt_ota.constant.StateCode;
import com.jieli.jl_bt_ota.impl.RcspAuth;
import com.jieli.jl_bt_ota.model.BluetoothOTAConfigure;
import com.jieli.jl_bt_ota.model.OTAError;
import com.jieli.jl_bt_ota.model.response.TargetInfoResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class OtaManagerReadinessTest {
    private static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
    private MockedConstruction<RcspAuth> authentication;
    private MockedConstruction<BleManager> bluetooth;
    private BleEventCallback bleEvents;
    private SdkManager manager;
    private BluetoothDevice device;
    private OtaStatusCallback listener;

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT);
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true);
        device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS);
        // Keep the actual Java SDK and adapter. Only the JNI handshake and BLE
        // hardware are replaced, so BLE-ready and SDK-ready remain distinct.
        authentication = mockConstruction(RcspAuth.class, (auth, context) ->
                when(auth.startAuth(any())).thenReturn(true));
        bluetooth = mockConstruction(BleManager.class, (ble, context) -> {
            doAnswer(call -> {
                bleEvents = call.getArgument(0);
                return null;
            }).when(ble).registerBleEventCallback(any());
        });
        manager = new SdkManager(application);
        manager.configure(BluetoothOTAConfigure.createDefault()
                .setUseAuthDevice(true).setNeedChangeMtu(false).setUseReconnect(true));
        listener = mock(OtaStatusCallback.class);
        manager.setOtaStatusCallback(listener);
        assertNotNull(bleEvents);
    }

    @After
    public void tearDown() {
        if (manager != null) manager.release();
        bluetooth.close();
        authentication.close();
    }

    private void connectTransport() {
        when(bluetooth.constructed().get(0).getConnectedBtDevice()).thenReturn(device);
        bleEvents.onBleConnection(device, BluetoothProfile.STATE_CONNECTED);
    }

    private void sdkConnected() {
        manager.onConnection(device, StateCode.CONNECTION_OK);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void bleConnectionStartsAuthenticationWithoutAllowingUpgrade() {
        connectTransport();
        shadowOf(Looper.getMainLooper()).idle();

        verify(authentication.constructed().get(0)).startAuth(device);
        verify(listener, never()).onCanStartOtaChanged(true);
    }

    @Test
    public void sdkConnectionAfterAuthenticationAndInitializationAllowsUpgrade() {
        connectTransport();
        verify(listener, never()).onCanStartOtaChanged(true);
        manager.authenticated(device);
        manager.initialized(device);

        manager.onConnection(device, StateCode.CONNECTION_OK);
        verify(listener, never()).onCanStartOtaChanged(true);
        shadowOf(Looper.getMainLooper()).idle();

        verify(listener).onCanStartOtaChanged(true);
    }

    @Test
    public void sdkConnectionWithoutAuthenticationCannotAllowUpgrade() {
        connectTransport();
        manager.initialized(device);

        sdkConnected();

        verify(listener, never()).onCanStartOtaChanged(true);
    }

    @Test
    public void sdkConnectionWithoutDeviceInformationCannotAllowUpgrade() {
        connectTransport();
        manager.authenticated(device);

        sdkConnected();

        verify(listener, never()).onCanStartOtaChanged(true);
    }

    @Test
    public void duplicateBleConnectionDoesNotRestartAuthentication() {
        connectTransport();
        connectTransport();

        verify(authentication.constructed().get(0)).startAuth(device);
    }

    @Test
    public void sdkAuthenticationErrorIsForwardedDuringPreparation() {
        connectTransport();

        manager.errorEventCallback(OTAError.buildError(20481));
        shadowOf(Looper.getMainLooper()).idle();

        verify(listener).onPreparationError(contains("Device authentication fails"));
        verify(listener, never()).onCanStartOtaChanged(true);
    }

    @Test
    public void disconnectBeforeQueuedSdkReadinessPreventsUpgrade() {
        connectTransport();
        manager.authenticated(device);
        manager.initialized(device);
        manager.onConnection(device, StateCode.CONNECTION_OK);

        when(bluetooth.constructed().get(0).getConnectedBtDevice()).thenReturn(null);
        bleEvents.onBleConnection(device, BluetoothProfile.STATE_DISCONNECTED);
        shadowOf(Looper.getMainLooper()).idle();

        verify(listener, never()).onCanStartOtaChanged(true);
    }

    private static class SdkManager extends OtaManager {
        SdkManager(Context context) {
            super(context, ADDRESS, "WaveNote");
        }

        void authenticated(BluetoothDevice device) {
            mDeviceStatusCache.updateDeviceIsAuth(device, true);
        }

        void initialized(BluetoothDevice device) {
            mDeviceStatusCache.updateDeviceTargetInfo(device, new TargetInfoResponse());
        }
    }
}

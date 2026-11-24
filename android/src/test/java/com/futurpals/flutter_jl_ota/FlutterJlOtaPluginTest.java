package com.futurpals.flutter_jl_ota;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.os.Looper;

import com.jieli.jl_bt_ota.interfaces.IUpgradeCallback;
import com.jieli.jl_bt_ota.model.BluetoothOTAConfigure;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FlutterJlOtaPluginTest {
    private final Map<OtaManager, OtaStatusCallback> callbacks = new IdentityHashMap<>();
    private final Map<OtaManager, IUpgradeCallback> upgrades = new IdentityHashMap<>();
    private MockedConstruction<OtaManager> managers;
    private MockedConstruction<MethodChannel> channels;
    private FlutterJlOtaPlugin plugin;
    private boolean scanAccepted = true;

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT);
        shadowOf(application).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true);

        channels = mockConstruction(MethodChannel.class);
        managers = mockConstruction(OtaManager.class, (manager, context) -> {
            manager.mac = (String) context.arguments().get(1);
            when(manager.startLeScan(anyLong())).thenAnswer(call -> scanAccepted);
            when(manager.getBluetoothOption()).thenReturn(BluetoothOTAConfigure.createDefault());
            doAnswer(call -> {
                OtaStatusCallback callback = call.getArgument(0);
                if (callback != null) callbacks.put(manager, callback);
                return null;
            }).when(manager).setOtaStatusCallback(any());
            doAnswer(call -> {
                upgrades.put(manager, call.getArgument(0));
                return null;
            }).when(manager).startOTA(any());
        });

        FlutterPluginBinding binding = mock(FlutterPluginBinding.class);
        when(binding.getApplicationContext()).thenReturn(application);
        when(binding.getBinaryMessenger()).thenReturn(mock(BinaryMessenger.class));
        plugin = new FlutterJlOtaPlugin();
        plugin.onAttachedToEngine(binding);
    }

    @After
    public void tearDown() {
        plugin.onDetachedFromEngine(null);
        managers.close();
        channels.close();
    }

    private MethodChannel.Result start() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("uuid", "AA:BB:CC:DD:EE:FF");
        arguments.put("filePath", "/tmp/ble.ufw");
        MethodChannel.Result result = mock(MethodChannel.Result.class);
        plugin.onMethodCall(new MethodCall("startOtaUpdate", arguments), result);
        return result;
    }

    private OtaManager manager() {
        return managers.constructed().get(managers.constructed().size() - 1);
    }

    private void verifyProgress(String status) {
        verify(channels.constructed().get(0)).invokeMethod(eq("otaProgress"), argThat(value ->
                value instanceof Map && status.equals(((Map<?, ?>) value).get("status"))));
    }

    @Test
    public void configuresAndRegistersCallbackBeforeScanning() {
        MethodChannel.Result result = start();

        InOrder order = inOrder(manager());
        order.verify(manager()).configure(any());
        verify(manager()).configure(argThat(config -> config.isUseAuthDevice()));
        order.verify(manager()).setOtaStatusCallback(any());
        order.verify(manager()).startLeScan(anyLong());
        verify(result).success(true);
        verify(manager(), never()).startOTA(any());
    }

    @Test
    public void scanRejectionReturnsAnErrorAndReleasesManager() {
        scanAccepted = false;

        MethodChannel.Result result = start();

        verify(result).error(eq("BLE_SCAN_FAILED"), anyString(), any());
        verify(result, never()).success(any());
        verify(manager()).release();
    }

    @Test
    public void missingBluetoothPermissionIsReportedBeforeCreatingManager() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                Manifest.permission.BLUETOOTH_SCAN);

        MethodChannel.Result result = start();

        verify(result).error(eq("MISSING_PERMISSION"), anyString(), any());
        assertEquals(0, managers.constructed().size());
    }

    @Test
    public void connectionTimeoutReportsFailureAndAllowsRetry() {
        start();
        OtaManager first = manager();

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));

        verify(first).release();
        verifyProgress("ERROR: OTA connection preparation timed out");
        start();
        assertEquals(2, managers.constructed().size());
    }

    @Test
    public void readyConnectionStartsUpgradeOnceAndCancelsPreparationTimeout() {
        start();
        OtaStatusCallback callback = callbacks.get(manager());

        callback.onCanStartOtaChanged(true);
        callback.onCanStartOtaChanged(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));

        verify(manager()).startOTA(any());
        verify(manager(), never()).queryMandatoryUpdate(any());
        verify(manager(), never()).release();
    }

    @Test
    public void cancellingPreparationReleasesManagerAndPreventsLateStart() {
        start();
        OtaManager first = manager();
        OtaStatusCallback callback = callbacks.get(first);
        MethodChannel.Result result = mock(MethodChannel.Result.class);

        plugin.onMethodCall(new MethodCall("cancelOtaUpdate", null), result);
        callback.onCanStartOtaChanged(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));

        verify(result).success(true);
        verify(first).release();
        verify(first, never()).startOTA(any());
        verifyProgress("CANCELED");
    }

    @Test
    public void callbacksFromCancelledRequestCannotStartRetry() {
        start();
        OtaManager first = manager();
        OtaStatusCallback oldCallback = callbacks.get(first);
        plugin.onMethodCall(new MethodCall("cancelOtaUpdate", null), mock(MethodChannel.Result.class));
        start();
        OtaManager second = manager();

        oldCallback.onCanStartOtaChanged(true);

        verify(first, never()).startOTA(any());
        verify(second, never()).startOTA(any());
        callbacks.get(second).onCanStartOtaChanged(true);
        verify(second).startOTA(any());
    }

    @Test
    public void completedUpgradeReleasesManagerAndAllowsFreshRequest() {
        start();
        OtaManager first = manager();
        callbacks.get(first).onCanStartOtaChanged(true);

        upgrades.get(first).onStopOTA();
        shadowOf(Looper.getMainLooper()).idle();

        verify(first).release();
        verifyProgress("COMPLETED");
        start();
        assertEquals(2, managers.constructed().size());
    }

    @Test
    public void preparationFailureReleasesManagerAndReportsReason() {
        start();

        callbacks.get(manager()).onPreparationError("BLE connection failed");
        shadowOf(Looper.getMainLooper()).idle();

        verify(manager()).release();
        verifyProgress("ERROR: BLE connection failed");
    }
}

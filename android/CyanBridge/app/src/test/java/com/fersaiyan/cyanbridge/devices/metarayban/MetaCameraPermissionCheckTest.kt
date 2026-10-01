package com.fersaiyan.cyanbridge.devices.metarayban

import android.Manifest
import com.meta.wearable.dat.core.types.PermissionError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MetaCameraPermissionCheckTest {
    @Test fun registrationConnectionRaceRetriesUntilGranted() = runTest {
        var calls = 0
        val result = checkMetaCameraPermissionWithRetry {
            if (++calls < 4) MetaCameraPermissionCheck.Failed(PermissionError.NO_DEVICE_WITH_CONNECTION, "All discovered devices are powered off or disconnected")
            else MetaCameraPermissionCheck.Granted
        }
        assertEquals(MetaCameraPermissionCheck.Granted, result)
        assertEquals(4, calls)
    }

    @Test fun denialReturnsImmediatelySoTheUiCanRequestAuthorization() = runTest {
        var calls = 0
        assertEquals(MetaCameraPermissionCheck.Denied, checkMetaCameraPermissionWithRetry { calls++; MetaCameraPermissionCheck.Denied })
        assertEquals(1, calls)
    }

    @Test fun disconnectedAndMissingAppNeverBecomeSyntheticSuccess() = runTest {
        var calls = 0
        val disconnected = MetaCameraPermissionCheck.Failed(PermissionError.NO_DEVICE_WITH_CONNECTION, "disconnected")
        assertEquals(disconnected, checkMetaCameraPermissionWithRetry(attempts = 3) { calls++; disconnected })
        assertEquals(3, calls)
        calls = 0
        val missingApp = MetaCameraPermissionCheck.Failed(PermissionError.META_AI_NOT_INSTALLED, "Meta AI not installed")
        assertEquals(missingApp, checkMetaCameraPermissionWithRetry { calls++; missingApp })
        assertEquals(1, calls)
    }

    @Test fun android10And11DiscoveryRequiresLocationWhileAndroid12PlusUsesNearbyDevices() {
        assertEquals(setOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), MetaDatPermissions.required(29).toSet())
        assertEquals(MetaDatPermissions.required(29).toSet(), MetaDatPermissions.required(30).toSet())
        assertEquals(setOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), MetaDatPermissions.required(37).toSet())
    }
}

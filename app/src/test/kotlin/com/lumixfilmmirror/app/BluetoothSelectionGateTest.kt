package com.lumixfilmmirror.app

import com.lumixfilmmirror.camera.BluetoothConnectionStep
import org.junit.Assert.*
import org.junit.Test

class BluetoothSelectionGateTest {
    private val scanning = CameraWorkspaceState(operationRunning = true, bluetoothStep = BluetoothConnectionStep.SCANNING)
    @Test fun discoveredCameraCanBeChosenWhileScanOwnsBusyFlag() {
        val workspace = WorkspaceState(recovering = false,cameraBusy = true)
        assertTrue(workspace.busy)
        assertTrue(workspace.canSelectBluetoothCamera(scanning))
        assertTrue(workspace.canSelectBluetoothCamera(CameraWorkspaceState()))
    }
    @Test fun answeringScanDoesNotPermitParallelTransferOrSecondHandshake() {
        val workspace = WorkspaceState(recovering = false,cameraBusy = true)
        assertFalse(workspace.canSelectBluetoothCamera(scanning.copy(activeTransferBatch = "transfer")))
        assertFalse(workspace.canSelectBluetoothCamera(scanning.copy(bluetoothStep = BluetoothConnectionStep.CONNECTING)))
    }
    @Test fun photoImportExportRecoveryAndStorageFailureStillBlockConnection() {
        for (workspace in listOf(WorkspaceState(recovering=false,importing=true), WorkspaceState(recovering=false,exporting=true),
            WorkspaceState(recovering=false,lutImporting=true), WorkspaceState(recovering=false,batchBusy=true), WorkspaceState(recovering=true),
            WorkspaceState(recovering=false,storageFailure="unavailable")))
            assertFalse(workspace.canSelectBluetoothCamera(scanning))
    }
}

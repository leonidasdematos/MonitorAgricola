package com.example.monitoragricola.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.monitoragricola.App
import com.example.monitoragricola.R
import com.example.monitoragricola.hardware.gateway.GatewayConnectionConfig
import com.example.monitoragricola.hardware.gateway.GatewayConnectionMedium
import com.example.monitoragricola.hardware.gateway.GatewayConnectionPreferences
import com.example.monitoragricola.hardware.gateway.GatewayConnectionState
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnGatewayConnect: Button
    private lateinit var tvGatewayDevice: TextView
    private lateinit var tvGatewayStatus: TextView

    private val app get() = application as App
    private val gatewayManager get() = app.gatewayManager
    private val bluetoothAdapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    private var pendingAction: PendingAction? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            if (granted) {
                onBluetoothPrerequisitesSatisfied()
            } else {
                Toast.makeText(this, R.string.settings_gateway_permission_denied, Toast.LENGTH_SHORT).show()
                pendingAction = null
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter?.isEnabled == true) {
                onBluetoothPrerequisitesSatisfied()
            } else {
                Toast.makeText(this, R.string.settings_gateway_enable_bluetooth, Toast.LENGTH_SHORT).show()
                pendingAction = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnGatewayConnect = findViewById(R.id.btnGatewayConnect)
        tvGatewayDevice = findViewById(R.id.tvGatewayDevice)
        tvGatewayStatus = findViewById(R.id.tvGatewayStatus)

        btnGatewayConnect.setOnClickListener { onGatewayConnectClicked() }

        updateGatewaySelectionSummary()
        observeGatewayState()
    }

    private fun observeGatewayState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gatewayManager.connectionState.collect { state ->
                    updateGatewayStatus(state)
                }
            }
        }
    }

    private fun onGatewayConnectClicked() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, R.string.settings_gateway_bluetooth_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBluetoothPermissions()) {
            val permissions = requiredBluetoothPermissions()
            pendingAction = PendingAction.ShowDevicePicker
            if (permissions.isEmpty()) {
                onBluetoothPrerequisitesSatisfied()
            } else {
                permissionLauncher.launch(permissions)
            }
            return
        }
        if (!adapter.isEnabled) {
            pendingAction = PendingAction.ShowDevicePicker
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        showDeviceSelectionDialog()
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val connectGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        val scanGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
        return connectGranted && scanGranted
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            emptyArray()
        }
    }

    private fun onBluetoothPrerequisitesSatisfied() {
        when (pendingAction) {
            PendingAction.ShowDevicePicker -> showDeviceSelectionDialog()
            null -> Unit
        }
        pendingAction = null
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val adapter = bluetoothAdapter ?: return
        val devices = adapter.bondedDevices?.toList().orEmpty().sortedBy { device ->
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        }
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.settings_gateway_no_paired_devices, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = devices.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() }
                ?: getString(R.string.settings_gateway_unknown_device)
            "$name\n${device.address}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_gateway_select_device)
            .setItems(labels) { _, which ->
                connectToDevice(devices[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val address = device.address
        val name = device.name?.takeIf { it.isNotBlank() }
        GatewayConnectionPreferences.saveSelection(
            context = this,
            medium = GatewayConnectionMedium.BLUETOOTH,
            endpoint = address,
            deviceName = name,
        )
        updateGatewaySelectionSummary()

        val label = name ?: address
        Toast.makeText(this, getString(R.string.settings_gateway_connecting_to, label), Toast.LENGTH_SHORT).show()

        gatewayManager.ensureConnected(GatewayConnectionConfig(GatewayConnectionMedium.BLUETOOTH, address))
    }

    private fun updateGatewaySelectionSummary() {
        val selection = GatewayConnectionPreferences.loadSelection(this)
        val label = when {
            selection == null -> getString(R.string.settings_gateway_no_device)
            selection.medium == GatewayConnectionMedium.BLUETOOTH ->
                selection.deviceName?.takeIf { it.isNotBlank() }
                    ?: selection.endpoint
                    ?: getString(R.string.settings_gateway_no_device)
            else ->
                selection.endpoint?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.settings_gateway_default_endpoint_label)
        }
        tvGatewayDevice.text = getString(R.string.settings_gateway_selected_device, label)
        val buttonText = if (selection == null) {
            R.string.settings_gateway_connect_button
        } else {
            R.string.settings_gateway_change_device
        }
        btnGatewayConnect.setText(buttonText)
    }

    private fun updateGatewayStatus(state: GatewayConnectionState) {
        val statusText = when (state) {
            GatewayConnectionState.Disconnected -> getString(R.string.settings_gateway_status_disconnected)
            is GatewayConnectionState.Connecting -> {
                val label = describeConfig(state.config)
                getString(R.string.settings_gateway_status_connecting_with, label)
            }
            is GatewayConnectionState.Connected -> {
                val label = describeConfig(state.config)
                getString(R.string.settings_gateway_status_connected_with, label)
            }
            is GatewayConnectionState.Error -> {
                val reason = state.throwable.message ?: state.throwable.javaClass.simpleName
                getString(R.string.settings_gateway_status_error_with_reason, reason)
            }
        }
        tvGatewayStatus.text = statusText
    }

    private fun describeConfig(config: GatewayConnectionConfig): String {
        return when (config.medium) {
            GatewayConnectionMedium.BLUETOOTH -> {
                val selection = GatewayConnectionPreferences.loadSelection(this)
                selection?.deviceName?.takeIf { it.isNotBlank() }
                    ?: selection?.endpoint
                    ?: config.endpoint
                    ?: getString(R.string.settings_gateway_no_device)
            }
            GatewayConnectionMedium.CABLE -> {
                val endpoint = config.endpoint?.takeIf { it.isNotBlank() }
                endpoint ?: getString(R.string.settings_gateway_default_endpoint_label)
            }
        }
    }

    private enum class PendingAction { ShowDevicePicker }
}
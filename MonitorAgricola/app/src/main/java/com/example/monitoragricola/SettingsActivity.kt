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
import android.widget.EditText
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
import com.example.monitoragricola.hardware.ntrip.NtripConfig
import com.example.monitoragricola.hardware.ntrip.NtripConnectionState
import com.example.monitoragricola.hardware.ntrip.NtripPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnGatewayConnect: Button
    private lateinit var tvGatewayDevice: TextView
    private lateinit var tvGatewayStatus: TextView
    private lateinit var etNtripHost: EditText
    private lateinit var etNtripPort: EditText
    private lateinit var etNtripMount: EditText
    private lateinit var etNtripUser: EditText
    private lateinit var etNtripPassword: EditText
    private lateinit var btnNtripConnect: Button
    private lateinit var tvNtripStatus: TextView

    private val app get() = application as App
    private val gatewayManager get() = app.gatewayManager
    private val ntripClient get() = app.ntripClient
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
        etNtripHost = findViewById(R.id.etNtripHost)
        etNtripPort = findViewById(R.id.etNtripPort)
        etNtripMount = findViewById(R.id.etNtripMount)
        etNtripUser = findViewById(R.id.etNtripUser)
        etNtripPassword = findViewById(R.id.etNtripPassword)
        btnNtripConnect = findViewById(R.id.btnNtripConnect)
        tvNtripStatus = findViewById(R.id.tvNtripStatus)

        btnGatewayConnect.setOnClickListener { onGatewayConnectClicked() }
        btnNtripConnect.setOnClickListener { onNtripConnectClicked() }

        updateGatewaySelectionSummary()
        loadNtripPreferences()
        observeGatewayState()
        observeNtripState()
        updateNtripStatus(ntripClient.connectionState.value)
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

    private fun observeNtripState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ntripClient.connectionState.collect { state ->
                    updateNtripStatus(state)
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

    private fun onNtripConnectClicked() {
        val currentState = ntripClient.connectionState.value
        if (currentState is NtripConnectionState.Connected || currentState is NtripConnectionState.Connecting) {
            ntripClient.disconnect()
            return
        }
        val config = readNtripConfigFromForm()
        if (!config.isValid()) {
            Toast.makeText(this, R.string.settings_ntrip_missing_fields, Toast.LENGTH_SHORT).show()
            return
        }
        NtripPreferences.save(this, config)
        ntripClient.ensureConnected(config)
    }

    private fun readNtripConfigFromForm(): NtripConfig {
        val host = etNtripHost.text?.toString()?.trim().orEmpty()
        val port = etNtripPort.text?.toString()?.toIntOrNull()?.coerceIn(1, 65535) ?: 2101
        val mount = etNtripMount.text?.toString()?.trim().orEmpty()
        val user = etNtripUser.text?.toString()?.trim().orEmpty()
        val password = etNtripPassword.text?.toString().orEmpty()
        return NtripConfig(host, port, mount, user, password)
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

    private fun updateNtripStatus(state: NtripConnectionState) {
        val statusText = when (state) {
            NtripConnectionState.Disconnected -> getString(R.string.settings_ntrip_status_disconnected)
            is NtripConnectionState.Connecting -> {
                val label = describeNtripConfig(state.config)
                getString(R.string.settings_ntrip_status_connecting_with, label)
            }
            is NtripConnectionState.Connected -> {
                val label = describeNtripConfig(state.config)
                getString(R.string.settings_ntrip_status_connected_with, label)
            }
            is NtripConnectionState.Error -> {
                val reason = state.throwable.message ?: state.throwable.javaClass.simpleName
                getString(R.string.settings_ntrip_status_error_with_reason, reason)
            }
        }
        tvNtripStatus.text = statusText
        val buttonText = if (state is NtripConnectionState.Connected || state is NtripConnectionState.Connecting) {
            R.string.settings_ntrip_disconnect_button
        } else {
            R.string.settings_ntrip_connect_button
        }
        btnNtripConnect.setText(buttonText)
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

    private fun loadNtripPreferences() {
        val config = NtripPreferences.load(this)
        etNtripHost.setText(config.host)
        etNtripPort.setText(config.port.takeIf { it > 0 }?.toString().orEmpty())
        etNtripMount.setText(config.mountPoint)
        etNtripUser.setText(config.username)
        etNtripPassword.setText(config.password)
    }

    private fun describeNtripConfig(config: NtripConfig): String {
        val mount = config.mountPoint.takeIf { it.isNotBlank() } ?: "?"
        return getString(R.string.settings_ntrip_config_label, config.host, config.port, mount)
    }


    private enum class PendingAction { ShowDevicePicker }
}
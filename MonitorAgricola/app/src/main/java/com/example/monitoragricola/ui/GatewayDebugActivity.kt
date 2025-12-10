package com.example.monitoragricola.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.monitoragricola.App
import com.example.monitoragricola.R
import com.example.monitoragricola.hardware.gateway.GatewayConnectionState
import com.example.monitoragricola.hardware.gateway.GatewayImplementTelemetry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class GatewayDebugActivity : AppCompatActivity() {

    private lateinit var connectionStateView: TextView
    private lateinit var telemetryStateView: TextView
    private lateinit var telemetryRawView: TextView
    private lateinit var implementView: GatewayImplementDebugView

    private val app get() = application as App
    private val gatewayManager get() = app.gatewayManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_debug)
        title = getString(R.string.gateway_debug_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        connectionStateView = findViewById(R.id.tvGatewayConnectionState)
        telemetryStateView = findViewById(R.id.tvGatewayTelemetryState)
        telemetryRawView = findViewById(R.id.tvGatewayTelemetryRaw)
        implementView = findViewById(R.id.gatewayImplementDebugView)

        observeGateway()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun observeGateway() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gatewayManager.connectionState.collectLatest { updateConnectionState(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gatewayManager.implementTelemetry.collectLatest { updateTelemetry(it) }
            }
        }
    }

    private fun updateConnectionState(state: GatewayConnectionState) {
        val text = when (state) {
            is GatewayConnectionState.Connected -> {
                getString(R.string.gateway_debug_state_connected, state.config.describe())
            }

            is GatewayConnectionState.Connecting -> {
                getString(R.string.gateway_debug_state_connecting, state.config.describe())
            }

            is GatewayConnectionState.Disconnected -> {
                getString(R.string.gateway_debug_state_disconnected)
            }

            is GatewayConnectionState.Error -> {
                getString(R.string.gateway_debug_state_error, state.config.describe(), state.throwable.message)
            }
        }
        connectionStateView.text = text
    }

    private fun updateTelemetry(telemetry: GatewayImplementTelemetry?) {
        implementView.updateTelemetry(telemetry)

        if (telemetry == null) {
            telemetryStateView.text = getString(R.string.gateway_debug_no_telemetry)
            telemetryRawView.text = ""
            return
        }

        val age = System.currentTimeMillis() - telemetry.timestampMillis
        val ageText = DateUtils.getRelativeTimeSpanString(
            telemetry.timestampMillis,
            System.currentTimeMillis(),
            DateUtils.SECOND_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        telemetryStateView.text = getString(
            R.string.gateway_debug_telemetry_summary,
            telemetry.mode ?: getString(R.string.gateway_debug_unknown_mode),
            telemetry.activeSectionsMask,
            if (telemetry.isImplementActive) getString(R.string.gateway_debug_active) else getString(R.string.gateway_debug_inactive),
            ageText,
            age,
        )

        telemetryRawView.text = buildTelemetryDump(telemetry)
    }

    private fun buildTelemetryDump(telemetry: GatewayImplementTelemetry): CharSequence {
        val builder = SpannableStringBuilder()
        builder.appendLine("isImplementActive = ${telemetry.isImplementActive}")
        builder.appendLine("activeSectionsMask = 0x${telemetry.activeSectionsMask.toString(16)}")
        builder.appendLine("rateValue = ${telemetry.rateValue}")
        builder.appendLine("timestampMillis = ${telemetry.timestampMillis}")
        builder.appendLine("mode = ${telemetry.mode}")
        builder.appendLine("articulated = ${telemetry.articulated}")

        telemetry.articulation?.let { art ->
            builder.appendLine("articulation:")
            builder.appendLine("  antennaToJointMeters = ${art.antennaToJointMeters}")
            builder.appendLine("  jointToImplementMeters = ${art.jointToImplementMeters}")
            builder.appendLine("  antennaLocal = (${art.antennaLocalX}, ${art.antennaLocalY})")
            builder.appendLine("  jointLocal = (${art.jointLocalX}, ${art.jointLocalY})")
            builder.appendLine("  implementLocal = (${art.implementLocalX}, ${art.implementLocalY})")
            builder.appendLine("  jointLatLon = (${art.jointLat}, ${art.jointLon})")
            builder.appendLine("  implementLatLon = (${art.implementLat}, ${art.implementLon})")
            builder.appendLine("  axis = (${art.axisX}, ${art.axisY})")
            builder.appendLine("  thetaRad = ${art.thetaRad}")
            builder.appendLine("  hasMotion = ${art.hasMotion}")
        } ?: builder.appendLine("articulation = null")

        return builder
    }

    private fun GatewayConnectionState.Connected.describe(): String = config.describe()
    private fun GatewayConnectionState.Connecting.describe(): String = config.describe()
    private fun GatewayConnectionState.Error.describe(): String = config.describe()

    private fun com.example.monitoragricola.hardware.gateway.GatewayConnectionConfig.describe(): String {
        val medium = medium.name.lowercase(Locale.ROOT)
        val endpointText = endpoint ?: getString(R.string.settings_gateway_default_endpoint_label)
        return "$medium@$endpointText"
    }
}
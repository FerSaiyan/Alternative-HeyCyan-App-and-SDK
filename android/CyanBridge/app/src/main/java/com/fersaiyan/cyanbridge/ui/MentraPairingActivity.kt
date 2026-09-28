package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.mentra.bluetoothsdk.Device
import kotlinx.coroutines.launch

/** Mentra SDK discovery is deliberately independent from the Oudmon scanner. */
class MentraPairingActivity : AppCompatActivity() {
    private lateinit var manager: MentraLiveManager
    private lateinit var status: TextView
    private lateinit var choices: LinearLayout
    private var pendingDevice: Device? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = MentraLiveManager.getInstance(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply { text = "Mentra Live — select Scan to discover your glasses." }
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(status)
        layout.addView(Button(this).apply { text = "Scan Mentra Live"; setOnClickListener { scan() } })
        layout.addView(Button(this).apply {
            text = "Disconnect Mentra Live"
            setOnClickListener {
                manager.disconnect()
                status.text = "Disconnected"
            }
        })
        layout.addView(choices)
        setContentView(ScrollView(this).apply { addView(layout) })
        lifecycleScope.launch {
            manager.glasses.collect { state ->
                runOnUiThread {
                    if (state.connected) {
                        status.text = if (state.ready) "Mentra Live connected and ready" else "Mentra Live connecting..."
                        pendingDevice?.let { selected ->
                            DeviceProfileStore.saveLastSelected(
                                this@MentraPairingActivity,
                                DeviceProfile(
                                    macAddress = selected.address ?: selected.id,
                                    advertisedName = selected.name,
                                    detectedClass = DeviceClass.MENTRA_LIVE,
                                    selectedClass = DeviceClass.MENTRA_LIVE,
                                    userOverridden = false,
                                ),
                            )
                            pendingDevice = null
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun scan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
            return
        }
        status.text = "Scanning for Mentra Live..."
        choices.removeAllViews()
        runCatching {
            manager.scan { devices ->
                runOnUiThread {
                    choices.removeAllViews()
                    status.text = if (devices.isEmpty()) "No Mentra Live glasses found" else "Choose your glasses"
                    devices.forEach { device ->
                        choices.addView(Button(this).apply {
                            text = device.name + (device.address?.let { " (" + it + ")" } ?: "")
                            setOnClickListener {
                                pendingDevice = device
                                status.text = "Connecting to " + device.name
                                runCatching { manager.connect(device) }.onFailure { error ->
                                    pendingDevice = null
                                    status.text = error.message ?: "Connection failed"
                                }
                            }
                        })
                    }
                }
            }
        }.onFailure { status.text = it.message ?: "Mentra scan failed" }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scan()
            else Toast.makeText(this, "Bluetooth permission is required", Toast.LENGTH_LONG).show()
        }
    }

    companion object { private const val REQUEST_BLUETOOTH = 9021 }
}

package com.andrerinas.openheadunit.main

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.WirelessScopePolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** The car's standby screen. Wireless pairing is the primary path; USB remains a fallback. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var primaryBluetoothDevice: BluetoothDevice? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    refreshBluetoothDevices()
                }
            }
        }
    }

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshBluetoothDevices()
            selectPhone()
        } else {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<View>(R.id.wifi_button).setOnClickListener {
            if (app.commManager.isConnected) openProjection()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else selectPhone()
        }
        view.findViewById<View>(R.id.usb_button).setOnClickListener {
            if (app.commManager.isConnected) openProjection()
            else findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
        }
        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        // Going back to the car does not tear down a connection or disable reconnect.
        view.findViewById<View>(R.id.exit_button).setOnClickListener { requireActivity().moveTaskToBack(true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.commManager.connectionState.collect { state ->
                    val status = when (state) {
                        is CommManager.ConnectionState.Disconnected -> R.string.da_ready
                        is CommManager.ConnectionState.TransportStarted -> R.string.da_connected
                        else -> R.string.da_connecting
                    }
                    view.findViewById<TextView>(R.id.connection_status).setText(status)
                    view.findViewById<MaterialButton>(R.id.wifi_button).setText(
                        if (app.commManager.isConnected) R.string.da_open else R.string.da_connect)
                    refreshBluetoothDevices()
                }
            }
        }
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            requireContext().registerReceiver(bluetoothReceiver, filter)
        } catch (_: Exception) {}
        refreshBluetoothDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshBluetoothDevices()
    }

    override fun onStop() {
        try {
            requireContext().unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        super.onStop()
    }

    override fun onPause() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val context = context ?: return
        if (app.commManager.isConnected) {
            openProjection()
            return
        }

        app.settings.wifiConnectionMode = 3
        if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
            app.settings.connectionModes =
                app.settings.connectionModes + Settings.ConnectionMode.WIFI
        }
        app.settings.autoStartBluetoothDeviceMac = device.address
        try {
            val devName = device.name
            if (!devName.isNullOrEmpty()) {
                app.settings.autoStartBluetoothDeviceName = devName
            }
        } catch (_: SecurityException) {}

        (activity as? MainActivity)?.beginAutoConnect(
            "manual native wireless", MainActivity.ConnectionUiMode.PILL)
        ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, device.address)
        })

        view?.findViewById<TextView>(R.id.connection_status)?.setText(R.string.da_connecting)
        view?.findViewById<TextView>(R.id.quick_device_status)?.setText(R.string.da_connecting)
    }

    private fun refreshBluetoothDevices() {
        val root = view ?: return
        val card = root.findViewById<View>(R.id.quick_device_card) ?: return
        val nameView = root.findViewById<TextView>(R.id.quick_device_name) ?: return
        val statusView = root.findViewById<TextView>(R.id.quick_device_status) ?: return
        val labelView = root.findViewById<TextView>(R.id.quick_device_label) ?: return
        val connectBtn = root.findViewById<MaterialButton>(R.id.quick_connect_btn) ?: return
        val otherContainer = root.findViewById<LinearLayout>(R.id.other_devices_container)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            primaryBluetoothDevice = null
            labelView.setText(R.string.da_permissions)
            nameView.setText(R.string.da_device)
            statusView.setText(R.string.da_permissions_hint)
            connectBtn.setText(R.string.da_connect)
            val reqPerm = View.OnClickListener { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            card.setOnClickListener(reqPerm)
            connectBtn.setOnClickListener(reqPerm)
            otherContainer?.visibility = View.GONE
            return
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) {
            primaryBluetoothDevice = null
            labelView.setText(R.string.da_quick_bt_title)
            nameView.setText(R.string.da_bluetooth_off)
            statusView.setText(R.string.da_system_settings)
            connectBtn.setText(R.string.da_quick_btn_pair)
            val openBt = View.OnClickListener { openBluetoothSettings() }
            card.setOnClickListener(openBt)
            connectBtn.setOnClickListener(openBt)
            otherContainer?.visibility = View.GONE
            return
        }

        val bonded = try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (bonded.isEmpty()) {
            primaryBluetoothDevice = null
            labelView.setText(R.string.da_quick_bt_title)
            nameView.setText(R.string.da_quick_no_device)
            statusView.setText(R.string.da_quick_pair_hint)
            connectBtn.setText(R.string.da_quick_btn_pair)
            val openBt = View.OnClickListener { openBluetoothSettings() }
            card.setOnClickListener(openBt)
            connectBtn.setOnClickListener(openBt)
            otherContainer?.visibility = View.GONE
            return
        }

        val lastMac = app.settings.autoStartBluetoothDeviceMac
        val target = bonded.firstOrNull { it.address.equals(lastMac, ignoreCase = true) } ?: bonded.first()
        primaryBluetoothDevice = target

        val deviceName = try { target.name ?: target.address } catch (_: SecurityException) { target.address }
        nameView.text = deviceName
        labelView.setText(R.string.da_quick_bt_title)

        if (app.commManager.isConnected) {
            statusView.setText(R.string.da_connected)
            connectBtn.setText(R.string.da_open)
            val clickOpen = View.OnClickListener { openProjection() }
            card.setOnClickListener(clickOpen)
            connectBtn.setOnClickListener(clickOpen)
        } else {
            statusView.setText(R.string.da_quick_bt_hint)
            connectBtn.setText(R.string.da_quick_btn_connect)
            val clickConnect = View.OnClickListener { connectToDevice(target) }
            card.setOnClickListener(clickConnect)
            connectBtn.setOnClickListener(clickConnect)
        }

        try {
            adapter.getProfileProxy(requireContext(), object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    try {
                        val connected = proxy?.connectedDevices.orEmpty()
                        if (connected.any { it.address.equals(target.address, ignoreCase = true) }) {
                            activity?.runOnUiThread {
                                if (isAdded && !app.commManager.isConnected) {
                                    statusView.text = "Bluetooth ile bağlı • Bağlanmak için dokunun"
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    finally {
                        try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } catch (_: Exception) {}

        val otherDevices = bonded.filter { it.address != target.address }
        if (otherDevices.isNotEmpty() && otherContainer != null) {
            otherContainer.removeAllViews()
            otherContainer.visibility = View.VISIBLE
            for (other in otherDevices.take(2)) {
                val btn = MaterialButton(requireContext()).apply {
                    val otherName = try { other.name ?: other.address } catch (_: SecurityException) { other.address }
                    text = otherName
                    textSize = 13f
                    isAllCaps = false
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.da_text))
                    backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.da_surface)
                    strokeColor = ContextCompat.getColorStateList(requireContext(), R.color.da_border)
                    strokeWidth = 2
                    cornerRadius = 24
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 16
                    }
                    setOnClickListener {
                        connectToDevice(other)
                    }
                }
                otherContainer.addView(btn)
            }
        } else {
            otherContainer?.visibility = View.GONE
        }
    }

    private fun selectPhone() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        try {
            if (adapter == null || !adapter.isEnabled) {
                showBluetoothHelp(R.string.da_bluetooth_off)
                return
            }
            val devices = adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                showBluetoothHelp(R.string.da_no_paired)
                return
            }
            activeDialog?.dismiss()
            activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.da_connect)
                .setItems(devices.map { it.name ?: getString(R.string.da_device) }.toTypedArray()) { _, index ->
                    val device = devices[index]
                    connectToDevice(device)
                }
                .setNeutralButton(R.string.da_pair) { _, _ -> openBluetoothSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: SecurityException) {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
    }

    private fun showBluetoothHelp(message: Int) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.da_pair).setMessage(message)
            .setPositiveButton(R.string.da_system_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openBluetoothSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    companion object {
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}

package com.aruncs.musicsync.ui.dialog

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.model.SyncDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

object DeviceDialogsHelper {

    fun showAddDeviceDialog(
        activity: Activity,
        scope: CoroutineScope,
        prefs: AppPreferences,
        apiClient: DesktopApiClient,
        onDeviceAdded: (SyncDevice) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_device, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_dialog_device_name)
        val etIp = dialogView.findViewById<EditText>(R.id.et_dialog_device_ip)
        val etPort = dialogView.findViewById<EditText>(R.id.et_dialog_device_port)
        val rbAndroid = dialogView.findViewById<RadioButton>(R.id.rb_role_android)
        val tvTestStatus = dialogView.findViewById<TextView>(R.id.tv_dialog_test_status)
        val btnTest = dialogView.findViewById<Button>(R.id.btn_dialog_test_device)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_dialog_save_device)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btn_dialog_add_close)

        val curIp = prefs.desktopIp
        if (curIp.isNotBlank() && curIp != "192.168.1.100") {
            etIp.setText(curIp)
            etPort.setText(prefs.desktopPort.toString())
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose?.setOnClickListener { dialog.dismiss() }

        btnTest?.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 5000
            if (ip.isBlank()) {
                tvTestStatus.visibility = View.VISIBLE
                tvTestStatus.text = "Please enter an IP address"
                tvTestStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_offline))
                return@setOnClickListener
            }
            tvTestStatus.visibility = View.VISIBLE
            tvTestStatus.text = "Pinging $ip:$port..."
            tvTestStatus.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))

            scope.launch(Dispatchers.IO) {
                try {
                    val res = apiClient.ping(ip, port)
                    val host = res.optString("hostname", ip)
                    withContext(Dispatchers.Main) {
                        tvTestStatus.text = "Connected! Host: $host"
                        tvTestStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
                        if (etName.text.isBlank()) {
                            etName.setText(host)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvTestStatus.text = "Ping failed: ${e.message}"
                        tvTestStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_offline))
                    }
                }
            }
        }

        btnSave?.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 5000
            var name = etName.text.toString().trim()
            val role = if (rbAndroid.isChecked) "android" else "desktop"

            if (ip.isBlank()) {
                Toast.makeText(activity, "IP address is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.isBlank()) {
                name = if (role == "android") "Android ($ip)" else "Desktop ($ip)"
            }

            val newDev = SyncDevice(
                id = "$ip:$port",
                name = name,
                ip = ip,
                port = port,
                role = role,
                isOnline = false,
                isManual = true
            )
            prefs.addOrUpdateDevice(newDev)
            onDeviceAdded(newDev)
            dialog.dismiss()
            Toast.makeText(activity, "Device '$name' added successfully!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    fun showRenameDeviceDialog(
        activity: Activity,
        device: SyncDevice,
        prefs: AppPreferences,
        onRenamed: (newName: String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_device, null)
        val tvAddress = dialogView.findViewById<TextView>(R.id.tv_dialog_edit_device_address)
        val etName = dialogView.findViewById<EditText>(R.id.et_dialog_edit_name)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_dialog_edit_cancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_dialog_edit_save)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btn_dialog_edit_close)

        tvAddress.text = "${device.address} (${device.role.uppercase(Locale.US)})"
        etName.setText(device.name)
        etName.setSelection(device.name.length)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose?.setOnClickListener { dialog.dismiss() }
        btnCancel?.setOnClickListener { dialog.dismiss() }

        btnSave?.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotBlank()) {
                device.name = newName
                prefs.updateDeviceName(device.id, newName)
                onRenamed(newName)
                Toast.makeText(activity, "Device renamed to '$newName'", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    fun showDeviceSyncOptionsDialog(
        activity: Activity,
        device: SyncDevice,
        onSelectDevice: (SyncDevice) -> Unit,
        onPull: () -> Unit,
        onPush: () -> Unit
    ) {
        onSelectDevice(device)
        AlertDialog.Builder(activity)
            .setTitle("Sync with ${device.name}")
            .setMessage("Target: ${device.address}\n\nChoose synchronization direction:")
            .setPositiveButton("Pull Tracks (from ${device.name})") { _, _ ->
                onPull()
            }
            .setNeutralButton("Push Tracks (to ${device.name})") { _, _ ->
                onPush()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

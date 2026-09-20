package com.aruncs.musicsync.ui.dialog

import android.app.Activity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.model.PlaybackTarget
import com.aruncs.musicsync.model.SyncDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DevicePickerHelper {

    fun show(
        activity: Activity,
        scope: CoroutineScope,
        apiClient: DesktopApiClient,
        currentTarget: PlaybackTarget,
        devicesProvider: () -> List<SyncDevice>,
        onTargetSelected: (PlaybackTarget) -> Unit,
        onAddDeviceRequested: () -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_device_picker, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val cardThisDevice = dialogView.findViewById<LinearLayout>(R.id.card_picker_this_device)
        val badgeLocalActive = dialogView.findViewById<TextView>(R.id.badge_picker_local_active)
        val tvLocalDesc = dialogView.findViewById<TextView>(R.id.tv_picker_local_desc)
        val layoutRemoteList = dialogView.findViewById<LinearLayout>(R.id.layout_picker_remote_devices)
        val tvNoRemote = dialogView.findViewById<TextView>(R.id.tv_picker_no_remote)
        val btnRefresh = dialogView.findViewById<ImageButton>(R.id.btn_picker_refresh)
        val btnAddDevice = dialogView.findViewById<TextView>(R.id.btn_picker_add_device)
        val btnClose = dialogView.findViewById<TextView>(R.id.btn_picker_close)

        // 1. Configure This Device (Local)
        if (currentTarget.isLocal) {
            cardThisDevice.setBackgroundResource(R.drawable.bg_card_active)
            badgeLocalActive.visibility = View.VISIBLE
            tvLocalDesc.text = "Currently controlling and playing local audio"
        } else {
            cardThisDevice.setBackgroundResource(R.drawable.bg_card)
            badgeLocalActive.visibility = View.GONE
            tvLocalDesc.text = "Direct audio output on phone speakers/headphones"
        }

        cardThisDevice.setOnClickListener {
            onTargetSelected(PlaybackTarget.Local)
            dialog.dismiss()
        }

        fun populateRemoteDevices() {
            layoutRemoteList.removeAllViews()
            val devices = devicesProvider()

            if (devices.isEmpty()) {
                tvNoRemote.visibility = View.VISIBLE
                tvNoRemote.text = "No saved or discovered devices found.\nTap '+ Add Device' below to add a computer or phone."
            } else {
                tvNoRemote.visibility = View.GONE
            }

            for (dev in devices) {
                val row = activity.layoutInflater.inflate(R.layout.item_picker_device, layoutRemoteList, false)
                val card = row.findViewById<LinearLayout>(R.id.card_picker_device)
                val ivIcon = row.findViewById<ImageView>(R.id.iv_picker_device_icon)
                val tvName = row.findViewById<TextView>(R.id.tv_picker_device_name)
                val badgeActive = row.findViewById<TextView>(R.id.badge_picker_active)
                val tvDesc = row.findViewById<TextView>(R.id.tv_picker_device_desc)
                val tvNowPlaying = row.findViewById<TextView>(R.id.tv_picker_device_now_playing)
                val badgeStatus = row.findViewById<TextView>(R.id.badge_picker_status)

                ivIcon.setImageResource(if (dev.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer)
                tvName.text = dev.name
                tvDesc.text = "${dev.ip}:${dev.port}"

                val isTarget = !currentTarget.isLocal && currentTarget.device?.let {
                    it.ip.equals(dev.ip, ignoreCase = true) && it.port == dev.port
                } == true

                if (isTarget) {
                    card.setBackgroundResource(R.drawable.bg_card_active)
                    badgeActive.visibility = View.VISIBLE
                } else {
                    card.setBackgroundResource(R.drawable.bg_card)
                    badgeActive.visibility = View.GONE
                }

                badgeStatus.text = "CHECKING..."
                badgeStatus.setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                badgeStatus.setBackgroundResource(R.drawable.bg_badge_yellow)

                card.setOnClickListener {
                    onTargetSelected(PlaybackTarget.Remote(dev))
                    dialog.dismiss()
                }

                // Check live session state on the device
                scope.launch {
                    val sessionState = withContext(Dispatchers.IO) {
                        try {
                            apiClient.fetchSessionState(dev.ip, dev.port)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (!dialog.isShowing) return@launch

                    if (sessionState != null) {
                        if (sessionState.isPlaying) {
                            badgeStatus.text = "PLAYING"
                            badgeStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
                            badgeStatus.setBackgroundResource(R.drawable.bg_badge_green)
                            if (sessionState.currentTitle.isNotBlank()) {
                                tvNowPlaying.visibility = View.VISIBLE
                                tvNowPlaying.text = "♪ ${sessionState.currentTitle}${if (sessionState.currentArtist.isNotBlank()) " • ${sessionState.currentArtist}" else ""}"
                            }
                        } else if (sessionState.currentTitle.isNotBlank()) {
                            badgeStatus.text = "PAUSED"
                            badgeStatus.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))
                            badgeStatus.setBackgroundResource(R.drawable.bg_badge_yellow)
                            tvNowPlaying.visibility = View.VISIBLE
                            tvNowPlaying.text = "⏸ ${sessionState.currentTitle}${if (sessionState.currentArtist.isNotBlank()) " • ${sessionState.currentArtist}" else ""}"
                        } else {
                            badgeStatus.text = "ONLINE"
                            badgeStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
                            badgeStatus.setBackgroundResource(R.drawable.bg_badge_green)
                            tvNowPlaying.visibility = View.GONE
                        }
                    } else {
                        badgeStatus.text = "OFFLINE"
                        badgeStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_offline))
                        badgeStatus.setBackgroundResource(R.drawable.bg_badge_red)
                        tvNowPlaying.visibility = View.GONE
                    }
                }

                layoutRemoteList.addView(row)
            }
        }

        btnRefresh.setOnClickListener { populateRemoteDevices() }
        btnAddDevice.setOnClickListener {
            dialog.dismiss()
            onAddDeviceRequested()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        populateRemoteDevices()
        dialog.show()
    }
}

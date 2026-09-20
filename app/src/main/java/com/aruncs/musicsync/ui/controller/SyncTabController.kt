package com.aruncs.musicsync.ui.controller

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.client.SyncManager
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.model.SyncDevice
import com.aruncs.musicsync.server.DiscoveredPeer
import com.aruncs.musicsync.server.NetworkUtils
import com.aruncs.musicsync.server.PeerDiscoveryManager
import com.aruncs.musicsync.server.SyncForegroundService
import com.aruncs.musicsync.ui.dialog.DeviceDialogsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncTabController(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val prefs: AppPreferences,
    private val apiClient: DesktopApiClient,
    private val syncManager: SyncManager,
    private val getTargetPeerIp: () -> String,
    private val getTargetPeerPort: () -> Int,
    private val onTargetPeerSelected: (ip: String, port: Int, name: String) -> Unit,
    private val onBrowseLibraryRequested: (SyncDevice) -> Unit,
    private val onShowSessionManager: () -> Unit,
    private val onRefreshLocalLibrary: () -> Unit,
    private val onLog: (String) -> Unit,
    private val onIpBadgeRefreshNeeded: () -> Unit
) {
    // Views in fragment_sync
    private var badgeServerStatus: TextView? = null
    private var tvServerAddress: TextView? = null
    private var btnToggleServer: Button? = null
    private var etDesktopIp: EditText? = null
    private var etDesktopPort: EditText? = null
    private var btnAddDevice: Button? = null
    private var btnAutoDiscover: Button? = null
    private var btnPingDesktop: Button? = null
    private var tvDesktopStatus: TextView? = null
    private var layoutSyncDevicesList: LinearLayout? = null
    private var tvDevicesEmptyState: TextView? = null
    private var badgeDevicesCount: TextView? = null
    private var pbDevicesScanning: ProgressBar? = null
    private var btnSyncPull: Button? = null
    private var btnSyncPush: Button? = null
    private var btnSessionManager: Button? = null
    private var layoutSyncProgress: LinearLayout? = null
    private var tvSyncStatusLabel: TextView? = null
    private var tvSyncPercent: TextView? = null
    private var pbSync: ProgressBar? = null
    private var tvSyncDetail: TextView? = null

    private val discoveredSyncDevices = mutableListOf<DiscoveredPeer>()

    fun init(syncView: View) {
        badgeServerStatus = syncView.findViewById(R.id.badge_server_status)
        tvServerAddress = syncView.findViewById(R.id.tv_server_address)
        btnToggleServer = syncView.findViewById(R.id.btn_toggle_server)
        etDesktopIp = syncView.findViewById(R.id.et_desktop_ip)
        etDesktopPort = syncView.findViewById(R.id.et_desktop_port)
        btnAddDevice = syncView.findViewById(R.id.btn_add_device)
        btnAutoDiscover = syncView.findViewById(R.id.btn_auto_discover)
        btnPingDesktop = syncView.findViewById(R.id.btn_ping_desktop)
        tvDesktopStatus = syncView.findViewById(R.id.tv_desktop_status)
        layoutSyncDevicesList = syncView.findViewById(R.id.layout_sync_devices_list)
        tvDevicesEmptyState = syncView.findViewById(R.id.tv_devices_empty_state)
        badgeDevicesCount = syncView.findViewById(R.id.badge_devices_count)
        pbDevicesScanning = syncView.findViewById(R.id.pb_devices_scanning)
        btnSyncPull = syncView.findViewById(R.id.btn_sync_pull)
        btnSyncPush = syncView.findViewById(R.id.btn_sync_push)
        btnSessionManager = syncView.findViewById(R.id.btn_sessions_manager)
        layoutSyncProgress = syncView.findViewById(R.id.layout_sync_progress)
        tvSyncStatusLabel = syncView.findViewById(R.id.tv_sync_status_label)
        tvSyncPercent = syncView.findViewById(R.id.tv_sync_percent)
        pbSync = syncView.findViewById(R.id.pb_sync)
        tvSyncDetail = syncView.findViewById(R.id.tv_sync_detail)

        etDesktopIp?.setText(prefs.desktopIp)
        etDesktopPort?.setText(prefs.desktopPort.toString())
        renderSyncDevicesList()

        btnToggleServer?.setOnClickListener {
            if (SyncForegroundService.isRunning) {
                stopServerService()
            } else {
                startServerService()
            }
        }

        btnAddDevice?.setOnClickListener {
            showAddDeviceDialog()
        }

        btnAutoDiscover?.setOnClickListener {
            scanForNearbyDevices(showToast = true)
        }

        btnPingDesktop?.setOnClickListener {
            pingDesktopServer()
        }

        btnSyncPull?.setOnClickListener {
            startSyncPull()
        }

        btnSyncPush?.setOnClickListener {
            startSyncPush()
        }

        btnSessionManager?.setOnClickListener {
            onShowSessionManager()
        }

        etDesktopIp?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.desktopIp = s?.toString()?.trim() ?: "192.168.1.100"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etDesktopPort?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.desktopPort = s?.toString()?.toIntOrNull() ?: 5000
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateServerStatusUI(SyncForegroundService.isRunning)
    }

    fun updateServerStatusUI(running: Boolean) {
        if (running) {
            badgeServerStatus?.text = "ONLINE"
            badgeServerStatus?.setBackgroundResource(R.drawable.bg_badge_green)
            badgeServerStatus?.setTextColor(ContextCompat.getColor(activity, R.color.status_online))

            val port = prefs.serverPort
            val ip = NetworkUtils.getWifiIpAddress(activity) ?: "0.0.0.0"
            tvServerAddress?.text = "Running at http://$ip:$port"
            tvServerAddress?.visibility = View.VISIBLE

            btnToggleServer?.text = activity.getString(R.string.btn_stop_server)
            btnToggleServer?.setBackgroundResource(R.drawable.bg_button_danger)
            btnToggleServer?.setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        } else {
            badgeServerStatus?.text = "OFFLINE"
            badgeServerStatus?.setBackgroundResource(R.drawable.bg_badge_red)
            badgeServerStatus?.setTextColor(ContextCompat.getColor(activity, R.color.status_offline))

            tvServerAddress?.text = activity.getString(R.string.status_server_stopped)
            tvServerAddress?.visibility = View.VISIBLE

            btnToggleServer?.text = activity.getString(R.string.btn_start_server)
            btnToggleServer?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnToggleServer?.setTextColor(ContextCompat.getColor(activity, R.color.black))
        }
    }

    private fun startServerService() {
        val serviceIntent = Intent(activity, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(serviceIntent)
        } else {
            activity.startService(serviceIntent)
        }
        onLog("[SERVER] Starting Over-IP sync server...")
    }

    private fun stopServerService() {
        val serviceIntent = Intent(activity, SyncForegroundService::class.java)
        activity.stopService(serviceIntent)
        updateServerStatusUI(false)
        onIpBadgeRefreshNeeded()
        onLog("[SERVER] Stopped Over-IP server.")
    }

    fun showAddDeviceDialog() {
        DeviceDialogsHelper.showAddDeviceDialog(
            activity = activity,
            scope = scope,
            prefs = prefs,
            apiClient = apiClient,
            onDeviceAdded = { newDev ->
                selectSyncDevice(newDev)
                renderSyncDevicesList()
            }
        )
    }

    fun getKnownDevices(): List<SyncDevice> {
        val saved = prefs.getSavedDevices().toMutableList()
        for (peer in discoveredSyncDevices) {
            if (saved.none { it.ip.equals(peer.ip, ignoreCase = true) && it.port == peer.port }) {
                saved.add(
                    SyncDevice(
                        id = "${peer.ip}:${peer.port}",
                        name = peer.displayName,
                        ip = peer.ip,
                        port = peer.port,
                        role = peer.role,
                        isOnline = true,
                        isManual = false
                    )
                )
            }
        }
        return saved
    }

    fun renderSyncDevicesList() {
        val container = layoutSyncDevicesList ?: return
        container.removeAllViews()

        val savedDevices = prefs.getSavedDevices().toMutableList()

        for (peer in discoveredSyncDevices) {
            val existing = savedDevices.firstOrNull { it.ip.equals(peer.ip, ignoreCase = true) && it.port == peer.port }
            if (existing != null) {
                existing.isOnline = true
                existing.lastSeen = System.currentTimeMillis()
            } else {
                val newDev = SyncDevice(
                    id = "${peer.ip}:${peer.port}",
                    name = peer.hostname,
                    ip = peer.ip,
                    port = peer.port,
                    role = if (peer.isAndroid) "android" else "desktop",
                    isOnline = true
                )
                savedDevices.add(newDev)
            }
        }

        if (savedDevices.isEmpty()) {
            badgeDevicesCount?.visibility = View.GONE
            tvDevicesEmptyState?.visibility = View.VISIBLE
            return
        }

        tvDevicesEmptyState?.visibility = View.GONE
        badgeDevicesCount?.text = "${savedDevices.size} Device(s)"
        badgeDevicesCount?.visibility = View.VISIBLE

        val currentTargetIp = getTargetPeerIp()
        val currentTargetPort = getTargetPeerPort()

        for (device in savedDevices) {
            val cardView = LayoutInflater.from(activity).inflate(R.layout.item_device_card, container, false)
            val cardRoot = cardView.findViewById<LinearLayout>(R.id.card_device_root)
            val ivIcon = cardView.findViewById<ImageView>(R.id.iv_card_device_icon)
            val tvName = cardView.findViewById<TextView>(R.id.tv_card_device_name)
            val tvAddress = cardView.findViewById<TextView>(R.id.tv_card_device_address)
            val tvStatus = cardView.findViewById<TextView>(R.id.tv_card_device_status)
            val tvRole = cardView.findViewById<TextView>(R.id.tv_card_device_role)
            val tvActiveIndicator = cardView.findViewById<TextView>(R.id.tv_card_active_indicator)
            val btnEdit = cardView.findViewById<ImageView>(R.id.btn_card_edit_name)
            val btnDelete = cardView.findViewById<ImageView>(R.id.btn_card_delete)
            val btnBrowse = cardView.findViewById<Button>(R.id.btn_card_browse_library)
            val btnSync = cardView.findViewById<Button>(R.id.btn_card_sync)

            ivIcon?.setImageResource(if (device.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer)
            tvName.text = device.name
            tvAddress.text = "${device.ip}:${device.port}"
            tvRole.text = if (device.isAndroid) "ANDROID COMPANION" else "DESKTOP SERVER"

            val isSelected = device.ip.equals(currentTargetIp, ignoreCase = true) && device.port == currentTargetPort

            if (isSelected) {
                cardRoot.setBackgroundResource(R.drawable.bg_card_active)
                tvActiveIndicator.visibility = View.VISIBLE
                tvName.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))
            } else {
                cardRoot.setBackgroundResource(R.drawable.bg_card)
                tvActiveIndicator.visibility = View.GONE
                tvName.setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            }

            if (device.isOnline) {
                tvStatus.text = "ONLINE"
                tvStatus.setBackgroundResource(R.drawable.bg_badge_green)
                tvStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
            } else {
                tvStatus.text = "OFFLINE"
                tvStatus.setBackgroundResource(R.drawable.bg_badge_red)
                tvStatus.setTextColor(ContextCompat.getColor(activity, R.color.status_offline))
            }

            cardRoot.setOnClickListener {
                selectSyncDevice(device)
            }

            btnEdit.setOnClickListener {
                DeviceDialogsHelper.showRenameDeviceDialog(
                    activity = activity,
                    device = device,
                    prefs = prefs,
                    onRenamed = { newName ->
                        if (device.ip.equals(getTargetPeerIp(), true) && device.port == getTargetPeerPort()) {
                            onTargetPeerSelected(device.ip, device.port, newName)
                        }
                        renderSyncDevicesList()
                    }
                )
            }

            btnDelete.setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Remove Device")
                    .setMessage("Remove '${device.name}' (${device.address}) from your saved devices?")
                    .setPositiveButton("Remove") { _, _ ->
                        prefs.removeDevice(device.id)
                        discoveredSyncDevices.removeAll { it.ip.equals(device.ip, true) && it.port == device.port }
                        renderSyncDevicesList()
                        Toast.makeText(activity, "Removed ${device.name}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            btnBrowse.setOnClickListener {
                selectSyncDevice(device)
                onBrowseLibraryRequested(device)
            }

            btnSync.setOnClickListener {
                DeviceDialogsHelper.showDeviceSyncOptionsDialog(
                    activity = activity,
                    device = device,
                    onSelectDevice = { selectSyncDevice(it) },
                    onPull = { startSyncPull() },
                    onPush = { startSyncPush() }
                )
            }

            container.addView(cardView)
        }
    }

    fun selectSyncDevice(device: SyncDevice) {
        prefs.desktopIp = device.ip
        prefs.desktopPort = device.port
        etDesktopIp?.setText(device.ip)
        etDesktopPort?.setText(device.port.toString())
        onTargetPeerSelected(device.ip, device.port, device.name)
        renderSyncDevicesList()
        Toast.makeText(activity, "Target set: ${device.name} (${device.address})", Toast.LENGTH_SHORT).show()
    }

    fun scanForNearbyDevices(showToast: Boolean = false) {
        pbDevicesScanning?.visibility = View.VISIBLE
        btnAutoDiscover?.isEnabled = false
        tvDesktopStatus?.text = "Scanning Wi-Fi / Hotspot for devices..."
        tvDesktopStatus?.setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
        onLog("[DISCOVERY] Scanning Wi-Fi / Hotspot for nearby devices...")

        val targetIp = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val targetPort = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        scope.launch {
            val savedList = prefs.getSavedDevices()
            for (saved in savedList) {
                launch(Dispatchers.IO) {
                    try {
                        val pingRes = apiClient.ping(saved.ip, saved.port)
                        saved.isOnline = true
                        saved.lastSeen = System.currentTimeMillis()
                        val h = pingRes.optString("hostname", "")
                        if (h.isNotBlank() && (saved.name.startsWith("Desktop (") || saved.name.startsWith("Android ("))) {
                            saved.name = h
                        }
                    } catch (ignored: Exception) {
                        saved.isOnline = false
                    }
                    withContext(Dispatchers.Main) {
                        renderSyncDevicesList()
                    }
                }
            }

            val peers = PeerDiscoveryManager.discoverAllPeers(
                context = activity,
                timeoutMs = 2500,
                targetIp = targetIp,
                targetPort = targetPort,
                onPeerFound = { peer ->
                    activity.runOnUiThread {
                        val exists = discoveredSyncDevices.any { it.ip == peer.ip && it.port == peer.port }
                        if (!exists) {
                            discoveredSyncDevices.add(peer)
                            val dev = SyncDevice(
                                id = "${peer.ip}:${peer.port}",
                                name = peer.hostname,
                                ip = peer.ip,
                                port = peer.port,
                                role = if (peer.isAndroid) "android" else "desktop",
                                isOnline = true
                            )
                            prefs.addOrUpdateDevice(dev)
                            renderSyncDevicesList()
                        }
                    }
                },
                onLog = { msg ->
                    activity.runOnUiThread { onLog(msg) }
                }
            )

            activity.runOnUiThread {
                pbDevicesScanning?.visibility = View.GONE
                btnAutoDiscover?.isEnabled = true

                for (p in peers) {
                    if (discoveredSyncDevices.none { it.ip == p.ip && it.port == p.port }) {
                        discoveredSyncDevices.add(p)
                        val dev = SyncDevice(
                            id = "${p.ip}:${p.port}",
                            name = p.hostname,
                            ip = p.ip,
                            port = p.port,
                            role = if (p.isAndroid) "android" else "desktop",
                            isOnline = true
                        )
                        prefs.addOrUpdateDevice(dev)
                    }
                }
                renderSyncDevicesList()

                val totalCount = prefs.getSavedDevices().size
                tvDesktopStatus?.text = "Discovered & verified devices on network."
                tvDesktopStatus?.setTextColor(ContextCompat.getColor(activity, R.color.status_online))

                if (showToast) {
                    Toast.makeText(activity, "Scan complete: $totalCount device(s) ready", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pingDesktopServer() {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        if (ip.isEmpty()) {
            Toast.makeText(activity, "Enter Desktop IP address", Toast.LENGTH_SHORT).show()
            return
        }

        btnPingDesktop?.isEnabled = false
        tvDesktopStatus?.text = "Pinging $ip:$port..."
        onLog("[CLIENT] Pinging Desktop at http://$ip:$port...")

        scope.launch {
            try {
                val res = apiClient.ping(ip, port)
                val songs = res.optInt("song_count", 0)
                val hostname = res.optString("hostname", ip)

                activity.runOnUiThread {
                    btnPingDesktop?.isEnabled = true
                    tvDesktopStatus?.text = "Connected to $hostname ($songs songs indexed)"
                    tvDesktopStatus?.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
                    onLog("[OK] Ping successful! Host: $hostname, songs: $songs")
                    Toast.makeText(activity, "Connected! $songs tracks on Desktop", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    btnPingDesktop?.isEnabled = true
                    tvDesktopStatus?.text = "Cannot reach $ip:$port (${e.message})"
                    tvDesktopStatus?.setTextColor(ContextCompat.getColor(activity, R.color.status_offline))
                    onLog("[ERROR] Ping failed: ${e.message}")
                    Toast.makeText(activity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startSyncPull() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val targetName = prefs.getSavedDevices().firstOrNull { it.ip.equals(ip, true) && it.port == port }?.name ?: "$ip:$port"

        setSyncInProgress(true, "Comparing tracks with $targetName...")
        onLog("[SYNC-PULL] Starting Pull Sync from $targetName ($ip:$port)...")

        scope.launch {
            try {
                val desktopSongs = apiClient.fetchSongs(ip, port)
                val localSongs = MediaStoreHelper.getAllDeviceSongs(activity)
                val diff = syncManager.calculateDiff(localSongs, desktopSongs)
                val songsToPull = diff.songsToPull

                onLog("[SYNC-PULL] Diff: ${songsToPull.size} to pull, ${diff.songsToPush.size} local only")

                if (songsToPull.isEmpty()) {
                    activity.runOnUiThread {
                        setSyncInProgress(false)
                        tvSyncStatusLabel?.text = "Library is already in sync with $targetName!"
                        Toast.makeText(activity, "Device is already up to date!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                activity.runOnUiThread {
                    tvSyncStatusLabel?.text = "Pulling ${songsToPull.size} track(s)..."
                }

                syncManager.pullSongs(
                    desktopIp = ip,
                    desktopPort = port,
                    songsToPull = songsToPull,
                    onProgress = { current, total, song, percent, _ ->
                        activity.runOnUiThread {
                            pbSync?.progress = percent
                            tvSyncPercent?.text = "$percent%"
                            tvSyncDetail?.text = "($current/$total) ${song.filename}"
                        }
                    },
                    onLog = { msg ->
                        activity.runOnUiThread { onLog(msg) }
                    }
                )

                activity.runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Pull Complete! Downloaded ${songsToPull.size} tracks."
                    Toast.makeText(activity, "Downloaded ${songsToPull.size} songs!", Toast.LENGTH_LONG).show()
                    onRefreshLocalLibrary()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Sync Failed: ${e.message}"
                    onLog("[ERROR] Sync Pull failed: ${e.message}")
                    Toast.makeText(activity, "Sync error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun startSyncPush() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val targetName = prefs.getSavedDevices().firstOrNull { it.ip.equals(ip, true) && it.port == port }?.name ?: "$ip:$port"

        setSyncInProgress(true, "Scanning local library for new songs...")
        onLog("[SYNC-PUSH] Starting Push Sync to $targetName ($ip:$port)...")

        scope.launch {
            try {
                val desktopSongs = apiClient.fetchSongs(ip, port)
                val localSongs = MediaStoreHelper.getAllDeviceSongs(activity)
                val diff = syncManager.calculateDiff(localSongs, desktopSongs)
                val songsToPush = diff.songsToPush

                onLog("[SYNC-PUSH] Diff: ${songsToPush.size} to push, ${diff.songsToPull.size} remote only")

                if (songsToPush.isEmpty()) {
                    activity.runOnUiThread {
                        setSyncInProgress(false)
                        tvSyncStatusLabel?.text = "No new songs to push to $targetName."
                        Toast.makeText(activity, "All local songs already on $targetName!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                activity.runOnUiThread {
                    tvSyncStatusLabel?.text = "Pushing ${songsToPush.size} track(s)..."
                }

                syncManager.pushSongs(
                    desktopIp = ip,
                    desktopPort = port,
                    songsToPush = songsToPush,
                    onProgress = { current, total, song, percent, _ ->
                        activity.runOnUiThread {
                            pbSync?.progress = percent
                            tvSyncPercent?.text = "$percent%"
                            tvSyncDetail?.text = "($current/$total) ${song.filename}"
                        }
                    },
                    onLog = { msg ->
                        activity.runOnUiThread { onLog(msg) }
                    }
                )

                activity.runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Push Complete! Uploaded ${songsToPush.size} tracks."
                    Toast.makeText(activity, "Uploaded ${songsToPush.size} songs!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Push Failed: ${e.message}"
                    onLog("[ERROR] Sync Push failed: ${e.message}")
                    Toast.makeText(activity, "Push error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setSyncInProgress(inProgress: Boolean, statusLabel: String = "") {
        if (inProgress) {
            tvSyncStatusLabel?.text = statusLabel
            tvSyncPercent?.text = "0%"
            pbSync?.progress = 0
            tvSyncDetail?.text = "Preparing..."
        }
        btnSyncPull?.isEnabled = !inProgress
        btnSyncPush?.isEnabled = !inProgress
        btnPingDesktop?.isEnabled = !inProgress
        layoutSyncProgress?.visibility = if (inProgress) View.VISIBLE else layoutSyncProgress?.visibility ?: View.GONE
    }
}

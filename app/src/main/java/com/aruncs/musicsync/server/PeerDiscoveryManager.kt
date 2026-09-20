package com.aruncs.musicsync.server

import android.content.Context
import android.net.wifi.WifiManager
import com.aruncs.musicsync.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredPeer(
    val ip: String,
    val port: Int,
    val hostname: String,
    val role: String // "android" or "desktop"
) {
    val isAndroid: Boolean get() = role.equals("android", ignoreCase = true)
    val isDesktop: Boolean get() = role.equals("desktop", ignoreCase = true)
    val displayName: String
        get() = if (isAndroid) "$hostname (Phone)" else "$hostname (Desktop)"
}

object PeerDiscoveryManager {

    private const val DISCOVERY_PORT = 5005
    private const val MAGIC_HEADER = "AndroidMusicSync"

    private var multicastLock: WifiManager.MulticastLock? = null
    private var isListening = false
    private var listenerThread: Thread? = null

    /**
     * Collect all potential broadcast destinations:
     * 1. 255.255.255.255 (standard fallback)
     * 2. All interface subnet broadcast addresses (e.g. 10.231.70.255)
     * 3. Saved Desktop IP from AppPreferences (for direct unicast probe)
     */
    fun getBroadcastTargets(context: Context): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (ignored: Exception) {}

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.interfaceAddresses) {
                    val bcast = addr.broadcast
                    if (bcast != null && !targets.contains(bcast)) {
                        targets.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val prefs = AppPreferences(context)
            val savedIp = prefs.desktopIp.trim()
            if (savedIp.isNotBlank() && savedIp != "0.0.0.0" && savedIp != "127.0.0.1") {
                val target = InetAddress.getByName(savedIp)
                if (!targets.contains(target)) {
                    targets.add(target)
                }
            }
        } catch (ignored: Exception) {}

        return targets
    }

    fun getAllLocalIps(): Set<String> {
        val ips = mutableSetOf("127.0.0.1", "localhost")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                for (addr in intf.inetAddresses) {
                    if (addr is java.net.Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return ips
    }

    /**
     * Broadcast a discovery probe on Wi-Fi / Hotspot to discover both Android and Desktop peers.
     */
    suspend fun discoverAllPeers(
        context: Context,
        timeoutMs: Long = 3000,
        targetIp: String? = null,
        targetPort: Int = 5000,
        onPeerFound: ((DiscoveredPeer) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null
    ): List<DiscoveredPeer> = withContext(Dispatchers.IO) {
        acquireMulticastLock(context)
        val peerList = mutableListOf<DiscoveredPeer>()
        val seenAddresses = mutableSetOf<String>()
        val localIps = getAllLocalIps()
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 700
            }

            val prefs = AppPreferences(context)
            val model = NetworkUtils.getDeviceModel()

            val probe = JSONObject().apply {
                put("magic", MAGIC_HEADER)
                put("cmd", "DISCOVER")
                put("role", "android")
                put("hostname", model)
                put("port", prefs.serverPort)
            }.toString().toByteArray(Charsets.UTF_8)

            val targets = getBroadcastTargets(context).toMutableList()
            targetIp?.trim()?.takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let {
                try {
                    val explicitAddr = InetAddress.getByName(it)
                    if (!targets.contains(explicitAddr)) targets.add(explicitAddr)
                } catch (ignored: Exception) {}
            }

            onLog?.invoke("[DISCOVERY] Broadcasting UDP discovery probe to ${targets.size} destination(s) on port $DISCOVERY_PORT...")
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, target, DISCOVERY_PORT))
                } catch (ignored: Exception) {}
            }

            val startTime = System.currentTimeMillis()
            val buf = ByteArray(2048)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val recvPacket = DatagramPacket(buf, buf.size)
                    socket.receive(recvPacket)

                    val senderIp = recvPacket.address.hostAddress ?: continue
                    if (localIps.contains(senderIp)) continue

                    val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                    val json = JSONObject(text)

                    if (json.optString("magic") == MAGIC_HEADER) {
                        val role = json.optString("role", "peer")
                        val port = json.optInt("port", 5000)
                        val defaultHost = if (role == "desktop") "Desktop" else "Android Device"
                        val hostname = json.optString("hostname", defaultHost)

                        val key = "$senderIp:$port"
                        if (!seenAddresses.contains(key)) {
                            seenAddresses.add(key)
                            val peer = DiscoveredPeer(senderIp, port, hostname, role)
                            peerList.add(peer)
                            onLog?.invoke("[DISCOVERY] Discovered $role '$hostname' at $senderIp:$port")
                            onPeerFound?.invoke(peer)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - startTime < timeoutMs / 2) {
                        for (target in targets) {
                            try {
                                socket.send(DatagramPacket(probe, probe.size, target, DISCOVERY_PORT))
                            } catch (ignored: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }

            // Direct fallback: check target or saved Desktop IP via HTTP if not already discovered via UDP
            val candidateIp = targetIp?.trim()?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: prefs.desktopIp.trim().takeIf { it.isNotBlank() && it != "0.0.0.0" }
            val candidatePort = if (targetIp?.isNotBlank() == true) targetPort else prefs.desktopPort

            if (candidateIp != null && seenAddresses.none { it.startsWith("$candidateIp:") }) {
                try {
                    val client = com.aruncs.musicsync.client.DesktopApiClient()
                    val res = client.ping(candidateIp, candidatePort)
                    val hostname = res.optString("hostname", "Desktop")
                    val key = "$candidateIp:$candidatePort"
                    if (!seenAddresses.contains(key)) {
                        seenAddresses.add(key)
                        val peer = DiscoveredPeer(candidateIp, candidatePort, hostname, "desktop")
                        peerList.add(peer)
                        onLog?.invoke("[DISCOVERY] Discovered desktop '$hostname' at $candidateIp:$candidatePort (direct HTTP)")
                        onPeerFound?.invoke(peer)
                    }
                } catch (e: Exception) {
                    onLog?.invoke("[DISCOVERY] Direct HTTP check to $candidateIp failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("[DISCOVERY] Discovery error: ${e.message}")
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
            releaseMulticastLock()
        }

        peerList
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (multicastLock == null) {
                multicastLock = wm?.createMulticastLock("MusicSyncMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Broadcast a discovery probe on Wi-Fi to find the Desktop server automatically.
     */
    suspend fun discoverDesktop(
        context: Context,
        timeoutMs: Long = 2500,
        onFound: (ip: String, port: Int, hostname: String) -> Unit,
        onLog: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        acquireMulticastLock(context)
        var found = false
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 800
            }

            val prefs = AppPreferences(context)
            val model = NetworkUtils.getDeviceModel()

            val probe = JSONObject().apply {
                put("magic", MAGIC_HEADER)
                put("cmd", "DISCOVER")
                put("role", "android")
                put("hostname", model)
                put("port", prefs.serverPort)
            }.toString().toByteArray(Charsets.UTF_8)

            val targets = getBroadcastTargets(context)
            onLog?.invoke("[DISCOVERY] Broadcasting UDP probe to ${targets.size} destination(s) on port $DISCOVERY_PORT...")
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, target, DISCOVERY_PORT))
                } catch (ignored: Exception) {}
            }

            val startTime = System.currentTimeMillis()
            val buf = ByteArray(2048)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val recvPacket = DatagramPacket(buf, buf.size)
                    socket.receive(recvPacket)

                    val senderIp = recvPacket.address.hostAddress ?: continue
                    val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                    val json = JSONObject(text)

                    if (json.optString("magic") == MAGIC_HEADER && json.optString("role") == "desktop") {
                        val desktopPort = json.optInt("port", 5000)
                        val hostname = json.optString("hostname", "Desktop")

                        onLog?.invoke("[DISCOVERY] Discovered Desktop '$hostname' at $senderIp:$desktopPort")
                        onFound(senderIp, desktopPort, hostname)
                        found = true
                        break
                    }
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - startTime < timeoutMs / 2) {
                        for (target in targets) {
                            try {
                                socket.send(DatagramPacket(probe, probe.size, target, DISCOVERY_PORT))
                            } catch (ignored: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }

            // Direct HTTP fallback if not found via UDP broadcast
            if (!found) {
                val savedIp = prefs.desktopIp.trim()
                val savedPort = prefs.desktopPort
                if (savedIp.isNotBlank() && savedIp != "0.0.0.0") {
                    try {
                        val client = com.aruncs.musicsync.client.DesktopApiClient()
                        val res = client.ping(savedIp, savedPort)
                        val hostname = res.optString("hostname", "Desktop")
                        onLog?.invoke("[DISCOVERY] Discovered Desktop '$hostname' at $savedIp:$savedPort (direct HTTP)")
                        onFound(savedIp, savedPort, hostname)
                        found = true
                    } catch (ignored: Exception) {}
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("[DISCOVERY] Discovery error: ${e.message}")
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
            releaseMulticastLock()
        }

        found
    }

    suspend fun discoverDesktopOnSubnet(
        context: Context,
        timeoutMs: Long = 3000
    ): String? = withContext(Dispatchers.IO) {
        var foundHost: String? = null
        discoverDesktop(
            context,
            timeoutMs = timeoutMs,
            onFound = { ip, _, _ ->
                foundHost = ip
            }
        )
        foundHost
    }

    /**
     * Broadcast an announcement so the Desktop server automatically registers this phone.
     */
    fun announceToServer(context: Context, onLog: ((String) -> Unit)? = null) {
        Thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true

                val prefs = AppPreferences(context)
                val model = NetworkUtils.getDeviceModel()

                val announce = JSONObject().apply {
                    put("magic", MAGIC_HEADER)
                    put("cmd", "ANNOUNCE")
                    put("role", "android")
                    put("hostname", model)
                    put("port", prefs.serverPort)
                }.toString().toByteArray(Charsets.UTF_8)

                val targets = getBroadcastTargets(context)
                for (target in targets) {
                    try {
                        socket.send(DatagramPacket(announce, announce.size, target, DISCOVERY_PORT))
                    } catch (ignored: Exception) {}
                }
                socket.close()

                onLog?.invoke("[DISCOVERY] Announced Android device '$model' to local network on port $DISCOVERY_PORT")
            } catch (e: Exception) {
                onLog?.invoke("[DISCOVERY] Announce error: ${e.message}")
            }
        }.start()
    }

    /**
     * Start background listener on Android to answer Desktop discovery probes.
     */
    fun startListener(context: Context, onLog: ((String) -> Unit)? = null) {
        if (isListening) return
        isListening = true

        listenerThread = Thread {
            acquireMulticastLock(context)
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    soTimeout = 2000
                }
                val buf = ByteArray(2048)

                while (isListening) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)

                        val senderIp = packet.address.hostAddress ?: continue
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)

                        if (json.optString("magic") == MAGIC_HEADER && json.optString("cmd") == "DISCOVER") {
                            val prefs = AppPreferences(context)
                            val model = NetworkUtils.getDeviceModel()

                            val reply = JSONObject().apply {
                                put("magic", MAGIC_HEADER)
                                put("cmd", "ANNOUNCE")
                                put("role", "android")
                                put("hostname", model)
                                put("port", prefs.serverPort)
                            }.toString().toByteArray(Charsets.UTF_8)

                            val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                            socket.send(replyPacket)
                            onLog?.invoke("[DISCOVERY] Answered Desktop discovery probe from $senderIp")
                        }
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (isListening) {
                            onLog?.invoke("[DISCOVERY] Listener error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                onLog?.invoke("[DISCOVERY] Could not bind listener on port $DISCOVERY_PORT: ${e.message}")
            } finally {
                try { socket?.close() } catch (ignored: Exception) {
                    onLog?.invoke("[Exception] $ignored")
                }
                releaseMulticastLock()
            }
        }.apply {
            isDaemon = true
            name = "android-peer-discovery"
            start()
        }
    }

    fun stopListener() {
        isListening = false
        listenerThread = null
        releaseMulticastLock()
    }
}

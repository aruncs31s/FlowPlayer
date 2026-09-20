package com.aruncs.musicsync.model

sealed class PlaybackTarget {
    abstract val displayName: String
    abstract val isLocal: Boolean
    open val device: SyncDevice? = null

    object Local : PlaybackTarget() {
        override val displayName: String = "This Device (Phone)"
        override val isLocal: Boolean = true
        override fun toString(): String = "Local"
    }

    data class Remote(override val device: SyncDevice) : PlaybackTarget() {
        override val displayName: String get() = device.name
        override val isLocal: Boolean = false
        val ip: String get() = device.ip
        val port: Int get() = device.port
        override fun toString(): String = "Remote(${device.name}, ${device.ip}:${device.port})"
    }
}

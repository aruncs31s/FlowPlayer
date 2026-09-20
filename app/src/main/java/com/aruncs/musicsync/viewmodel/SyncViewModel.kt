package com.aruncs.musicsync.viewmodel

import androidx.lifecycle.ViewModel
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.model.SyncDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val apiClient: DesktopApiClient
) : ViewModel() {

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<SyncDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<SyncDevice>> = _discoveredDevices.asStateFlow()

    fun setServerRunning(running: Boolean) {
        _serverRunning.value = running
    }
}

package com.aruncs.musicsync.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val apiClient: DesktopApiClient
) : ViewModel() {

    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

    private val _remoteSongs = MutableStateFlow<List<Song>>(emptyList())
    val remoteSongs: StateFlow<List<Song>> = _remoteSongs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadLocalLibrary(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            val songs = withContext(Dispatchers.IO) {
                MediaStoreHelper.getAllDeviceSongs(context)
            }
            _localSongs.value = songs
            _isLoading.value = false
        }
    }

    fun loadRemoteLibrary(ip: String, port: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val songs = apiClient.fetchSongs(ip, port)
                _remoteSongs.value = songs
            } catch (e: Exception) {
                _remoteSongs.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}

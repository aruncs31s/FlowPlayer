package com.aruncs.musicsync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.Playlist
import com.aruncs.musicsync.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistManager: PlaylistManager,
    private val apiClient: DesktopApiClient
) : ViewModel() {

    private val _localPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val localPlaylists: StateFlow<List<Playlist>> = _localPlaylists.asStateFlow()

    private val _remotePlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val remotePlaylists: StateFlow<List<Playlist>> = _remotePlaylists.asStateFlow()

    fun loadLocalPlaylists(allLocalSongs: List<Song>) {
        val userLists = playlistManager.getLocalPlaylists()
        val likedSongs = playlistManager.getLikedSongs(allLocalSongs)
        val likedPlaylist = Playlist(
            id = -1L,
            name = "Liked Music",
            trackCount = likedSongs.size,
            isRemote = false,
            trackFilepaths = likedSongs.map { it.filepath }
        )
        _localPlaylists.value = listOf(likedPlaylist) + userLists
    }

    fun loadRemotePlaylists(ip: String, port: Int) {
        viewModelScope.launch {
            try {
                val lists = apiClient.fetchPlaylists(ip, port)
                _remotePlaylists.value = lists
            } catch (e: Exception) {
                _remotePlaylists.value = emptyList()
            }
        }
    }
}

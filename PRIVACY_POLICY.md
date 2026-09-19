# Privacy Policy for Music Sync

*Last Updated: September 19, 2026*

**Music Sync** ("we", "our", or "the App") is developed as a peer-to-peer and local network companion application designed to synchronize, stream, and manage audio files between your Android device and personal computer. We are committed to protecting your privacy.

---

### 1. Data Collection and Transmission
- **Zero External Telemetry**: The App does **not** collect, store, transmit, or sell any personal data, usage analytics, device identifiers, or tracking metrics to external third-party servers.
- **Local Network Operation Only**: All network communication occurs exclusively across your private Local Area Network (LAN / Wi-Fi) directly between your Android device and your desktop companion client (or over direct USB via ADB). No audio files, metadata, or device information are ever transmitted to any cloud servers.

---

### 2. Device Permissions and Why We Need Them

The App requests the following permissions solely for its core music synchronization and playback functionality:

| Permission | Purpose |
| :--- | :--- |
| **`READ_MEDIA_AUDIO`** / **`READ_EXTERNAL_STORAGE`** | Allows the App to discover and index music files on your device so you can browse, stream, and sync them with your desktop. |
| **`WRITE_EXTERNAL_STORAGE`** (Android ≤ 9) | Allows the App to save new audio files synced from your desktop to your device's Music folder. |
| **`INTERNET`** & **`ACCESS_WIFI_STATE`** | Enables the local embedded HTTP server so your desktop companion can connect over your local Wi-Fi to sync or stream music. |
| **`CHANGE_WIFI_MULTICAST_STATE`** | Enables automatic peer discovery across your local Wi-Fi network using UDP broadcast/mDNS. |
| **`FOREGROUND_SERVICE`** & **`FOREGROUND_SERVICE_DATA_SYNC`** | Keeps the local sync server active while a data transfer or audio stream is in progress so transfers are not abruptly killed by Android battery optimization. A persistent notification is displayed whenever the server is active. |
| **`WAKE_LOCK`** | Prevents CPU sleep during large batch audio file transfers over Wi-Fi. |

---

### 3. Third-Party Services and SDKs
The App contains **no advertisements, no third-party analytics SDKs, and no tracking libraries**. All libraries used are open-source utilities (AndroidX, Kotlin Coroutines, OkHttp, and NanoHTTPD).

---

### 4. Children's Privacy
The App does not collect any personal information from anyone, including children under the age of 13.

---

### 5. Contact
If you have any questions or feedback regarding this Privacy Policy, please open an issue on our GitHub repository or contact the developer directly.

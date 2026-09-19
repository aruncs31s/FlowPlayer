# Android Music Sync — Android Companion App

Native companion application for **Android 7.0+ (Nougat, API level 24+)** designed to pair seamlessly with the **Android Music Sync** desktop system.

Built using **Kotlin**, **AndroidX**, **OkHttp**, and an embedded **NanoHTTPD** Over-IP server, formatted in the project's signature **pure pitch-black (`#000000`)** and **flat vivid yellow (`#FACC15`)** design language.

---

## Key Features

1. **Dual-Role Wireless Synchronization**:
   - **Over-IP Server**: Hosts a lightweight HTTP REST server on the phone (`:5000`), allowing the Desktop dashboard to browse, stream, and sync tracks wirelessly without needing USB or ADB.
   - **Over-IP Client**: Connects to the Desktop server (`/api/ping`, `/api/songs`, `/api/song/stream`, `/api/song/upload`) to perform two-way synchronization with live byte-level progress reporting.

2. **Android 7 (API 24+) Native Compatibility**:
   - `minSdkVersion = 24` (Android 7.0 Nougat).
   - Handles standard Android 7 storage permissions (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`) as well as Android 13+ modern audio permissions (`READ_MEDIA_AUDIO`).
   - Allows cleartext local HTTP traffic (`android:usesCleartextTraffic="true"` and `network_security_config.xml`) for private LAN subnets (`192.168.x.x`).

3. **Background Foreground Service & WakeLock**:
   - Runs as a low-overhead Android Foreground Service with persistent notification ("*Server Running at http://192.168.x.x:5000*").
   - Maintains a partial `WakeLock` and `WifiLock` so large multi-gigabyte library syncs continue uninterrupted even when the screen turns off.

4. **Native MediaStore Integration**:
   - Automatically triggers Android's `MediaScannerConnection.scanFile` upon receiving or downloading tracks, ensuring new songs immediately appear in Samsung Music, Poweramp, Retro Music, and other native player apps.

5. **Integrated Pitch-Black Audio Player**:
   - Built-in preview player bar with yellow scrubber, elapsed/total time, and play/pause controls to audition any track in the local library.

6. **Activity Terminal / Log Viewer**:
   - Real-time monospace terminal log capturing HTTP requests, download progress, diff calculations, and media scanner broadcasts.

---

## Project Structure

```
android_sync_app/
├── app/
│   ├── build.gradle                 # Module configuration (minSdk 24, compileSdk 34)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml      # Permissions, services, activities
│       ├── java/com/aruncs/musicsync/
│       │   ├── MainActivity.kt      # Main dashboard with bottom navigation & tabs
│       │   ├── client/
│       │   │   ├── DesktopApiClient.kt # OkHttp client calling Desktop endpoints
│       │   │   └── SyncManager.kt      # Two-way sync engine (Pull/Push)
│       │   ├── data/
│       │   │   ├── AppPreferences.kt   # Persistent preferences (IP, port, paths)
│       │   │   ├── MediaScannerHelper.kt # Broadcasts MediaStore refresh events
│       │   │   └── MediaStoreHelper.kt # Queries & deletes audio from MediaStore
│       │   ├── model/
│       │   │   ├── Song.kt          # Song schema matching Desktop JSON
│       │   │   └── SyncDiff.kt      # Sync diff model
│       │   ├── player/
│       │   │   └── AudioPlayer.kt   # MediaPlayer controller
│       │   ├── server/
│       │   │   ├── NetworkUtils.kt  # Resolves local Wi-Fi IP address
│       │   │   ├── OverIpServer.kt  # Embedded NanoHTTPD REST server
│       │   │   └── SyncForegroundService.kt # Foreground service keeping server alive
│       │   └── ui/adapter/
│       │       ├── LogAdapter.kt    # Monospace terminal log adapter
│       │       └── SongsAdapter.kt  # RecyclerView adapter for music library
│       └── res/
│           ├── drawable/            # Pitch-black cards & vivid yellow icons
│           ├── layout/              # XML layouts (activity_main, fragment_sync, etc.)
│           ├── values/              # colors.xml, strings.xml, themes.xml
│           └── xml/                 # network_security_config.xml
├── build.gradle                     # Top-level build file
├── settings.gradle                  # Project name & module inclusion
├── gradle.properties                # JVM & AndroidX settings
├── gradlew                          # Gradle Unix wrapper script
└── README.md
```

---

## How to Build & Run

### Option A: Using Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **File -> Open...** and select `/Users/aruncs/Desktop/Projects/android_sync_app`.
3. Let Gradle sync and download dependencies.
4. Connect an Android 7.0+ device via USB (or start an emulator running API 24+).
5. Click **Run 'app'** (`Shift + F10`) to build and deploy.

### Option B: Using Command Line (`gradlew`)
Ensure a Java 17+ JDK and the Android SDK are installed, then run:

```bash
cd /Users/aruncs/Desktop/Projects/android_sync_app

# Build debug APK
./gradlew assembleDebug

# The generated APK will be at:
# app/build/outputs/apk/debug/app-debug.apk

# Install to connected Android device via ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## How to Pair with the Desktop App

### Workflow 1: Wireless Control from Desktop (Desktop -> Phone)
1. In the Android app, tap **Start Server** on the Sync tab. Note the IP displayed (e.g. `http://192.168.1.105:5000`).
2. On your Desktop Mac/PC, open the **Android Music Sync** Web Dashboard (`http://localhost:5000`).
3. Go to **Dashboard** or **Sync** and enter `192.168.1.105:5000` under **Add Over-IP Peer**.
4. The desktop will connect to the Android device wirelessly! You can browse device tracks, stream songs, and push music from your Mac directly to the phone over Wi-Fi without ADB.

### Workflow 2: Direct Sync from Phone (Phone -> Desktop)
1. Make sure the Desktop app is running (`python app.py` or `./start.sh`).
2. In the Android app, enter your Desktop Mac's IP (e.g. `192.168.1.100`) and port `5000`.
3. Tap **Ping Desktop** to verify connection. The app displays latency and total desktop songs.
4. Tap **Pull from Desktop**:
   - The app computes the diff between local storage and desktop tracks.
   - It downloads any missing songs with a live progress bar.
   - It automatically updates the Android MediaStore so songs appear in your music player immediately.
5. Tap **Push to Desktop**:
   - Any local audio recordings or tracks stored on the phone are uploaded directly to the Desktop sync folder.

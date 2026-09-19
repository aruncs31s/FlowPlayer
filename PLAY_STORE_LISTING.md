# Google Play Console Submission Guide & Assets

Use this guide to fill out all the mandatory fields and questionnaires when submitting **Music Sync** to Google Play Console.

---

## 1. App Store Details

- **App Name** *(Max 30 characters)*:
  `Music Sync - Audio Companion`
- **Short Description** *(Max 80 characters)*:
  `Sync and stream your music library seamlessly between Android and your PC.`
- **Full Description** *(Max 4000 characters)*:
  ```text
  Music Sync is a lightweight, privacy-focused audio companion designed to synchronize, manage, and stream your personal music library between your Android device and your computer over your local Wi-Fi network or USB.

  Key Features:
  • Fast Wi-Fi Synchronization: Wirelessly sync your favourite audio tracks and playlists from your computer directly to your Android device without cloud uploads or cables.
  • Direct Audio Streaming: Stream songs directly from your phone to your desktop web player with smooth seek and scrub capabilities.
  • Embedded Companion Server: Features an integrated, lightweight background service that lets your desktop discover and transfer tracks reliably.
  • Audio Downconversion Support: Works seamlessly with your desktop companion to downconvert high-bitrate tracks (320 kbps / lossless) to save device storage space.
  • Broad Android Compatibility: Fully supported on Android 7.0 (Nougat) up through Android 14+.
  • Private & Secure: Completely free of ads, tracking, and third-party telemetry. All data stays strictly on your private local network.
  ```

---

## 2. Category and Tags
- **Application Type**: App
- **Category**: Music & Audio
- **Tags**: Music, Audio Player, File Transfer, Wi-Fi Sync

---

## 3. Data Safety Form (Mandatory Questionnaire)

In the Google Play Console **App content** → **Data safety** section, provide the following exact answers:

1. **Does your app collect or share any of the required user data types?**
   - Answer: **No**
2. **Is all of the user data collected by your app encrypted in transit?**
   - Answer: **Not applicable** (No data is collected or sent to external servers; transfers occur over local Wi-Fi).
3. **Do you provide a way for users to request that their data be deleted?**
   - Answer: **Not applicable** (No user accounts or cloud storage exist).

---

## 4. Android 14 Foreground Service Declaration (`FOREGROUND_SERVICE_DATA_SYNC`)

When prompted by Google Play Console regarding why your app uses the `dataSync` foreground service:

- **Service Type**: Data transfer / sync (`FOREGROUND_SERVICE_DATA_SYNC`)
- **Video demonstration requirement**: If requested, record a 30-second screen recording showing:
  1. Opening the app and tapping "Start Server".
  2. The persistent status notification appearing in the Android notification drawer.
  3. Transferring a song from the desktop dashboard to the phone.
- **Justification Text for Google Reviewer**:
  ```text
  Music Sync runs an embedded local HTTP server to synchronize audio files directly with the user's desktop companion computer over local Wi-Fi. 

  The dataSync foreground service and persistent notification are required to keep the server socket alive during multi-file batch sync transfers and audio streaming, preventing the Android OS battery manager from terminating the connection while in the background.
  ```

---

## 5. App Content Declarations

- **Ads**: Select **"No, my app does not contain ads"**.
- **App Access**: Select **"All functionality is available without special access"** (No login or credentials required).
- **Target Audience and Content**: Select **13 and older** (or All ages / 18+).
- **News App / Financial / Government**: Select **No** to all.

---

## 6. Release Tracks Recommendation
- **New personal Google Play developer accounts** must publish to **Closed Testing** with 12–20 testers for 14 continuous days before requesting Production release.
- Upload `app-release.aab` to **Testing** → **Closed testing**, invite testers by email or Google Group, and maintain daily testing activity before applying for Production.

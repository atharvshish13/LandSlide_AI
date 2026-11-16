# Drishti Setu Android app

Drishti Setu is an Android-only application for landslide sensor monitoring and risk guidance. Its interface is built with native Android views; the previous HTML/WebView dashboard has been removed. The APK includes the embedded Python/Flask API, on-device SQLite storage, and risk model. No website or hosted server is required for local sensor monitoring.

The native interface includes fixed Dashboard, Sensors, Assistant, Reports, and More navigation tabs. The dashboard shows the risk status, latest readings, model summary, and quick actions. Sensor history plots each saved sensor against its actual sample timestamps. Alerts, map, settings, and the conceptual slope view are also available from More. Tab changes return each screen to its top, nested More screens have a Back path, and the selected tab and touch states are highlighted. Connect to the ESP32 Wi-Fi station to receive live readings. Unknown or unavailable sensor readings are not presented as measurements. The slope illustration is conceptual and is not a surveyed or 3D terrain model.

On first launch, Android asks for location, notification, and microphone permissions. Location is used for device GPS/network fallback when the ESP32 has no satellite fix; the app uses the most recent phone location for SOS and map coordinates. Microphone access is used for voice questions in Gemini and can be denied while continuing to type. Android requests SMS permission only when an SOS is sent. Permissions can be denied or changed later in Android Settings.

Open **More → Medical history** to keep an optional emergency medical card (blood group, allergies, conditions, medications, and notes). It is encrypted on-device. Medical details are not sent unless you explicitly check the share option when saving the card; if enabled, they are included only in SOS messages to the configured contact. The app does not discover or automatically contact nearby hospitals or police. Use the medical section's map-search actions to find nearby services.

The Gemini chat supports Android speech-to-text through the **Mic** button. Speech recognition availability depends on the device's installed Android recognition service. The app cannot guarantee a free unlimited cloud AI service; Gemini needs your own API key and internet access, and provider free-tier quotas may apply.

## Mobile and Python backend

The mobile screens and Android platform features remain native Java; GPS, permissions, and direct SMS require Android APIs. Backend processing is Python: `backend/app.py` exposes the Flask API, `backend/telemetry.py` validates readings and calculates sensor quality and kinematic derivatives, `model/predict.py` evaluates risk, and `database/models.py` persists history in SQLite. `backend/android_runtime.py` starts the Python API inside the app and applies the encrypted provider settings; `backend/whatsapp_notifier.py` handles WhatsApp delivery. The API routes and JSON contracts remain unchanged by the telemetry module split.

This is an embedded Python backend running locally on the phone, not a separately hosted web service. That keeps local sensor processing available without a server; Gemini and WhatsApp still need internet connectivity.

## GitHub and VS Code

The project repository is [LandSlide_AI](https://github.com/atharvshish13/LandSlide_AI). Open the project root in VS Code to work with the Android, Python, database, and ESP32 source together. Sign in to GitHub from VS Code to publish local commits using Source Control or `git push`.

The `.gitignore` excludes local sensor databases, Python caches and virtual environments, Android build output and machine-specific SDK settings, and signing-key files. Never commit API keys, passwords, signing credentials, or real sensor-location history.

## Build and install

Requirements: Windows, Android Studio/Android SDK, and Python 3.10. The Gradle build uses Chaquopy with Python 3.10. If Python 3.10 is not on the Python Launcher, add the Python 3.10 installation directory to `PATH` before building.

In PowerShell:

```powershell
cd C:\LandslideAI\android_app
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to:

```text
C:\LandslideAI\android_app\app\build\outputs\apk\debug\app-debug.apk
```

Copy the APK to an Android phone and install it. If installing from a file manager, allow that app to install unknown apps. For USB installation with Android Platform Tools:

```powershell
adb install -r C:\LandslideAI\android_app\app\build\outputs\apk\debug\app-debug.apk
```

The debug APK is for testing and is signed with a debug key. For a Play Store release, configure the registered upload keystore variables documented in `android_app/app/build.gradle`, then build `:app:bundleRelease`.

## Configure Gemini

1. Revoke any Gemini key that was shared in chat, screenshots, or source code. Create a replacement key in [Google AI Studio](https://aistudio.google.com/apikey). Never commit or share the replacement.
2. Open Drishti Setu, go to **More → Settings and permissions → Configure API keys**, enter the replacement Gemini key, and save. The app encrypts provider settings with Android Keystore.
3. Connect the phone to the internet and open **Gemini**. The assistant sends the current risk/sensor summary and recent confirmed readings to Gemini 2.5 Flash through the on-device API. The Gemini key is sent to Google in an HTTPS request header; it is not embedded in the APK.

Gemini requires internet access. The ESP32 `GeoGuard_Landslide_AP` is a local sensor network and normally does not provide internet. If Android cannot keep mobile data while connected to the ESP32, use an internet-connected Wi-Fi network or router/bridge that can also reach the ESP32.

Gemini provides general decision support, not an official warning or geotechnical assessment. Follow local emergency authorities. A Gemini answer does not send an SOS or contact emergency services.

## Review sensor history

Open **Sensors** to load up to the latest 100 saved ESP32 readings. Charts use recorded timestamps and plot only sensors confirmed active for that sample; unavailable or legacy readings with unknown sensor status are not treated as measured zeroes. The ESP32 firmware reports its rain detector as a relative percentage (0–100), not calibrated rainfall in mm/h, so the app labels it as sensor output rather than a rainfall-rate measurement. History rows include ESP32 GPS coordinates when a fix is available. The Gemini assistant also receives recent saved readings for chronological trend summaries.

In **More → Map and coordinates**, the app prefers an ESP32 GPS fix and falls back to a recent phone GPS/network location. Google Earth 3D and Google Maps satellite are opened in their external apps or browser at those coordinates; internet access and installed Google services may be required. Coordinates are sent to Google when you explicitly open either map. This is not a live 3D map embedded in the app.

Under **More → Reports and exports**, create a Gemini analysis of up to 20 recent saved readings, then select **Download AI analysis as PDF**. You can also save a current sensor summary as PDF or export the full loaded sensor history (up to 100 readings) as CSV. Android's system document picker lets you choose where to save each file; CSV includes timestamps, sensor readings/status, risk values, and GPS coordinates when available. AI reports require a Gemini key and internet access.

## Connect the sensor station

1. Flash `esp32_firmware/esp32_landslide_system/esp32_landslide_system.ino` to an ESP32 with the required sensors wired and calibrated.
2. Connect the phone to the station Wi-Fi network `GeoGuard_Landslide_AP`.
3. Open Drishti Setu and allow location and notification permissions. The Android background service reads the station at `http://192.168.4.1/api/telemetry`; live sensor readings and risk history are saved by the embedded API in the app's private storage.
4. The app identifies missing and stale readings. Historical charts exclude sensors that were not confirmed active for that sample.

Set the SOS contact as an international E.164 number in **More → Settings and permissions → Configure API keys**. The app uses that same contact for SMS and WhatsApp. Tapping SOS submits an SMS after Android grants SMS permission; active mobile service and carrier charges may apply, and submission is not proof of delivery.

Without WhatsApp Business API credentials, SOS opens a WhatsApp click-to-chat conversation with the message filled in; you must tap **Send** in WhatsApp. A phone number/link alone cannot make WhatsApp send messages automatically. If you configure valid Meta WhatsApp Business API credentials and an approved template, the app can request automatic WhatsApp delivery; provider acceptance is not proof of delivery. HIGH/CRITICAL live telemetry WhatsApp alerts also require that API configuration. Direct SMS uses Android's restricted `SEND_SMS` permission; Google Play distribution may require eligibility under Google's SMS permission policies.

The app's maps and live weather require internet. Local sensor monitoring, risk evaluation, and notifications continue without internet while the ESP32 is reachable.

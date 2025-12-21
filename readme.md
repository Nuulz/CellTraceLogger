

# CellTraceLogger

**CellTraceLogger** is an open-source **Android application** that collects and logs **cellular network observations** (Cell ID, LTE / 5G NR metrics, signal strength) using a **foreground service**, without relying on GPS.
It is designed for **mobile network research**, **telephony analysis**, and integration with **cell-based inference models** such as *celltrace*.

The app records cellular events in **NDJSON log files**, supports **offline cell resolution**, and provides a **map view** to visualize detected cells and antennas.

![Android](https://img.shields.io/badge/platform-Android-informational)
![Kotlin](https://img.shields.io/badge/language-Kotlin-informational)
![Gradle](https://img.shields.io/badge/build-Gradle-informational)

---

## Table of contents

* [Features](#features)
* [Project requirements](#project-requirements)
* [Clone and run](#clone-and-run)
* [Configuration](#configuration)
* [Permissions](#permissions)
* [Generated files](#generated-files)
* [Main dependencies](#main-dependencies)
* [Troubleshooting](#troubleshooting)
* [License](#license)

---

## Features

* **Android foreground service** that periodically collects cellular network data and automatically rotates log files.
* Supports **5G NR**, **4G LTE**, and **3G WCDMA** radio technologies.
* Logs detailed **cell identifiers** (MCC, MNC, LAC/TAC, Cell ID) and **signal metrics** (RSRP, RSRQ, RSCP, RSSNR depending on radio).
* **Offline cell lookup** using a bundled CSV dataset plus a persistent cache (`cached_cells.csv`) to avoid repeated network queries.
* **Map visualization** using OpenStreetMap (OSMDroid) with markers colored by radio technology and detailed popups.
* Optional **cell location resolution** via the **Unwired Labs API** for cells not present in the offline dataset.
* Optional **Discord webhook reporting**, including periodic summaries and merged trace reports.

---

## Project requirements

* **Android SDK**

    * `minSdk = 29`
    * `targetSdk = 36`
    * `compileSdk = 36`
* **Java / Kotlin**

    * JVM target: **Java 11**

---

## Clone and run

Clone the repository:

```bash
git clone https://github.com/Nuulz/CellTraceLogger.git
cd CellTraceLogger
```

Build a debug APK:

### Linux / macOS

```bash
chmod +x gradlew
./gradlew assembleDebug
```

### Windows

```bat
gradlew.bat assembleDebug
```

Debug APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

<details>
  <summary>Optional: Release build</summary>

```bash
./gradlew assembleRelease
```

A release build typically requires configuring a signing keystore.

</details>

---

## Configuration

The app includes a **Settings screen** where you can configure:

* **Unwired Labs API key** (optional, used to resolve cells not found in the offline dataset).
* **Discord webhook URL** (optional, used for automated reporting).

If no API key is provided, the app operates in **offline-only mode**, displaying only cells resolved locally or from the persistent cache.

---

## Permissions

The following permissions are required for proper operation:

* **Telephony**

    * `READ_PHONE_STATE`
* **Location**

    * `ACCESS_COARSE_LOCATION`
    * `ACCESS_FINE_LOCATION`
    * `ACCESS_BACKGROUND_LOCATION`
* **Foreground service**

    * `FOREGROUND_SERVICE`
    * `FOREGROUND_SERVICE_LOCATION`
* **Notifications**

    * `POST_NOTIFICATIONS`
* **Network**

    * `INTERNET`
    * `ACCESS_NETWORK_STATE`
* **Legacy storage compatibility**

    * `WRITE_EXTERNAL_STORAGE` (limited with `maxSdkVersion="32"`)

The logging component runs as a **foreground service** declared with:

```xml
android:foregroundServiceType="location"
```

<details>
  <summary>Notes about background operation</summary>

On modern Android versions, background cellular data collection requires proper location permissions and a visible foreground notification while logging is active.

</details>

---

## Generated files

All generated files are stored under the **app-specific external files directory** (`getExternalFilesDir(null)`).

### NDJSON event logs

* Rotating files:

    * `celltrace_events_001.ndjson`
    * `celltrace_events_002.ndjson`
* Optional merged trace:

    * `celltrace_full_trace.ndjson`

Each line represents a single **cellular network event** in JSON format:

```json
{"radio":"lte","mcc":732,"mnc":101,"lac":12345,"cellid":67890,"rsrp":-95,"rsrq":-10,"rssnr":20,"timestamp":"2025-12-21T14:25:00.000-0500"}
```

---

### Persistent cell cache

* File: `cached_cells.csv`
* Columns:

  ```
  radio,mcc,mnc,area,cell,unit,lon,lat
  ```

This cache stores resolved cell coordinates to avoid repeated API lookups across sessions.

---

## Main dependencies

* **Google Play Services Location**
  `com.google.android.gms:play-services-location:21.1.0`
* **OSMDroid (OpenStreetMap)**
  `org.osmdroid:osmdroid-android:6.1.18`
* **OkHttp**
  `com.squareup.okhttp3:okhttp:4.12.0`
* **JSON**
  `org.json:json:20231013`

---

## Troubleshooting

<details>
  <summary>Logging does not start or event counter remains at zero</summary>

* Ensure location and phone state permissions are granted.
* Verify background location permission on Android 10+.
* Confirm the foreground service notification is visible while logging.

</details>

<details>
  <summary>Map shows markers without resolved coordinates</summary>

* The cell may not exist in the offline dataset.
* Ensure an Unwired Labs API key is configured.
* Check whether `cached_cells.csv` is being populated over time.

</details>

---

## License

MIT License



---
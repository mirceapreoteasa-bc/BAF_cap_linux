GCapAndroid - Installation Guide
==================================

This package contains the Android APK installer for GCapAndroid (BC VRI field data collector & .dat editor).

Supported Devices:
------------------
- Blackview BV5200 Pro (Rugged Phone, Android)
- Blackview Active 8 Pro (Rugged Tablet, Android)

Package Contents:
-----------------
1. app-debug.apk — The application installer binary.
2. README.txt — This instruction file.

Installation Instructions:
--------------------------
1. Copy `app-debug.apk` to your Blackview device (via USB cable, Bluetooth, Google Drive, or SD card).
2. On your device, open your File Manager app and tap on `app-debug.apk`.
3. If prompted about installing from "Unknown Sources", tap Settings and allow installation from your File Manager app.
4. Tap Install.
5. Once installed, open "Ground Call Capture" from your app drawer.

Data Folders Setup:
-------------------
Upon first run, GCapAndroid automatically creates and uses the required directory structure in your device's internal storage / Downloads folder:
- `/sdcard/Download/Groundcalls/` (stores `.dat` files)
- `/sdcard/Download/Groundcalls/GPS_MAP/` (stores shapefile map layers)
- `/sdcard/Download/Groundcalls/XGV_ODS_XLSX/` (stores generated `.ods` and `.xlsx` summaries)

You can push existing `.dat` files or shapefile map layers directly into `/sdcard/Download/Groundcalls/` using a file manager or ADB.

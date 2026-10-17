# PLate.Launcher — Setup & User Guide

Step-by-step setup, permissions guide, OEM troubleshooting, and frequently asked questions for **PLate.Launcher**.

---

## 🚀 Quick Start

1. **Install the APK**: Download the latest release from the [GitHub Releases](https://github.com/Zoro-15/PLate.Dex/releases).
2. **Grant Core Permissions**: Enable the **Accessibility Service** when prompted (required to render the dock and windows).
3. **Launch the Desktop**: Choose to set PLate.Launcher as your default launcher, or open it on demand via the Quick Settings tile or desktop shortcut.

---

## 🔑 Privileged Permissions (Root / Shizuku / ADB)

Certain advanced features (custom resolution, background process management, auto-granting permissions) require privileged access.

### Option 1: Shizuku (Recommended)
1. Install [Shizuku](https://shizuku.rikka.app/) from Google Play or GitHub.
2. Start the Shizuku service via Wireless Debugging or ADB.
3. Open **PLate.Launcher Settings → Permissions** and tap **Grant** next to Shizuku.

### Option 2: Root (Magisk / KernelSU / APatch)
- Root is detected automatically on launch; tap **Grant** when prompted by your root manager.

### Option 3: Manual ADB Setup
Connect your device to a computer and run:
```bash
adb shell pm grant com.plate.launcher android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.plate.launcher GET_USAGE_STATS allow
```

---

## 📋 Required Permissions Breakdown

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Renders the dock, taskbar, and floating windows over apps |
| `Accessibility Service` | Core service driving desktop window interactions and gestures |
| `PACKAGE_USAGE_STATS` | Detects running tasks and sorts recent applications |
| `WRITE_SECURE_SETTINGS` | Adjusts system display parameters and settings (Root/Shizuku/ADB) |
| `QUERY_ALL_PACKAGES` | Populates apps in the launcher and app drawer |
| `Notification Listener` | Displays and manages notifications directly within the desktop tray |
| `MANAGE_USERS` | Enables multi-user profile switching from the power menu |
| `MANAGE_EXTERNAL_STORAGE` | Imports/exports `.sdp` settings backups and manages wallpaper assets |
| `BLUETOOTH_CONNECT` / `SCAN` | Controls status and connection toggles in the system tray |
| `KILL_BACKGROUND_PROCESSES` | Clears memory when triggering "Clear All" in Task View |
| `CAPTURE_VIDEO_OUTPUT` | Creates isolated virtual displays for self-testing (Root/Shizuku) |

---

## 🔧 Device-Specific Setup (OEMs)

### RedMagic & ZTE Devices
These devices ship with freeform window support disabled by default:
1. Go to **Settings → About Phone** and tap **Build Number** 7 times to enable Developer Options.
2. Under **Developer Options**, enable **Force activities to be resizable** and **Enable freeform windows**.
3. Navigate to **Settings → Display → Desktop Mode** and turn it on.
4. Restart your device.

### Xiaomi (MIUI / HyperOS), OPPO (ColorOS), vivo (Funtouch)
Aggressive background task killers may stop the Accessibility service after reboots:
- Set PLate.Launcher battery usage to **Unrestricted** / **No restrictions**.
- Enable **Autostart** in system app permissions.

---

## ❓ Frequently Asked Questions (FAQ)

### Android 13+ blocks the Accessibility Service ("Restricted setting")
Android blocks accessibility services for apps installed outside official app stores.
- **Fix via Settings**: Open **Settings → Apps → PLate.Launcher**, tap the three dots `⋮` in the top-right corner, and select **Allow restricted settings**.
- **Fix via ADB**:
  ```bash
  adb shell appops set com.plate.launcher REQUEST_INSTALL_PACKAGES allow
  ```

### The dock disappeared after rebooting
Verify that the Accessibility Service is still active in your device Settings, and ensure your battery manager has not killed background services.

### Can I use PLate.Launcher alongside my standard launcher?
Yes. PLate.Launcher does not have to be your default home app. You can invoke it whenever needed using the Quick Settings tile or app shortcut.

### How do I back up and restore my configuration?
Navigate to **Settings → Advanced Preferences**:
- Tap **Backup Preferences** to export an `.sdp` file.
- Tap **Restore Preferences** to import any previous backup (supports both Youki DEX and PLate.Launcher backups).

---

## 🏗️ Architecture

- **Kotlin Framework**: Handles Android system interactions, UI components, databases, and background services.
- **Rust Native Engine**: Powers live wallpaper rendering, math transforms, and high-performance operations via JNI.
- **Minimum Requirements**: Android 8.0 (API level 26) or higher.

---

👉 *Want to explore all launcher features? See [Features.md](Features.md).*

# Spider Proxy Client: sing-box Core Update & Synchronization Guide

This document provides step-by-step instructions for the IDE AI Agent (e.g., Trae, Copilot) to update and synchronize the **sing-box** core/libraries across all supported platforms (Windows, Android, iOS).

When the user requests a core update, follow these steps precisely.

---

## 📂 Core File Locations by Platform

| Platform | Core Dependency Type | Target File Path in Project |
| :--- | :--- | :--- |
| **Windows** | Executable Binary (`.exe`) | `D:\workspace\Spider\assets\binaries\windows\sing-box.exe` |
| **Windows (TUN)** | Wintun Network Driver (`.dll`) | `D:\workspace\Spider\assets\binaries\windows\wintun.dll` |
| **Android** | Android Archive Library (`.aar`) | `D:\workspace\Spider\android\app\libs\singbox.aar` |
| **iOS** | Xcode Framework Bundle (`.xcframework`) | `D:\workspace\Spider\ios\Frameworks\SingBox.xcframework` |

---

## 🛠️ Step-by-Step Update Procedure

### 1. Windows Core Update
1.  Download the target version zip `sing-box-[VERSION]-windows-amd64.zip` from [sing-box GitHub Releases](https://github.com/SagerNet/sing-box/releases).
2.  Extract the zip file and locate `sing-box.exe`.
3.  Replace the existing file at `D:\workspace\Spider\assets\binaries\windows\sing-box.exe` with the new binary.
4.  *(Optional)* If Wintun driver is updated, download `wintun.dll` (amd64) from [wintun.net](https://www.wintun.net/) and replace `D:\workspace\Spider\assets\binaries\windows\wintun.dll`.

### 2. Android Core Update
1.  Obtain the compiled Android archive file `singbox.aar` for the target version (either compiled via `gomobile bind` or downloaded from official pre-builds).
2.  Replace the file at `D:\workspace\Spider\android\app\libs\singbox.aar` with the new file.
3.  Ensure `D:\workspace\Spider\android\app\build.gradle` has the flat repository dependency configured:
    ```groovy
    dependencies {
        implementation fileTree(dir: 'libs', include: ['*.aar'])
        // ... other dependencies
    }
    ```

### 3. iOS Core Update
1.  Obtain the compiled iOS Apple framework `SingBox.xcframework` for the target version (compiled via `gomobile bind -target ios`).
2.  Delete the old framework directory at `D:\workspace\Spider\ios\Frameworks\SingBox.xcframework` entirely.
3.  Copy the new `SingBox.xcframework` folder into `D:\workspace\Spider\ios\Frameworks/`.
4.  Verify in the Xcode project settings (`D:\workspace\Spider\ios\Runner.xcodeproj/project.pbxproj`) that `SingBox.xcframework` is linked and set to **"Embed & Sign"**.

---

## ⚙️ Configuration Compatibility & Schema Check

Every time the sing-box core is upgraded, the JSON configuration schema might change. The agent must verify:
1.  Review sing-box Release Notes for any **Breaking Changes** regarding JSON configurations (e.g., changes to DNS routing, inbound/outbound parameters).
2.  Inspect the local configuration generator code:
    *   File: `D:\workspace\Spider\lib\features\core_service/` (Specifically the config generator provider/class).
3.  If any JSON schema tags or fields have been deprecated or modified in the new sing-box version, update the generator code output format to match the new schema requirements exactly.

---

## 🧪 Post-Update Validation

After replacing the binaries/libraries and updating the config schema, execute the following commands in the terminal to verify the build integrity:

```bash
# 1. Clean build cache
flutter clean

# 2. Re-fetch packages
flutter pub get

# 3. Compile check (Windows)
flutter build windows --no-pub

# 4. Compile check (Android)
flutter build apk --debug --no-pub
```

Report any compilation or linting errors immediately to the user.

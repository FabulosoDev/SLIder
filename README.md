# SLIder for Android

SLIder reads compatible ISO15693 / NfcV tags and saves their contents as Flipper `.nfc` dumps. It does not modify NFC tags. The project is a standard Kotlin Android app and can be edited and built entirely from VS Code.

## What you need

- Windows with JDK 17 or newer (`java -version`)
- [Android SDK command-line tools](https://developer.android.com/tools) or the SDK installed by Android Studio
- Android SDK Platform 36, Build Tools 36.0.0, and Platform Tools
- A physical NFC-capable Android phone for testing (the Android emulator cannot test this app's NFC workflow)

This repository includes the Gradle wrapper, so a system-wide Gradle installation is not needed.

## One-time Android SDK setup on Windows

If Android Studio is installed, use its SDK Manager to install **Android SDK Platform 36**, **Android SDK Build-Tools 36**, and **Android SDK Platform-Tools**. You can then close Android Studio and work in VS Code.

For a command-line-only setup, extract Google's command-line tools so that this file exists:

```text
%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat
```

Then open PowerShell and run:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path += ";$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin"
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Add `ANDROID_HOME` and both `Path` entries to your Windows user environment variables to keep them after restarting the terminal. Alternatively, create an untracked `local.properties` file in the project root:

```properties
sdk.dir=C:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
```

## Build in VS Code

1. Open this repository folder in VS Code.
2. Open **Terminal → Run Build Task** and choose **Android: build debug APK**.

The APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Equivalent terminal commands:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Install and run on a phone

Enable Developer options and USB debugging on the phone, connect it by USB, and accept the debugging prompt. Confirm the connection with:

```powershell
adb devices
```

In VS Code, run **Terminal → Run Task → Android: launch app**. That task builds, installs, and opens the app. You can also run it manually:

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n dev.fabuloso.slider/.MainActivity
```

## NFC workflow

Open SLIder and hold a compatible NfcV tag against the phone. The tag is read automatically; no button press is required. After reading, choose where to save the resulting Flipper `.nfc` file. You can also hold a compatible tag to the phone while the app is closed; Android will open SLIder and read it immediately.

Keep the phone still against the tag until the read finishes.

## Project layout

```text
app/src/main/AndroidManifest.xml
app/src/main/java/dev/fabuloso/slider/     Kotlin sources
app/src/main/res/                          UI and Android resources
.vscode/tasks.json                         build, install, and launch tasks
```

# DeltaFiles

A simple, fast file manager for Android (Kotlin, Material 3, Android 8.0+).

## Features

- Browse internal storage with a tappable path bar
- Sort by name, date, size or type (folders always first)
- Search within the current folder
- Create folders and files
- Select multiple items: copy, cut, paste, rename, delete, share, details
- Show or hide hidden files
- Open files in whatever app handles them
- Light and dark themes

## Get an APK

### Option A: GitHub Actions (no computer needed)

1. Create a new GitHub repository and upload everything in this folder
   (keep the `.github` folder).
2. Open the **Actions** tab and run **Build DeltaFiles APK**
   (it also runs on every push to `main`).
3. When it finishes, open the run and download the **DeltaFiles-debug-apk**
   artifact. It is a zip containing `app-debug.apk`.
4. Tip: push a tag such as `v1.0` and the APK is attached to a GitHub Release
   as a plain `.apk` download.

### Option B: Android Studio

1. Open this folder in Android Studio and let Gradle sync.
2. **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### Option C: Command line

With JDK 17, the Android SDK (platform 34) and Gradle 8.7 installed:

    gradle assembleDebug

## Install on your phone

1. Copy or download `app-debug.apk` to the phone and tap it.
2. If asked, allow installs from this source (Chrome, Files, etc.).
3. Open DeltaFiles and tap **Grant access**. Android sends you to a settings
   screen where you turn on "Allow access to manage all files".

## Notes

- The debug APK is signed with the standard Android debug key, which is fine
  for personal installs. Use your own keystore for anything you distribute.
- "All files access" is needed to manage files across storage. Google Play
  restricts this permission, so this build is meant for sideloading.
- Android 11+ blocks apps from `Android/data` and `Android/obb`; those folders
  show as unreadable.

# Escape Keyboard

A full PC-style keyboard for Android: Esc, Ctrl, Alt, Tab, Fn, arrow keys,
Home/End/Del and F1–F12, with real modifier chording — made for SSH clients,
terminals, remote desktops and anyone who misses a desktop layout on a phone.

- Compact 5-row and full 5-row layouts, 4-row layout
- Ukrainian and Russian ЙЦУКЕН in the compact layout, 40+ languages overall
- Google-style themes that follow the system light/dark mode
- Material 3 settings, Android 17 (API 37) target
- No internet permission: nothing you type can leave the device

## Building

Requirements: JDK 21, Android SDK (platform 37, build-tools 37.0.0),
NDK 29.0.14206865, CMake 4.1.2.

```bash
./gradlew assembleRelease   # APK, for installing by hand
./gradlew bundleRelease     # AAB, for Google Play
```

Release builds are signed with the key described in `keystore.properties`
(next to `settings.gradle`, not committed):

```properties
storeFile=../full-keyboard-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Without that file release builds fall back to the local debug key, which is
fine for side-loading but is rejected by Google Play.

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).
This product is a modified version of Hacker's Keyboard, which is based on
the Android Open Source Project keyboard; see [NOTICE](NOTICE) for the
retained copyright notices and third-party components.

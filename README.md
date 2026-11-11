# Escape Keyboard

[Українською](README.uk.md)

A full PC-style keyboard for Android: Esc, Ctrl, Alt, Tab, Fn, arrow keys,
Home/End/Del and F1–F12, with real modifier chording. Made for SSH clients,
terminals, remote desktops and anyone who misses a desktop layout on a phone.

Escape Keyboard is a reworked fork of [Hacker's Keyboard](https://github.com/klausw/hackerskeyboard)
by Klaus Weidner, including later changes by Maxim Pulya, rebuilt for Android 17.

**[Download the latest APK](../../releases/latest)**

- Package: `app.escapekeyboard`
- Android 5.0 or newer, targets Android 17 (API 37)
- Permissions: vibration and the user dictionary only. No internet access:
  nothing you type can leave the device.

## Features

**Layouts and languages**
- Compact 5-row, full 5-row and 4-row layouts.
- Ukrainian and Russian ЙЦУКЕН in the compact layout, about 30 languages in
  compact form and 40+ overall; ₴ on the long press of $ and 4.
- English in Dvorak, Colemak, Colemak-DH, Workman, QWERTZ and AZERTY (with the English
  dictionary): turn them on in Input languages.
- Optionally remembers the input language for each app.
- One extra key can be emoji, the clipboard, a globe (next language) or voice input: Tab left
  of the space bar in Compact, the gear in the other layouts (Settings stay on a long press).

**Terminal**
- A custom key row above the keyboard with macros (off by default).
- Ctrl and Alt work as real modifiers and light up while active.
- Text selection like on a PC: tap Shift, then arrows, Home/End or PgUp/PgDn (or swipe
  along the space bar in cursor mode); add Ctrl for whole words. Ctrl+A selects all.

**Typing**
- Built-in dictionaries for Ukrainian, Russian and English: suggestions and
  autocorrection.
- Optional, off by default: web addresses, emails and paths (github.com, me@mail.ua,
  ~/src) are left as typed instead of being split and autocorrected, and a long press on
  "." in address fields offers .com, .ua, .net, .org and more.
- Clipboard history in a floating window: pin, edit, swipe to delete with undo. Long-press
  Enter to open it; it closes after a paste.
- Emoji panel and voice input through the installed voice keyboard.
- Inline autofill suggestions from your password manager (Android 11+).
- Also optional and off by default:
  - Shift taps change the case of selected text: UPPER, lower, Capitalised.
  - The emoji panel closes after an emoji is picked.
  - Text copied less than a minute ago is offered first in the suggestion strip, never for
    passwords, in incognito or on the lock screen.
  - Persian: a long press on space offers the zero-width non-joiner (ZWNJ).
  - Keys vibrate in Do Not Disturb too.

**Gestures**
- Swipe along the space bar to switch the language or to move the cursor (then switch the
  language with the extra key set to the globe).
- Slide from ?123 (off by default): it switches as soon as it's pressed; slide to a symbol
  and let go to type it and return to the letters.
- Swipe left from Backspace (off by default) selects characters or words to delete: lift
  the finger to delete them, slide back to cancel. Not in terminals, where Backspace works
  as before.
- One-handed mode: keys move to the left or right edge, 60–95% wide. Drag the
  handle in the empty side to change the width. It can also be bound to a swipe left
  or right, or turned on and off from a key in the long-press popup of the comma or of
  the extra key that replaces the gear (off by default). Portrait only unless "In landscape too" is on; in landscape the keys
  are as wide as in portrait.

**Themes**
- Google (follows the system, light, dark, wallpaper colours / Material You).
- Cupertino, Keycap, Mechanical and Retro terminal (light or dark, following the system), Real
  keycaps (cut from a photo of a real keyboard), Graphite, and the classic
  Hacker's Keyboard themes.

**Settings**
- Material 3 settings with full Ukrainian and Russian translations.

## Installing

1. Download `EscapeKeyboard-*.apk` from [Releases](../../releases) and install it.
2. Open Escape Keyboard and follow the two steps on the start screen:
   turn it on in the system input settings, then make it the active keyboard.

It installs as a separate app next to Hacker's Keyboard. Settings are not
carried over.

## Custom key row and macros

Settings → Terminal and PC keys → Custom key row. Keys are separated by spaces:

| Entry | Example |
|---|---|
| Key names | `Esc Tab Enter Bksp Del Ins Home End PgUp PgDn Up Down Left Right F1`…`F12` |
| Modifiers | `Ctrl Alt`, which toggle like the keys on the keyboard |
| Combinations | `Ctrl+C Alt+F Ctrl+Left` |
| Any single character | `\| ~ / -` |
| Text in quotes, typed as is | `"sudo "` `"\| grep "` `"\n"` for a new line |
| Editing | `Copy Cut Paste SelectAll SelectWord Undo Redo`, `DelWord` deletes the word before the cursor (like Ctrl+Backspace) |
| Panels | `Clipboard Emoji Voice`, `OneHand` turns one-handed mode on and off |
| Date and time | `Date Time DateTime`; a format after a colon: `Date:dd.MM.yyyy` `Time:HH:mm:ss` `DateTime:"d MMM HH:mm"` |
| Media | `Play Next Prev VolUp VolDown Mute` |

The default row is `Esc Tab Ctrl Alt | ~ / - "sudo " "| grep "`.

- Arrows and Backspace repeat while held.
- A long row scrolls, and scrolling never types anything.
- Names are not case sensitive: `copy` works as well as `Copy`.
- The names in the last four rows of the table are new in this release and now run
  commands. If your row had such a word without quotes (`Date`, `Next`, `Mute`…) and it
  should still type the word, put it in quotes: `"Date"`.
- Terminals ignore Copy, Cut and Paste as commands: there Paste types the clipboard,
  and Copy never becomes Ctrl+C. SelectAll, Undo and Redo send nothing to a terminal,
  where Ctrl+A and Ctrl+Z mean something else.
- The date format is Java's `SimpleDateFormat`; without one, or with a wrong one,
  it is `yyyy-MM-dd`, `HH:mm` and `yyyy-MM-dd HH:mm`.

## Building

Requirements: JDK 21, Android SDK (platform 37, build-tools 37.0.0),
NDK 29.0.14206865, CMake 4.1.2.

```bash
./gradlew assembleRelease   # APK, for installing by hand
./gradlew bundleRelease     # AAB, for Google Play
```

Release builds are signed with the key described in `keystore.properties`
(next to `settings.gradle`, never committed):

```properties
storeFile=escape-keyboard-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Without that file, release builds fall back to the local debug key. That is
fine for side-loading but cannot update an official build and is rejected by
Google Play.

The built-in dictionaries are generated by [tools/dict](tools/dict/README.md).

## Privacy

See [PRIVACY.md](PRIVACY.md). In short:
- the keyboard has no internet permission;
- clipboard history stays on the device and is excluded from backups, is hidden on the lock
  screen, and unpinned entries can expire after an hour, a day, 7 or 30 days;
- in private fields (incognito tabs) it learns nothing; "Always incognito" turns learning off
  everywhere, and learned words can be cleared in one tap;
- texts from password managers are not saved;
- the offer of freshly copied text in the suggestion strip is off by default and never
  shows in password fields, incognito or on the lock screen.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

This product is a modified version of Hacker's Keyboard, which is based on the
Android Open Source Project keyboard. [NOTICE](NOTICE) lists the retained
copyright notices and third-party components.

The dictionaries are built from [wordfreq](https://github.com/rspeer/wordfreq)
data by Robyn Speer, licensed under CC BY-SA 4.0.

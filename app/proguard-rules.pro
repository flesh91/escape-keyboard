# The keyboard's own classes are created by name from XML (preferences, layouts,
# manifest) and called from native code, so they are kept as they are; R8 only
# shrinks the libraries (AndroidX, Material), which ship their own rules.
-keep class org.pocketworkstation.pckeyboard.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

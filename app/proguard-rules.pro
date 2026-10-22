# Add project specific ProGuard / R8 rules here.

# Room Database entities, DAOs, and query POJOs
-keep class app.nagatani.dev.attendance.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase {
    void <init>();
}

# MIFARE & Export Preferences / Enums
-keep class app.nagatani.dev.attendance.data.preferences.** { *; }
-keepclassmembers enum app.nagatani.dev.attendance.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# USB CCID & NFC Reader models
-keep class app.nagatani.dev.attendance.usb.** { *; }
-keep class app.nagatani.dev.attendance.nfc.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

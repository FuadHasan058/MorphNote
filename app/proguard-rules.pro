# ProGuard rules for MorphNote
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class com.example.morphnote.database.** { *; }


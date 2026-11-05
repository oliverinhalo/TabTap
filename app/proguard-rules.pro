# The composition model is serialised to JSON by kotlinx.serialization, whose
# generated serializers are looked up reflectively by class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.tabtap.** {
    *** Companion;
}
-keepclasseswithmembers class com.tabtap.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tabtap.music.**$$serializer { *; }
-keep,includedescriptorclasses class com.tabtap.app.data.**$$serializer { *; }

# Room generates implementations that are instantiated by name.
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

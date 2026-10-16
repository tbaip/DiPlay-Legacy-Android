# Application class initialization happens before MultiDexApplication.attachBaseContext.
-keep class com.shilapi.xcertplay.DiPlayApplication { *; }
-keep class com.shilapi.xcertplay.DiPlayApplication$* { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class androidx.multidex.** { *; }
-keep class com.shilapi.xcertplay.LoggingGate { *; }
-keep class com.shilapi.xcertplay.FloatingDotOverlay { *; }

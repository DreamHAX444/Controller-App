# App ProGuard & R8 configuration
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.lang.invoke.**
-dontwarn kotlinx.serialization.**
-dontwarn org.osmdroid.**
-dontwarn okhttp3.**

-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Kotlinx Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    companion *;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep ViewModel constructors for reflection/Factory
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Core ProGuard & R8 configuration
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.lang.invoke.**
-dontwarn kotlinx.serialization.**

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

# kotlinx.serialization keeps generated serializers on the companion of each
# @Serializable class. Without these the release build parses nothing.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class ph.edu.mmsu.ee.grades.data.** {
    *** Companion;
}
-keepclasseswithmembers class ph.edu.mmsu.ee.grades.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ph.edu.mmsu.ee.grades.data.**$$serializer { *; }

# OkHttp ships references to optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

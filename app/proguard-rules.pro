# kotlinx.serialization（AppData / GitHub API DTO）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mengpolar.dailycheck.**$$serializer { *; }
-keepclassmembers class com.mengpolar.dailycheck.** {
    *** Companion;
}
-keepclasseswithmembers class com.mengpolar.dailycheck.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Native methods and JNI callbacks are resolved by name/signature.
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

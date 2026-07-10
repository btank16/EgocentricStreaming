# Consumer ProGuard rules for @chameleon/expo-xr-android
#
# These rules are bundled into the AAR (via `consumerProguardFiles` in
# build.gradle) and merged into the consuming app's R8 pass automatically.
#
# The Jetpack XR set-up docs publish no explicit -keep rules: the androidx.xr.*
# libraries ship their own consumer rules, so they need nothing here.
#
# The one gap is `com.android.extensions.xr`, which is a `compileOnly`
# dependency — it is provided by the device's XR system at runtime and is never
# packaged in the APK. Because it is absent from the runtime/consumer classpath,
# its own consumer rules are not applied, and R8 would otherwise warn about (and
# could strip) references to it. Keep the API surface and silence the warnings.
-keep class com.android.extensions.xr.** { *; }
-dontwarn com.android.extensions.xr.**

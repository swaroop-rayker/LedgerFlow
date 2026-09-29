# Release R8 rules for :app. Inherited by the `benchmark` build type.

# BUG38: receipt OCR failed in every release build.
# ML Kit starts through Firebase's component discovery, which instantiates
# each ComponentRegistrar reflectively by its no-argument constructor.
# firebase-components 16.1.0 ships `-keep class * implements ComponentRegistrar`
# with no member list; in R8 full mode (AGP's default) that keeps the class and
# not its constructor, so CommonComponentRegistrar.<init>() was removed and
# TextRecognition failed with a NullPointerException on first use. Debug is not
# shrunk and never showed it. Bug38_OcrWorksInAShrunkBuildTest guards it.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# Supplied by the device at runtime (Jetpack WindowManager's OEM extensions),
# never packaged. Once a build type has a rules file, R8 reports these as
# missing; the lines are R8's own generated suggestion (missing_rules.txt).
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

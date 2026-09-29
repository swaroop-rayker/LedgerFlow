# The `benchmark` build type only (P5).
#
# Release code, shrunk and optimised as release is, but with its class and
# method names kept. The baseline profile is recorded from this build, and a
# profile written in R8's renamed names (La0;, Ljm0;) matches nothing in any
# other build: the renaming differs every time. Measured 2026-09-29, the first
# recorded profile was in exactly those names. R8 rewrites a real-named profile
# into each release build's own names when it compiles it in.
-dontobfuscate

# Supplied by the device at runtime (Jetpack WindowManager's OEM extensions),
# never packaged. Giving this build type a rules file makes R8 report them as
# missing; these lines are R8's own generated suggestion (missing_rules.txt).
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

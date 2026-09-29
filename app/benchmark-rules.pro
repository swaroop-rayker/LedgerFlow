# The `benchmark` build type only (P5), on top of proguard-rules.pro, which it
# inherits from release.
#
# Release code, shrunk and optimised as release is, but with its class and
# method names kept. The baseline profile is recorded from this build, and a
# profile written in R8's renamed names (La0;, Ljm0;) matches nothing in any
# other build: the renaming differs every time. Measured 2026-09-29, the first
# recorded profile was in exactly those names. R8 rewrites a real-named profile
# into each release build's own names when it compiles it in.
-dontobfuscate

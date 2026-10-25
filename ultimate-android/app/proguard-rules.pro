# Compose, AndroidX and Media3 ship their own consumer rules.

# The MP3 encoder is reached through a stand-in for javax.sound's AudioFormat (see src/main/java/javax/sound).
# It cannot be exercised in unit tests on a device-less build, so keep both exactly as written.
-keep class de.sciss.jump3r.** { *; }
-keep class javax.sound.** { *; }
-dontwarn javax.sound.**

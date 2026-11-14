# Keep reflection, JNI and desktop runtime entry points intact.
# Only unused Material icon classes may be removed from the release package.
-keep class !androidx.compose.material.icons.**,** { *; }
-keepattributes *

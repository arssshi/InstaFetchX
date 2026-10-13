# InSave ships zero third-party libraries, so R8 has almost nothing to strip.
# These rules only make aggressive full-mode shrinking safe.

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# org.json constructs objects reflectively in places; nothing of ours is passed to it.
-dontwarn org.json.**

# Ignore missing optional Java 9+ APIs on older Android runtimes (we never call them).
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**

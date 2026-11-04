# Keeps Kotlin's metadata, as an app that reads it at run time does (kotlin-reflect, for one), so R8
# reads and rewrites the metadata of every class it keeps: an R8 older than 8.10.21 (AGP 8.0's)
# does not know Kotlin 2.2's and warns, which compat/run.sh counts.
-keep class kotlin.Metadata { *; }

# Hopi's performance monitors, in debuggable variants only (docs/design/perf-monitors.md).
# hopi-android's provider loads PerfEntry by name when the plugin put hopi-perf in the variant.
-keep class dev.koga.hopi.perf.internal.PerfEntry { <init>(); *; }

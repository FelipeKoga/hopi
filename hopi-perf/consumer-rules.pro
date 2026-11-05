# Hopi's performance monitors, in debuggable variants only (docs/design/perf-monitors.md).
# The app's generated configuration names PerfEntry, the start sequence's perf tool, when the plugin
# put hopi-perf in the variant (before, the provider loaded it by name).
-keep class dev.koga.hopi.perf.internal.PerfEntry { <init>(...); *; }

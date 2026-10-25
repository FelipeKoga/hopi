package dev.koga.hopi.core.bench

internal actual fun hotPathBenchmarkEnabled(): Boolean = System.getProperty("hopi.hotpath") != null

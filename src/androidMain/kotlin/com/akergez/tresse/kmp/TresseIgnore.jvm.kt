package com.akergez.tresse.kmp

import uniffi.tresse_ffi.FfiIgnoreRules

// No native-library setup here: on Android the `.so` ships in `jniLibs` and the system
// loader finds it. Desktop has to unpack it from the jar first — see `TresseNative`.
actual class TresseIgnore actual constructor(patterns: List<String>) : AutoCloseable {
    private val rules = FfiIgnoreRules(patterns)

    actual fun isIgnored(path: String, isDir: Boolean): Boolean = rules.isIgnored(path, isDir)

    actual fun warnings(): List<String> = rules.warnings()

    override fun close() = rules.close()
}

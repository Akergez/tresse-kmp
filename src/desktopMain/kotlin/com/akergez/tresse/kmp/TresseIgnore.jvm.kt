package com.akergez.tresse.kmp

import uniffi.tresse_ffi.FfiIgnoreRules

actual class TresseIgnore actual constructor(patterns: List<String>) : AutoCloseable {
    private val rules = run {
        TresseNative.ensureLoaded()
        FfiIgnoreRules(patterns)
    }

    actual fun isIgnored(path: String, isDir: Boolean): Boolean = rules.isIgnored(path, isDir)

    actual fun warnings(): List<String> = rules.warnings()

    override fun close() = rules.close()
}

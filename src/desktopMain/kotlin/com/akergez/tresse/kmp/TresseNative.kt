package com.akergez.tresse.kmp

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Unpacks the bundled `libtresse_ffi` for the running platform and points JNA at it.
 * Android loads its `.so` from `jniLibs`; on desktop the library rides inside the jar
 * and has to reach the filesystem before the first FFI call.
 *
 * Every entry point into native code must go through [ensureLoaded] — not just
 * [TresseSync], which used to be the only one. [TresseIgnore] is constructible on its
 * own, and a host that compiles ignore rules before opening a sync would otherwise
 * hit an unresolved library.
 */
internal object TresseNative {
    private val loaded: Unit by lazy { configure() }

    fun ensureLoaded() = loaded

    private fun configure() {
        if (System.getProperty("uniffi.component.tresse_ffi.libraryOverride") != null) return
        val mapped = System.mapLibraryName("tresse_ffi").replace(".jnilib", ".dylib")
        val resource = "/native/${platform()}/$mapped"
        val input = javaClass.getResourceAsStream(resource)
            ?: error("Missing tresse native library: $resource")
        val target = Files.createTempFile("tresse-ffi-", mapped.substringAfterLast('.', ".bin")).toFile()
        target.deleteOnExit()
        input.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.setProperty("uniffi.component.tresse_ffi.libraryOverride", target.absolutePath)
    }

    private fun platform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val normalizedOs = when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("win") -> "windows"
            else -> error("Unsupported desktop OS: $os")
        }
        val normalizedArch = when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "arm64", "aarch64" -> "aarch64"
            else -> error("Unsupported desktop architecture: $arch")
        }
        return "$normalizedOs-$normalizedArch"
    }
}

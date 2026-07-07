val desktopNative = layout.buildDirectory.dir("generated/desktopNative")
val tresseRoot = file(System.getenv("TRESSE_DIR") ?: "../tresse")

val buildTresseDesktop by tasks.registering(Exec::class) {
    workingDir(tresseRoot)
    commandLine("cargo", "build", "--release", "-p", "tresse-ffi")
    inputs.files(
        fileTree(tresseRoot.resolve("tresse-ffi/src")),
        fileTree(tresseRoot.resolve("tresse-lib/src")),
    )
    outputs.files(
        tresseRoot.resolve("target/release/libtresse_ffi.dylib"),
        tresseRoot.resolve("target/release/libtresse_ffi.so"),
        tresseRoot.resolve("target/release/tresse_ffi.dll"),
    )
}

val copyTresseDesktopNative by tasks.registering(Copy::class) {
    dependsOn(buildTresseDesktop)
    from(tresseRoot.resolve("target/release")) {
        include("libtresse_ffi.dylib", "libtresse_ffi.so", "tresse_ffi.dll")
    }
    into(desktopNative.map { it.dir("native") })
}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.library)
}

kotlin {
    jvm("desktop")
    androidTarget()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation(libs.kotlinx.serialization)
        }
        val desktopMain by getting {
            resources.srcDir(desktopNative)
            dependencies { implementation("net.java.dev.jna:jna:5.14.0") }
        }
        val androidMain by getting {
            dependencies { implementation("net.java.dev.jna:jna:5.14.0@aar") }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    }

    android {
        namespace = "com.akergez.tresse.kmp"
        compileSdk = 36
        defaultConfig { minSdk = 24 }
    }
}

tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    dependsOn(copyTresseDesktopNative)
}

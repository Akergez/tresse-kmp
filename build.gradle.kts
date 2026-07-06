val desktopNative = layout.buildDirectory.dir("generated/desktopNative")

val buildTresseDesktop by tasks.registering(Exec::class) {
    workingDir(rootProject.file("tresse"))
    commandLine("cargo", "build", "--release", "-p", "tresse-ffi")
    inputs.files(
        fileTree(rootProject.file("tresse/tresse-ffi/src")),
        fileTree(rootProject.file("tresse/tresse-lib/src")),
    )
    outputs.files(
        rootProject.file("tresse/target/release/libtresse_ffi.dylib"),
        rootProject.file("tresse/target/release/libtresse_ffi.so"),
        rootProject.file("tresse/target/release/tresse_ffi.dll"),
    )
}

val copyTresseDesktopNative by tasks.registering(Copy::class) {
    dependsOn(buildTresseDesktop)
    from(rootProject.file("tresse/target/release")) {
        include("libtresse_ffi.dylib", "libtresse_ffi.so", "tresse_ffi.dll")
    }
    into(desktopNative.map { it.dir("native") })
}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.library)
}

kotlin {
    jvm("desktop")
    androidTarget()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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
        defaultConfig { minSdk = 26 }
    }
}

tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    dependsOn(copyTresseDesktopNative)
}

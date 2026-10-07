val desktopNativeResources = providers.environmentVariable("TRESSE_PREBUILT_NATIVE_DIR")
    .orNull
    ?.let(::file)
    ?: layout.projectDirectory.dir("src/desktopMain/resources").asFile

group = "com.akergez.tresse"
version = providers.gradleProperty("tresseVersion")
    .orElse(
        providers.environmentVariable("GITHUB_REF_TYPE")
            .filter { it == "tag" }
            .flatMap { providers.environmentVariable("GITHUB_REF_NAME") }
            .map { it.removePrefix("v") }
    )
    .orElse("0.1.0-SNAPSHOT")
    .get()

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("com.android.library") version "8.13.2"
    `maven-publish`
}

kotlin {
    jvm("desktop")
    androidTarget {
        publishLibraryVariants("release")
    }
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        }
        val desktopMain by getting {
            resources.srcDir(desktopNativeResources)
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

publishing {
    repositories {
        // GitHub Actions sets GITHUB_REPOSITORY ("owner/repo"); for a local publish pass
        // -PgithubRepository=owner/repo together with GITHUB_ACTOR and GITHUB_TOKEN.
        val githubRepository = providers.gradleProperty("githubRepository")
            .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
            .orNull
        if (githubRepository != null) {
            maven {
                name = "GitHubPackages"
                // GitHub Packages wants a lowercase owner in the Maven URL.
                url = uri("https://maven.pkg.github.com/${githubRepository.lowercase()}")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
}

import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

val desktopNativeResources = providers.environmentVariable("TRESSE_PREBUILT_NATIVE_DIR")
    .orNull
    ?.let(::file)
    ?: layout.projectDirectory.dir("src/desktopMain/resources").asFile

group = "com.akergez.tresse"
version = providers.gradleProperty("tresseVersion")
    .orElse(providers.environmentVariable("CI_COMMIT_TAG").map { it.removePrefix("v") })
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
        maven {
            name = "GitLab"
            val apiUrl = providers.environmentVariable("CI_API_V4_URL")
                .orElse("https://gitlab.com/api/v4")
                .get()
            val projectId = providers.environmentVariable("CI_PROJECT_ID")
                .orElse("84164267")
                .get()
            url = uri("$apiUrl/projects/$projectId/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                val jobToken = providers.environmentVariable("CI_JOB_TOKEN").orNull
                name = if (jobToken != null) "Job-Token" else "Private-Token"
                value = jobToken ?: providers.environmentVariable("GITLAB_TOKEN").orNull
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}

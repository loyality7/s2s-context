plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.s2s.context.local"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                // No explicit groupId/artifactId — JitPack's multi-module
                // convention derives them from the repo and this module's
                // directory name. See s2s-llm's build.gradle.kts comments for
                // why a custom override breaks resolution.
                version = project.findProperty("VERSION_NAME")?.toString() ?: "0.1.0"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":common"))
    api("com.github.loyality7:speech-to-speech-mobile:1.0.7")

    // No third-party dependency: android.database.sqlite (with FTS5, built
    // into the platform since API 11) is all this backend needs.

    testImplementation("junit:junit:4.13.2")
    // org.json's Android stub throws on the JVM; this is the upstream
    // implementation Android's is derived from, for JVM-testable JSON parsing.
    testImplementation("org.json:json:20240303")
    // android.database.sqlite is a platform stub in JVM unit tests — Robolectric
    // provides a real (if simulated) SQLite implementation so TranscriptStore/
    // SqliteMemoryRepository are testable without an emulator.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.s2s.context.common"
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
                version = project.findProperty("VERSION_NAME")?.toString() ?: "0.1.0"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The only dependency common should ever need: core's ContextEngine
    // contract. Nothing storage-specific belongs here.
    api("com.github.loyality7:speech-to-speech-mobile:1.0.4")

    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.library")
    `maven-publish`
}

group = "com.chatwithhal"
// Bumped by mobile-sdk/release.sh's check: the release tag must match.
version = providers.gradleProperty("halChatVersion").get()

android {
    namespace = "com.chatwithhal.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.chatwithhal"
            artifactId = "hal-chat"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}

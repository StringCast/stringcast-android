plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.stringcast.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.stringcast.sample"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":stringcast"))
}

// Example: push the app's strings.xml to StringCast before building (requires the Node CLI).
// Not wired into the build by default; run with `./gradlew :sample:stringcastPush`.
tasks.register<Exec>("stringcastPush") {
    group = "stringcast"
    description = "Uploads src/main/res string files with the stringcast CLI"
    workingDir = projectDir
    commandLine("npx", "stringcast", "push", "--platform", "android", "--res", "src/main/res")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.polyglot.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.polyglot.sample"
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
    implementation(project(":polyglot"))
}

// Example: push the app's strings.xml to Polyglot before building (requires the Node CLI).
// Not wired into the build by default; run with `./gradlew :sample:polyglotPush`.
tasks.register<Exec>("polyglotPush") {
    group = "polyglot"
    description = "Uploads src/main/res string files with the polyglot CLI"
    workingDir = projectDir
    commandLine("npx", "polyglot", "push", "--platform", "android", "--res", "src/main/res")
}

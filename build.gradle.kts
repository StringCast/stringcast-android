plugins {
    id("com.android.library") version "8.11.1" apply false
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // 0.35.0 is the newest release that runs on Gradle 8.x (0.36+ require Gradle 9).
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}

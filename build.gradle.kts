plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Additional repositories that AndroidUSBCamera may need
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    }
}
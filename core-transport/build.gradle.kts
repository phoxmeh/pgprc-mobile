plugins {
    // AGP 9+ has built-in Kotlin support for Android modules.
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.packetradio.mobile.transport"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.usb.serial.android)
    implementation(libs.sshj)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (mik3y) is published via JitPack, not Maven Central.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "packet-radio-android"

include(":app")
include(":core-model")
include(":core-protocol")
include(":core-transport")
include(":core-data")

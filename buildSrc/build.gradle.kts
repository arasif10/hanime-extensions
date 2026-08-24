plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.2.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.22")
    implementation("org.jetbrains.kotlin:kotlin-serialization:1.8.22")
    implementation("org.jmailen.gradle:kotlinter-gradle:3.15.0")
}
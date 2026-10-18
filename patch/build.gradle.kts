val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra

plugins {
    id("java-library")
    alias(npatch.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
}

dependencies {
    implementation("top.nkbe:NeoApk:1.0.2")
    implementation(projects.share.java)
    implementation("vector:axml")

    implementation(npatch.commons.io)
    implementation(npatch.beust.jcommander)
    testImplementation("junit:junit:4.13.2")
    implementation(npatch.google.gson)
}

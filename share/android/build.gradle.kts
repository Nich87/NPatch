plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "top.nkbe.npatch.share"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }
}

dependencies {
    implementation(projects.share.java)
    implementation("vector:daemon-service")
}

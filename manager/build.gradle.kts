import java.util.Base64
import java.util.Locale
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder

val defaultManagerPackageName: String by rootProject.extra
val apiCode: Int by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val coreVerCode: Int by rootProject.extra
val coreVerName: String by rootProject.extra

fun decodeSha256Hex(value: String): ByteArray {
    require(value.length == 64) { "Manager signature digest must be 64 hex chars: $value" }
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun encodeAllowlistEntry(value: String): String {
    val key = 0x5A
    val obfuscated = decodeSha256Hex(value).map { byte -> (byte.toInt() xor key).toByte() }.toByteArray()
    return Base64.getEncoder().encodeToString(obfuscated)
}

plugins {
    alias(libs.plugins.agp.app)
    alias(npatch.plugins.kotlin.android)
    alias(npatch.plugins.compose.compiler)
    alias(npatch.plugins.google.devtools.ksp)
    alias(npatch.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
            "-opt-in=androidx.lifecycle.compose.ExperimentalLifecycleComposeApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

android {
    defaultConfig {
        applicationId = defaultManagerPackageName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val managerSignatureAllowlist = (
            System.getenv("NPATCH_MANAGER_SIGNATURE_SHA256")
                ?: project.findProperty("npatchManagerSignatureSha256")?.toString()
                ?: listOf(
                    "DB73788534AFFC4BFA3AE16040A2D3A2C2B63EDEA1E07F3A1CF9AFF4DD0995A8",
                    "A1F6D52645F53DB0995EDB6DD1C61071B0CA66C281C68ED20B0DEC7268E14A2C",
                ).joinToString(",")
            )
            .split(',', ';', ' ', '\n', '\r', '\t')
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",") { encodeAllowlistEntry(it) }
        buildConfigField("String", "MANAGER_SIGNATURE_SHA256_ALLOWLIST", "\"$managerSignatureAllowlist\"")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            excludes += "lib/*/libandroidx.graphics.path.so"
            excludes += "lib/*/libdatastore_shared_counter.so"
        }
        resources {
            excludes += "kotlin/**"
            excludes += "META-INF/androidx*"
            excludes += "META-INF/androidx/**"
            excludes += "DebugProbesKt.bin"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // 启用 R8/ProGuard 进行代码压缩、优化和混淆。
            isShrinkResources = true    // 启用资源缩减，移除未被引用的资源文件。
            isDebuggable = false        // 发布版本禁止调试。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        all {
            sourceSets[name].assets.srcDirs(rootProject.projectDir.resolve("out/assets/$name"))
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    namespace = "top.nkbe.npatch"

}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }

    onVariants { variant ->
        val variantLowered = variant.name.lowercase()
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

        val copyAssetsTaskProvider = tasks.register<Copy>("copy${variantCapped}Assets") {
            dependsOn(":meta-loader:copy$variantCapped")
            dependsOn(":patch-loader:copy$variantCapped")

            val targetDir = layout.buildDirectory.dir("intermediates/assets/$variantLowered/merge${variantCapped}Assets")
            doFirst {
                delete(targetDir.map { it.file("npatch/loader.dex") })
            }
            into(targetDir)

            from("${rootProject.projectDir}/out/assets/${variant.name}")
        }

        tasks.configureEach {
            if (name == "merge${variantCapped}Assets") {
                dependsOn(copyAssetsTaskProvider)
            }
        }

        tasks.register<Copy>("build$variantCapped") {
            dependsOn("assemble$variantCapped")
            from(variant.artifacts.get(SingleArtifact.APK))
            into("${rootProject.projectDir}/out/$variantLowered")
            rename(".*.apk", "NPatch-v$verName-$verCode-$variantLowered.apk")
        }
    }
}

dependencies {
    implementation("com.android.tools.build:apksig:8.0.2")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform(npatch.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(projects.patch)
    implementation(projects.share.android)
    implementation(projects.share.java)
    implementation("vector:daemon-service")

    implementation(platform(npatch.androidx.compose.bom))
    implementation(npatch.androidx.activity.compose)
    implementation(npatch.androidx.compose.material.icons.extended)
    implementation(npatch.androidx.compose.material3)
    implementation(npatch.androidx.compose.ui)
    implementation(npatch.androidx.compose.ui.tooling.preview)
    implementation(npatch.androidx.core.ktx)
    implementation(libs.material)
    implementation(npatch.androidx.datastore.preferences)
    implementation(npatch.coil.compose)
    implementation(libs.gson)
    implementation(npatch.androidx.lifecycle.viewmodel.compose)
    implementation(npatch.androidx.navigation3.runtime)
    implementation(npatch.androidx.navigation3.ui)
    implementation(libs.androidx.preference)
    implementation(npatch.androidx.room.ktx)
    implementation(npatch.androidx.room.runtime)
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2")
    // Split APK merging (REAndroid) — merges base + config splits into a single APK
    implementation("com.github.REAndroid:arsclib:a28c6fb2a7")

    implementation(npatch.rikka.shizuku.api)
    implementation(npatch.rikka.shizuku.provider)
    implementation(npatch.rikka.refine)
    implementation(libs.appiconloader)
    implementation(libs.hiddenapibypass)

    implementation(npatch.coui)
    implementation(npatch.couiPreference)
    implementation(npatch.couiIcons)


    annotationProcessor(npatch.androidx.room.compiler)
    compileOnly(npatch.rikka.hidden.stub)
    ksp(npatch.androidx.room.compiler)

    debugImplementation(npatch.androidx.compose.ui.tooling)
    debugImplementation(npatch.androidx.customview)
    debugImplementation(npatch.androidx.customview.poolingcontainer)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hivecore"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hivecore"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }

        // Spec section 19: every phase lands behind a feature flag that defaults off until its phone gate passed.
        // Build a flagged APK for a gate with  -PhiveFeatures=mesh,runtime,split,graph,remote,state,cloud,hardening  (or all).
        val enabled = (project.findProperty("hiveFeatures") as String?).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun flag(name: String, key: String) = buildConfigField("boolean", name, (key in enabled || "all" in enabled).toString())
        flag("FEATURE_MESH_TRUST", "mesh")        // phase 1: LAN/Nearby mesh, Noise sessions, SAS pairing, roster
        flag("FEATURE_RUNTIME", "runtime")        // phase 2: GenieX model pool, catalog, BENCH, worker service
        flag("FEATURE_SPLIT", "split")            // phases 3-4: split engine over the mesh, placement, Jobs grid
        flag("FEATURE_GRAPH", "graph")            // phase 5: planner tiers, compiler, owner consent, Ask plan panel
        flag("FEATURE_REMOTE", "remote")          // phase 6: relay, WebRTC blobs, link class, invite links
        flag("FEATURE_STATE", "state")            // phase 7: journal, memo, RECLAIM, resume, ledger receipts
        flag("FEATURE_CLOUD", "cloud")            // phase 8: escalation gate, T2 planner (orchestrator only)
        flag("FEATURE_HARDENING", "hardening")    // phase 9: re-cut, deputy, store-and-forward, soak
        flag("FEATURE_DECIDE", "decide")          // decision model at the chunking stage (docs/plans/decision-chunking.md)
        // Demo builds only (-PhiveDemoThermal): relaxed thermal limits for sharing (Eligibility.relaxForDemo).
        buildConfigField("boolean", "DEMO_THERMAL", (project.findProperty("hiveDemoThermal") != null).toString())
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // GenieX needs extracted native libraries. One QNN runtime for the whole app: QAIRT 2.50.0 in src/main/jniLibs
    // replaces GenieX's bundled 2.45 copies; libonnxruntime.so there is ORT 1.29.0 with the QNN EP.
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += listOf(
            "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpPrepare.so", "libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so",
            "libQnnHtpV79Skel.so", "libQnnHtpV79Stub.so", "libonnxruntime.so"
        ).map { "lib/arm64-v8a/$it" }
        resources.excludes += setOf(
            "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/io.netty.versions.properties",
            "META-INF/LICENSE*", "META-INF/NOTICE*"
        )
    }

    androidResources {
        noCompress += listOf("onnx", "bin", "gguf", "pdf", "docx", "pptx", "csv")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":mesh"))
    implementation(project(":trust"))
    implementation(project(":runtime"))
    implementation(project(":engine"))
    implementation(project(":planner"))
    implementation(project(":node"))
    implementation(project(":cloud"))
    implementation(project(":ledger"))
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
}

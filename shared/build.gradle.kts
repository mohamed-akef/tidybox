plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

// Pure engine. No I/O, no network, no DB, no clock — see docs/specs/2026-09-16-design.md §1.
// The only dependency is JSON parsing for rule packs and fixtures. Auditing the privacy claim
// starts here: nothing below can open a socket.
kotlin {
    jvm()
    androidLibrary {
        namespace = "co.raseed.engine"
        compileSdk = 37
        minSdk = 26
    }
    // iosArm64() is added when the iOS app exists; commonMain does not change.

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// rulepacks/merchants.json is the single source of the bundled merchant pack. Compile it in as a
// string constant so commonMain needs no resource loading on any platform.
val rulepackSrc = rootProject.file("rulepacks/merchants.json")
val generatedDir = layout.buildDirectory.dir("generated/rulepack/commonMain/kotlin")
val generateBundledRulePack by tasks.registering {
    inputs.file(rulepackSrc)
    outputs.dir(generatedDir)
    doLast {
        val out = generatedDir.get().file("co/raseed/engine/BundledRulePack.kt").asFile
        out.parentFile.mkdirs()
        val body = rulepackSrc.readText().replace("$", "\${'$'}")
        out.writeText("package co.raseed.engine\n\n// GENERATED from rulepacks/merchants.json — edit that file, not this one.\ninternal const val BUNDLED_RULEPACK_JSON: String = \"\"\"$body\"\"\"\n")
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateBundledRulePack) }

// The golden corpus is read at runtime from ../fixtures; declare it so edits re-run the tests.
tasks.named<Test>("jvmTest") {
    inputs.file("../fixtures/corpus.jsonl")
}

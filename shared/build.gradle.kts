plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

// Pure engine. No I/O, no network, no DB, no clock — see docs/specs/2026-09-16-design.md §1.
// The only dependency is JSON parsing for rule packs and fixtures. Auditing the privacy claim
// starts here: nothing below can open a socket.
kotlin {
    jvm()
    // androidTarget() and iosArm64() are added when their apps exist; commonMain does not change.

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

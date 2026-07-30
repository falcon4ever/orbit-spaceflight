/*
 * Copyright 2026 Laurence Muller
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    js {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    // Mirrors orbit-core's target list so the addon never constrains an Orbit user's platform set
    macosArm64()
    iosSimulatorArm64()
    iosArm64()
    linuxX64()
    linuxArm64()
    watchosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    tvosSimulatorArm64()
    tvosArm64()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()
    mingwX64()
    watchosDeviceArm64()
    iosX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("org.orbitmvi.orbit.annotation.OrbitExperimental")
            // OrbitInternal is deliberately NOT opted in module-wide: the coupling to Orbit
            // internals is confined to Retrograde.kt and TimeTravelContainerDecorator.kt via
            // file-level @OptIn, so any new internal-API usage is a visible, reviewed choice
        }

        commonMain.dependencies {
            api(libs.orbitCore)
            api(libs.kotlinCoroutines)
            // The wire protocol is public API
            api(libs.kotlinxSerializationJson)
        }
        // Tests drive containers through orbit {} directly, which is @OrbitInternal
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("org.orbitmvi.orbit.annotation.OrbitInternal")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinCoroutinesTest)
            implementation(libs.turbine)
        }
    }
}

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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DemoShared"
            isStatic = true
        }
    }

    android {
        namespace = "net.multigesture.spaceflight.demo.shared"
        compileSdk = 37
        minSdk = 24

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("org.orbitmvi.orbit.annotation.OrbitExperimental")
        }

        androidMain.dependencies {
            implementation(libs.androidxCoreKtx)
        }

        commonMain.dependencies {
            api(project(":orbit-spaceflight"))
            api(project(":orbit-spaceflight-logging"))
            api(libs.orbitCore)
            implementation(libs.orbitViewmodel)
            implementation(libs.orbitCompose)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            implementation(libs.jbLifecycleViewmodelCompose)
            implementation(libs.androidxNav3Runtime)
            implementation(libs.jbNav3Ui)
            implementation(libs.jbLifecycleViewmodelNav3)
            implementation(libs.kotlinxSerializationCore)
        }
    }
}

// Demo code compiles against alpha navigation APIs; don't fail on their warnings
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.allWarningsAsErrors = false
}

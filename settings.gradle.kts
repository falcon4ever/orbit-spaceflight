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

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "orbit-spaceflight-root"

include(":orbit-spaceflight")
include(":orbit-spaceflight-logging")
include(":orbit-spaceflight-noop")
include(":benchmarks")

project(":orbit-spaceflight").projectDir = file("spaceflight-core")
project(":orbit-spaceflight-logging").projectDir = file("spaceflight-logging")
project(":orbit-spaceflight-noop").projectDir = file("spaceflight-noop")

// The Android library and the demo need the Android SDK; skip them on machines and runners
// without one, so the multiplatform library build stays SDK-free
val androidSdkPresent = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (androidSdkPresent) {
    include(":orbit-spaceflight-android")
    include(":demo:shared")
    include(":demo:androidApp")
    include(":demo:desktopApp")
    // Mission Control dev harness embeds the demo app, so it shares the SDK gate
    include(":tools:mission-control")

    project(":orbit-spaceflight-android").projectDir = file("spaceflight-android")
} else {
    logger.lifecycle("Android SDK not found - skipping :orbit-spaceflight-android, :demo and :tools modules")
}

// Until the observer SPI is published upstream, build against the local orbit-mvi checkout
// (branch feature/orbit-core-observer-spi) via dependency substitution.
includeBuild("../orbit-mvi") {
    dependencySubstitution {
        substitute(module("org.orbit-mvi:orbit-core")).using(project(":orbit-core"))
        substitute(module("org.orbit-mvi:orbit-viewmodel")).using(project(":orbit-viewmodel"))
        substitute(module("org.orbit-mvi:orbit-compose")).using(project(":orbit-compose"))
    }
}

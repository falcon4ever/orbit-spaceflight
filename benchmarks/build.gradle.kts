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

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinAllopen)
    alias(libs.plugins.kotlinxBenchmark)
}

dependencies {
    implementation(project(":orbit-spaceflight"))
    implementation(libs.kotlinCoroutines)
    implementation(libs.kotlinxBenchmarkRuntime)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "org.orbitmvi.orbit.annotation.OrbitInternal",
            "org.orbitmvi.orbit.annotation.OrbitExperimental",
        )
    }
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets {
        register("main")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "us"
        }
    }
}

tasks.register<JavaExec>("frameSim") {
    group = "benchmark"
    description = "Synthetic frame-loop timing comparison, recorder on vs off"
    mainClass.set("net.multigesture.spaceflight.benchmarks.FrameTimingSimulationKt")
    classpath = sourceSets["main"].runtimeClasspath
}

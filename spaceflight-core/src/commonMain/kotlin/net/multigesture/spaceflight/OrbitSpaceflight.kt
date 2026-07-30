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

package net.multigesture.spaceflight

import kotlin.concurrent.atomics.AtomicReference

/**
 * Orbit Spaceflight entry point.
 *
 * Install once at application startup, then wire the recorder's observer into Orbit:
 *
 * ```
 * val recorder = OrbitSpaceflight.install {
 *     capacity = 2_000
 *     exclude("CountdownTimer")
 * }
 * Orbit.configureDefaults {
 *     eventObserver = recorder.eventObserver
 * }
 * ```
 */
public object OrbitSpaceflight {

    private val installed = AtomicReference<FlightRecorder?>(null)

    /** The installed recorder, or `null` when not installed. */
    public val recorder: FlightRecorder? get() = installed.load()

    /**
     * Creates and installs the global [FlightRecorder].
     *
     * @throws IllegalStateException when already installed; call [uninstall] first (tests).
     */
    public fun install(configure: FlightRecorderConfig.Builder.() -> Unit = {}): FlightRecorder {
        val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply(configure).build())
        check(installed.compareAndSet(null, recorder)) { "OrbitSpaceflight is already installed" }
        return recorder
    }

    /**
     * Installs like [install] unless a recorder already exists (including one installed by a
     * racing caller), in which case nothing happens and `null` is returned. The race-safe
     * variant behind [Spaceflight.install].
     */
    public fun installIfAbsent(configure: FlightRecorderConfig.Builder.() -> Unit = {}): FlightRecorder? {
        if (installed.load() != null) return null
        val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply(configure).build())
        return if (installed.compareAndSet(null, recorder)) recorder else null
    }

    /** Removes the installed recorder. Containers holding its observer keep reporting to it. */
    public fun uninstall() {
        installed.store(null)
    }
}

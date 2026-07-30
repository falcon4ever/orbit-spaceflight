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

import org.orbitmvi.orbit.ContainerDecoration
import kotlin.concurrent.atomics.AtomicReference
import org.orbitmvi.orbit.observer.OrbitEventObserver

/**
 * The **entry API**: the only Spaceflight surface application code should touch from shared
 * (all-variant) source sets.
 *
 * It is deliberately tiny because `orbit-spaceflight-noop` mirrors it exactly, letting release
 * variants depend on the no-op twin so recorder code is *absent* from public builds rather
 * than merely disabled:
 *
 * ```kotlin
 * debugImplementation("io.github.falcon4ever:orbit-spaceflight:x")
 * releaseImplementation("io.github.falcon4ever:orbit-spaceflight-noop:x")
 * ```
 *
 * Shared code then compiles against this object in every variant:
 *
 * ```kotlin
 * Spaceflight.install(capacity = 300)
 * Orbit.configureDefaults {
 *     eventObserver = Spaceflight.observer()          // null in release
 *     containerDecoration = Spaceflight.decoration()  // null in release
 * }
 * if (Spaceflight.isAvailable) { /* show the "Share debug log" entry */ }
 * ```
 *
 * Development tooling (Mission Control's harness, tests, benchmarks) uses the richer
 * [OrbitSpaceflight]/[FlightRecorder]/[Retrograde] API directly instead.
 */
public object Spaceflight {

    /** True in the real artifact, false in the no-op twin — gate share/debug UI on this. */
    public const val isAvailable: Boolean = true

    private val installedRetrograde: AtomicReference<Retrograde?> = AtomicReference(null)

    /**
     * Installs the recorder (and time travel) once. Repeat calls are ignored, so it is safe
     * to call from `Application.onCreate` and again from tests.
     */
    public fun install(
        capacity: Int = FlightRecorderConfig.DEFAULT_CAPACITY,
        minRetainedReductionsPerContainer: Int = FlightRecorderConfig.DEFAULT_MIN_RETAINED_REDUCTIONS,
        excludedContainerNames: Set<String> = emptySet(),
    ) {
        // This is the one entry point meant to be called blindly from app startup paths, so
        // a racing second call must be a no-op, never an exception: losing the install race
        // is indistinguishable from calling after someone else installed
        val recorder = OrbitSpaceflight.installIfAbsent {
            this.capacity = capacity
            this.minRetainedReductionsPerContainer = minRetainedReductionsPerContainer
            exclude(*excludedContainerNames.toTypedArray())
        } ?: return
        installedRetrograde.compareAndSet(null, Retrograde(recorder))
    }

    /** The observer to hand to `Orbit.configureDefaults`, or null when not installed. */
    public fun observer(): OrbitEventObserver? = OrbitSpaceflight.recorder?.eventObserver

    /** The time-travel decoration to hand to `Orbit.configureDefaults`, or null. */
    public fun decoration(): ContainerDecoration? = installedRetrograde.load()?.containerDecoration

    /** Drops all retained events. */
    public fun clearRecording() {
        OrbitSpaceflight.recorder?.clear()
    }

    /** How many events are currently retained; 0 when not installed. */
    public fun retainedEventCount(): Int = OrbitSpaceflight.recorder?.snapshot()?.events?.size ?: 0
}

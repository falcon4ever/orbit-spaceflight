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
import org.orbitmvi.orbit.observer.OrbitEventObserver

/**
 * No-op twin of the Spaceflight entry API, for release variants:
 *
 * ```kotlin
 * debugImplementation("io.github.falcon4ever:orbit-spaceflight:x")
 * releaseImplementation("io.github.falcon4ever:orbit-spaceflight-noop:x")
 * ```
 *
 * Shared application code compiles unchanged, but public builds contain **no recorder code
 * at all** — not a disabled recorder, an absent one. Every function here does nothing and
 * [isAvailable] is a compile-time `false`, so `if (Spaceflight.isAvailable) { … }` blocks
 * fold away entirely.
 *
 * The signature list is verified against the real artifact by a parity test on both sides
 * (see `spaceflight-entry-api.txt`).
 */
public object Spaceflight {

    /** Always false here: this build has no recorder. */
    public const val isAvailable: Boolean = false

    public fun install(
        capacity: Int = 0,
        minRetainedReductionsPerContainer: Int = 0,
        excludedContainerNames: Set<String> = emptySet(),
    ) {
        // no-op
    }

    public fun observer(): OrbitEventObserver? = null

    public fun decoration(): ContainerDecoration? = null

    public fun clearRecording() {
        // no-op
    }

    public fun retainedEventCount(): Int = 0
}

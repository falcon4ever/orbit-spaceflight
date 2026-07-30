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

import org.orbitmvi.orbit.observer.IntentResult

/**
 * A recorded Orbit event.
 *
 * Events are totally ordered by [seq], assigned under the recorder's lock — the one global
 * order in a concurrent world. State and side effect references are the live objects from the
 * producing container; Orbit's immutability contract is what makes the recording truthful.
 *
 * @property seq global sequence number; unique and monotonically increasing per recorder.
 * The synthetic gap [Diagnostic] uses seq -1 (see [FlightRecording]).
 * @property timeMillis wall-clock time the event was recorded.
 * @property containerId the container the event belongs to, or `null` for recorder-level
 * diagnostics.
 */
public sealed interface SpaceflightEvent {
    public val seq: Long
    public val timeMillis: Long
    public val containerId: Long?

    /** A container was created. */
    public data class ContainerAttached(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long,
        val name: String?,
        val initialState: Any,
    ) : SpaceflightEvent

    /** A container's scope completed. */
    public data class ContainerDetached(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long,
    ) : SpaceflightEvent

    /** An intent started executing. */
    public data class IntentDispatched(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long,
        val intentId: Long,
        val name: String?,
    ) : SpaceflightEvent

    /** An intent finished executing. */
    public data class IntentCompleted(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long,
        val intentId: Long,
        val result: IntentResult,
    ) : SpaceflightEvent

    /**
     * A state reduction. [noOp] marks reductions where [oldState] equals [newState], which the
     * container's state flow deduplicates — useful for display-time filtering.
     */
    public data class Reduction(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long,
        val intentId: Long?,
        val oldState: Any,
        val newState: Any,
        val noOp: Boolean,
    ) : SpaceflightEvent

    /** A posted side effect. */
    public data class SideEffect(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long,
        val intentId: Long?,
        val value: Any,
    ) : SpaceflightEvent

    /** A recorder-level diagnostic: eviction gaps, drops, renderer errors. */
    public data class Diagnostic(
        override val seq: Long,
        override val timeMillis: Long,
        override val containerId: Long?,
        val message: String,
    ) : SpaceflightEvent
}

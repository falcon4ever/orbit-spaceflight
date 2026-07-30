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

import kotlinx.coroutines.flow.StateFlow

/**
 * Retrograde's control surface, consumed by overlays, servers and tests alike.
 *
 * Time travel is playback, not re-execution: stepping substitutes recorded state snapshots
 * into the containers' displayed flows. Nothing re-runs, so rewinding can never re-trigger a
 * network call. While inspecting, in-flight intents keep mutating real state underneath
 * (the live tail, still recorded); new intents are queued and side effects are held, both
 * delivered on [resume].
 */
public interface TimeTravel {

    public val state: StateFlow<TimeTravelState>

    /** Freezes all containers' displayed state and detaches the cursor for navigation. */
    public fun inspect()

    /** Snaps displayed state back to live, dispatches queued intents, releases held side effects. */
    public fun resume()

    public fun stepBackward()

    public fun stepForward()

    /** Moves before the earliest retained reduction: every container shows its oldest known state. */
    public fun moveToStart()

    /** Moves to the newest recorded reduction (the live tail keeps extending it). */
    public fun moveToEnd()

    /** Moves the cursor to the reduction with the given [seq] (or the nearest one before it). */
    public fun seekTo(seq: Long)

    /** Clears the recording. */
    public fun clear()
}

public enum class TimeTravelMode {
    /** Recording; displayed state mirrors real state. */
    LIVE,

    /** Cursor navigation active; displayed state is driven by recorded snapshots. */
    INSPECTING,
}

/**
 * @property mode current mode.
 * @property cursorSeq the reduction the cursor sits on, or null when [mode] is LIVE.
 * @property cursorPosition 1-based position of the cursor among retained reductions;
 * 0 means before all of them (at start).
 * @property reductionCount number of retained reductions at the last navigation.
 */
public data class TimeTravelState(
    public val mode: TimeTravelMode = TimeTravelMode.LIVE,
    public val cursorSeq: Long? = null,
    public val cursorPosition: Int = 0,
    public val reductionCount: Int = 0,
)

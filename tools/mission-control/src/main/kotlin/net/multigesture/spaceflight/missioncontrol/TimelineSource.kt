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

package net.multigesture.spaceflight.missioncontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.FlightRecording
import net.multigesture.spaceflight.RecordedContainer
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.TimeTravel
import net.multigesture.spaceflight.protocol.CAPABILITY_RECORDING
import net.multigesture.spaceflight.protocol.CAPABILITY_TIME_TRAVEL

/**
 * Where Mission Control gets its timeline (and optional time travel) from.
 *
 * [InProcessTimelineSource] feeds the dev harness (demo app in the same JVM);
 * [SocketTimelineSource] attaches to a separately running app over the wire protocol.
 * The UI is identical over both.
 */
interface TimelineSource {
    val label: String
    val recording: StateFlow<FlightRecording>
    val liveContainers: StateFlow<List<RecordedContainer>>
    val timeTravel: TimeTravel?

    /** Non-fatal problems worth showing, e.g. a protocol version gap. */
    val warning: StateFlow<String?> get() = NoWarning

    /**
     * Features the peer actually advertises. Gate UI on these rather than on protocol
     * version numbers: capabilities survive version skew in both directions.
     */
    val capabilities: StateFlow<Set<String>> get() = LocalCapabilities

    /**
     * The derived external (before, after) states for a reduction, or null when the
     * container's transform is identity. In-process they are derived on demand; over the
     * wire the producing app rendered them at send time.
     */
    fun externalStatesOf(event: SpaceflightEvent.Reduction): Pair<Any, Any>?

    fun clear()

    fun close() {}
}

private val NoWarning: StateFlow<String?> = MutableStateFlow(null)

/** In-process sources are always fully capable - there is no peer to negotiate with. */
private val LocalCapabilities: StateFlow<Set<String>> = MutableStateFlow(setOf(CAPABILITY_RECORDING, CAPABILITY_TIME_TRAVEL))

class InProcessTimelineSource(
    private val recorder: FlightRecorder,
    private val retrograde: Retrograde? = null,
    scope: CoroutineScope,
) : TimelineSource {

    override val label: String = "In-process demo"
    override val recording = MutableStateFlow(FlightRecording(emptyList(), 0))
    override val liveContainers = MutableStateFlow(emptyList<RecordedContainer>())
    override val timeTravel: TimeTravel? get() = retrograde

    private val job = scope.launch {
        // Event-driven: the recorder's revision bumps on every append/clear, so a quiet app
        // costs nothing and a burst coalesces (StateFlow conflates)
        recorder.revision.collect {
            recording.value = recorder.snapshot()
            liveContainers.value = recorder.liveContainers()
        }
    }

    override fun externalStatesOf(event: SpaceflightEvent.Reduction): Pair<Any, Any>? {
        val external = retrograde?.externalStateOf(event.containerId, event.newState) ?: return null
        if (external == event.newState) return null
        val externalBefore = retrograde.externalStateOf(event.containerId, event.oldState) ?: return null
        return externalBefore to external
    }

    override fun clear() {
        recorder.clear()
        recording.value = recorder.snapshot()
    }

    override fun close() {
        job.cancel()
    }
}

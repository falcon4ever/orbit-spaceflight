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

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.FlightRecording
import net.multigesture.spaceflight.RecordedContainer
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.TimeTravel
import net.multigesture.spaceflight.protocol.CAPABILITY_RECORDING
import net.multigesture.spaceflight.protocol.RemoteIntentFailure
import net.multigesture.spaceflight.session.OrbitSession
import net.multigesture.spaceflight.session.SessionEvent
import net.multigesture.spaceflight.session.SessionEventType
import net.multigesture.spaceflight.session.flatten
import net.multigesture.spaceflight.session.readSession

/**
 * Session mode: a `.orbitsession` file rendered through the same timeline UI as a live
 * attachment. Read-only — the producing app is gone, so there is no time travel to drive
 * (and [timeTravel] is null, which hides the transport bar).
 *
 * Value trees are flattened back to data-class-style strings so the existing diff rendering
 * works unchanged on session files.
 */
class SessionTimelineSource(private val file: File) : TimelineSource {

    private val session: OrbitSession = readSession(file)

    override val label: String = buildString {
        append(file.name)
        append(" — ${session.app.name} (${session.app.platform}, format v${session.formatVersion})")
        append(" — exported ${formatEventDateTime(session.app.exportedAtMillis)}")
    }

    override val recording = MutableStateFlow(
        FlightRecording(
            events = session.events.map { it.toEvent() },
            droppedEvents = session.droppedEvents,
        )
    )

    override val liveContainers = MutableStateFlow(
        session.containers.map { RecordedContainer(it.containerId, it.name, attachedAtMillis = 0) }
    )

    override val timeTravel: TimeTravel? = null
    override val capabilities = MutableStateFlow(setOf(CAPABILITY_RECORDING))

    private val externals: Map<Long, Pair<Any, Any>> = session.events
        .mapNotNull { event ->
            val before = event.externalOldState?.flatten()
            val after = event.externalNewState?.flatten()
            if (before != null && after != null) event.seq to (before as Any to after as Any) else null
        }
        .toMap()

    override fun externalStatesOf(event: SpaceflightEvent.Reduction): Pair<Any, Any>? = externals[event.seq]

    /** Session files are immutable history; clearing is meaningless here. */
    override fun clear() = Unit
}

private fun SessionEvent.toEvent(): SpaceflightEvent = when (eventType) {
    SessionEventType.ATTACHED -> SpaceflightEvent.ContainerAttached(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        name = name, initialState = value?.flatten() ?: "",
    )
    SessionEventType.DETACHED -> SpaceflightEvent.ContainerDetached(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
    )
    SessionEventType.INTENT_DISPATCHED -> SpaceflightEvent.IntentDispatched(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        intentId = intentId ?: -1, name = name,
    )
    SessionEventType.INTENT_COMPLETED -> SpaceflightEvent.IntentCompleted(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1, intentId = intentId ?: -1,
        result = when (val outcome = result) {
            null, "completed" -> IntentResult.Completed
            "cancelled" -> IntentResult.Cancelled
            else -> IntentResult.Failed(RemoteIntentFailure(outcome.removePrefix("failed: ")))
        },
    )
    SessionEventType.REDUCTION -> SpaceflightEvent.Reduction(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1, intentId = intentId,
        oldState = oldState?.flatten() ?: "", newState = newState?.flatten() ?: "", noOp = noOp,
    )
    SessionEventType.SIDE_EFFECT -> SpaceflightEvent.SideEffect(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1, intentId = intentId,
        value = value?.flatten() ?: "",
    )
    SessionEventType.DIAGNOSTIC -> SpaceflightEvent.Diagnostic(
        seq = seq, timeMillis = timeMillis, containerId = containerId, message = message.orEmpty(),
    )
}


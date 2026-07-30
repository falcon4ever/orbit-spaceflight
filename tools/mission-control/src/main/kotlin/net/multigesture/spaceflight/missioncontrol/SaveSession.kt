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
import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.session.OrbitSession
import net.multigesture.spaceflight.session.SESSION_FORMAT_VERSION
import net.multigesture.spaceflight.session.SessionApp
import net.multigesture.spaceflight.session.SessionContainer
import net.multigesture.spaceflight.session.SessionEvent
import net.multigesture.spaceflight.session.SessionEventType
import net.multigesture.spaceflight.session.ValueNode
import net.multigesture.spaceflight.session.redacted
import net.multigesture.spaceflight.session.writeTo

/**
 * Writes the currently displayed timeline to a `.orbitsession` file — the second producer of
 * the format (the first being an app's own export).
 *
 * Mission Control only ever holds states as *rendered* values: strings from the wire, or
 * value trees flattened from a session file. They are re-wrapped as scalar [ValueNode]s here
 * rather than re-parsed into trees, so saving is lossless with respect to what this client
 * actually received. Redaction runs again on the way out — an app that redacted already is
 * unaffected, and one that predates redaction gets it retroactively.
 */
fun TimelineSource.saveSessionTo(file: File) {
    val recording = recording.value

    fun node(value: Any?, name: String): ValueNode? =
        value?.let { ValueNode(name = name, value = it.toString()).redacted() }

    val events = recording.events.map { event ->
        when (event) {
            is SpaceflightEvent.ContainerAttached -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.ATTACHED,
                containerId = event.containerId, name = event.name,
                value = node(event.initialState, "initialState"),
            )
            is SpaceflightEvent.ContainerDetached -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.DETACHED,
                containerId = event.containerId,
            )
            is SpaceflightEvent.IntentDispatched -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.INTENT_DISPATCHED,
                containerId = event.containerId, intentId = event.intentId, name = event.name,
            )
            is SpaceflightEvent.IntentCompleted -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.INTENT_COMPLETED,
                containerId = event.containerId, intentId = event.intentId,
                result = when (val result = event.result) {
                    is IntentResult.Completed -> "completed"
                    is IntentResult.Cancelled -> "cancelled"
                    is IntentResult.Failed -> "failed: ${result.exception}"
                },
            )
            is SpaceflightEvent.Reduction -> {
                val external = externalStatesOf(event)
                SessionEvent(
                    seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.REDUCTION,
                    containerId = event.containerId, intentId = event.intentId, noOp = event.noOp,
                    oldState = node(event.oldState, "oldState"),
                    newState = node(event.newState, "newState"),
                    externalOldState = external?.let { node(it.first, "externalOldState") },
                    externalNewState = external?.let { node(it.second, "externalNewState") },
                )
            }
            is SpaceflightEvent.SideEffect -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.SIDE_EFFECT,
                containerId = event.containerId, intentId = event.intentId,
                value = node(event.value, "sideEffect"),
            )
            is SpaceflightEvent.Diagnostic -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.DIAGNOSTIC,
                containerId = event.containerId, message = event.message,
            )
        }
    }

    OrbitSession(
        formatVersion = SESSION_FORMAT_VERSION,
        app = SessionApp(
            name = label,
            platform = "mission-control",
            exportedAtMillis = System.currentTimeMillis(),
        ),
        containers = liveContainers.value.map { SessionContainer(it.containerId, it.name) },
        events = events,
        droppedEvents = recording.droppedEvents,
    ).writeTo(file)
}

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

package net.multigesture.spaceflight.session

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.SpaceflightEvent

/**
 * Exports a consistent, rendered, **redacted** snapshot of the recording. Rendering and
 * redaction happen here, at export time — never on the recording hot path. There is no
 * unredacted export: the built-in field-name policy always applies, [redactor] only adds.
 */
public fun FlightRecorder.exportSession(
    appName: String,
    platform: String,
    exportedAtMillis: Long,
    redactor: SessionRedactor? = null,
    caps: RenderCaps = RenderCaps(),
    externalStates: ((SpaceflightEvent.Reduction) -> Pair<Any, Any>?)? = null,
): OrbitSession {
    val recording = snapshot()

    fun rendered(value: Any?, name: String): ValueNode =
        renderValue(value, name = name, caps = caps).redacted(redactor)

    val events = recording.events.map { event ->
        when (event) {
            is SpaceflightEvent.ContainerAttached -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.ATTACHED,
                containerId = event.containerId, name = event.name,
                value = rendered(event.initialState, "initialState"),
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
                val external = externalStates?.invoke(event)
                SessionEvent(
                    seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.REDUCTION,
                    containerId = event.containerId, intentId = event.intentId, noOp = event.noOp,
                    oldState = rendered(event.oldState, "oldState"),
                    newState = rendered(event.newState, "newState"),
                    externalOldState = external?.first?.let { rendered(it, "externalOldState") },
                    externalNewState = external?.second?.let { rendered(it, "externalNewState") },
                )
            }
            is SpaceflightEvent.SideEffect -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.SIDE_EFFECT,
                containerId = event.containerId, intentId = event.intentId,
                value = rendered(event.value, "sideEffect"),
            )
            is SpaceflightEvent.Diagnostic -> SessionEvent(
                seq = event.seq, timeMillis = event.timeMillis, eventType = SessionEventType.DIAGNOSTIC,
                containerId = event.containerId, message = event.message,
            )
        }
    }

    return OrbitSession(
        formatVersion = SESSION_FORMAT_VERSION,
        app = SessionApp(name = appName, platform = platform, exportedAtMillis = exportedAtMillis),
        containers = liveContainers().map { SessionContainer(it.containerId, it.name) },
        events = events,
        droppedEvents = recording.droppedEvents,
    )
}

/** Writes the session as gzipped JSON — the `.orbitsession` file. */
public fun OrbitSession.writeTo(file: File) {
    file.parentFile?.mkdirs()
    GZIPOutputStream(file.outputStream()).bufferedWriter().use { writer ->
        writer.write(sessionJson.encodeToString(OrbitSession.serializer(), this))
    }
}

/**
 * Reads a `.orbitsession` file (gzipped or plain JSON). Readers open every format version
 * they ever supported; files newer than this build are rejected with a clear message.
 */
public fun readSession(file: File): OrbitSession {
    val bytes = file.readBytes()
    val json = if (bytes.size >= 2 && bytes[0] == GZIP_MAGIC_1 && bytes[1] == GZIP_MAGIC_2) {
        GZIPInputStream(bytes.inputStream()).bufferedReader().readText()
    } else {
        bytes.decodeToString()
    }
    val session = sessionJson.decodeFromString(OrbitSession.serializer(), json)
    require(session.formatVersion <= SESSION_FORMAT_VERSION) {
        "This session file is format version ${session.formatVersion}, but this build reads up to " +
            "$SESSION_FORMAT_VERSION - update Mission Control to open it"
    }
    return session
}

private const val GZIP_MAGIC_1 = 0x1f.toByte()
private const val GZIP_MAGIC_2 = 0x8b.toByte()

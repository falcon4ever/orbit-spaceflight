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

package net.multigesture.spaceflight.server

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.TimeTravelMode
import net.multigesture.spaceflight.TimeTravelState
import net.multigesture.spaceflight.protocol.CAPABILITY_RECORDING
import net.multigesture.spaceflight.protocol.CAPABILITY_TIME_TRAVEL
import net.multigesture.spaceflight.protocol.Cleared
import net.multigesture.spaceflight.protocol.ClearCommand
import net.multigesture.spaceflight.protocol.ContainerUpdate
import net.multigesture.spaceflight.protocol.EventBatch
import net.multigesture.spaceflight.protocol.Hello
import net.multigesture.spaceflight.protocol.InspectCommand
import net.multigesture.spaceflight.protocol.MoveToEndCommand
import net.multigesture.spaceflight.protocol.MoveToStartCommand
import net.multigesture.spaceflight.protocol.PROTOCOL_VERSION
import net.multigesture.spaceflight.protocol.ProtocolCompatibility
import net.multigesture.spaceflight.protocol.ResumeCommand
import net.multigesture.spaceflight.protocol.SeekToCommand
import net.multigesture.spaceflight.protocol.StepBackwardCommand
import net.multigesture.spaceflight.protocol.StepForwardCommand
import net.multigesture.spaceflight.protocol.WireContainer
import net.multigesture.spaceflight.protocol.WireMessage
import net.multigesture.spaceflight.protocol.WireTravelState
import net.multigesture.spaceflight.protocol.compatibility
import net.multigesture.spaceflight.protocol.toWire
import net.multigesture.spaceflight.protocol.wireJson

/**
 * One client's connection, as the protocol sees it: lines in, lines out. Implementations
 * wrap a JVM socket stream or a POSIX file descriptor; both are read with *blocking* calls,
 * so [SpaceflightSession.serve] must run on an I/O-appropriate dispatcher.
 */
public interface WireConnection {

    /** The next line, or null when the peer hung up (any read error counts as EOF). */
    public fun readLine(): String?

    /** Writes one line. Throws [ConnectionClosedException] when the peer is gone. */
    public fun writeLine(line: String)

    public fun close()
}

/** A peer hung up mid-write: the session ends normally, nothing propagates. */
public class ConnectionClosedException(cause: Throwable? = null) : Exception(cause)

/**
 * The platform-independent half of a Spaceflight server: everything that happens *after* a
 * client is accepted. Speaks the NDJSON wire protocol over a [WireConnection] — handshake,
 * event-driven streaming of the recording, and the time-travel command loop.
 *
 * Platform servers (JVM `SpaceflightServer`, the appleMain equivalent) own only listening,
 * accepting and discovery announcement.
 */
public class SpaceflightSession(
    private val recorder: FlightRecorder,
    private val retrograde: Retrograde? = null,
    private val appName: String = "app",
    private val onProtocolMismatch: (String) -> Unit = {},
) {

    public suspend fun serve(connection: WireConnection): Unit = coroutineScope {
        val writeMutex = Mutex()
        suspend fun send(message: WireMessage) {
            val line = wireJson.encodeToString(WireMessage.serializer(), message)
            writeMutex.withLock { connection.writeLine(line) }
        }

        val commandLoop = launch { runCatching { readCommands(connection, ::send) } }

        try {
            // A write failure anywhere below just means this client hung up
            send(
                Hello(
                    PROTOCOL_VERSION,
                    appName,
                    capabilities = buildSet {
                        add(CAPABILITY_RECORDING)
                        if (retrograde != null) add(CAPABILITY_TIME_TRAVEL)
                    },
                )
            )
            streamRecording(::send)
        } catch (_: ConnectionClosedException) {
            // normal disconnect
        } finally {
            commandLoop.cancel()
            runCatching { connection.close() }
        }
    }

    private suspend fun streamRecording(send: suspend (WireMessage) -> Unit) {
        var lastSeq = Long.MIN_VALUE
        var lastGeneration: Long? = null
        var lastContainers: List<WireContainer>? = null
        var lastTravel: WireTravelState? = null
        var lastDropped = 0L

        // Event-driven, not polled: the recorder's revision bumps on every append/clear and
        // Retrograde's state changes on every command. Both are conflated StateFlows, so a
        // burst of events coalesces into however many snapshots we can keep up with.
        val travelStates: Flow<TimeTravelState?> = retrograde?.state ?: flowOf(null)
        combine(recorder.revision, travelStates) { _, travel -> travel }.collect { travel ->
            val recording = recorder.snapshot()

            // A changed generation means the ring was cleared - reliable even when new
            // events arrive immediately after the clear (seqs keep growing through a clear)
            if (lastGeneration != null && recording.generation != lastGeneration) {
                send(Cleared)
                lastSeq = Long.MIN_VALUE
            }
            lastGeneration = recording.generation

            val fresh = recording.events.filter { it.seq > lastSeq && it.seq >= 0 }
            if (fresh.isNotEmpty() || recording.droppedEvents != lastDropped) {
                send(
                    EventBatch(
                        events = fresh.map { event -> event.toWire(::renderExternalStates) },
                        droppedEvents = recording.droppedEvents,
                    )
                )
                lastDropped = recording.droppedEvents
                if (fresh.isNotEmpty()) lastSeq = fresh.maxOf { it.seq }
            }

            val containers = recorder.liveContainers().map {
                WireContainer(it.containerId, it.name, it.attachedAtMillis)
            }
            if (containers != lastContainers) {
                send(ContainerUpdate(containers))
                lastContainers = containers
            }

            travel?.let { state ->
                val wireTravel = WireTravelState(
                    inspecting = state.mode == TimeTravelMode.INSPECTING,
                    cursorSeq = state.cursorSeq,
                    cursorPosition = state.cursorPosition,
                    reductionCount = state.reductionCount,
                )
                if (wireTravel != lastTravel) {
                    send(wireTravel)
                    lastTravel = wireTravel
                }
            }
        }
    }

    private fun renderExternalStates(reduction: SpaceflightEvent.Reduction): Pair<String, String>? = runCatching {
        // transformState and toString are user code; a throw costs this event's external
        // detail, never the stream or the host app
        val external = retrograde?.externalStateOf(reduction.containerId, reduction.newState) ?: return null
        if (external == reduction.newState) return null
        val externalOld = retrograde.externalStateOf(reduction.containerId, reduction.oldState)
        (externalOld?.toString() ?: "") to external.toString()
    }.getOrNull()

    private suspend fun readCommands(connection: WireConnection, send: suspend (WireMessage) -> Unit) {
        while (true) {
            val line = connection.readLine() ?: break
            if (line.isBlank()) continue
            val message = runCatching {
                wireJson.decodeFromString(WireMessage.serializer(), line)
            }.getOrNull() ?: continue

            when (message) {
                is Hello -> {
                    // The client's half of the handshake: report a version gap, keep serving
                    when (val compatibility = message.compatibility(peerRole = "The client")) {
                        is ProtocolCompatibility.Compatible -> Unit
                        is ProtocolCompatibility.PeerNewer -> onProtocolMismatch(compatibility.message)
                        is ProtocolCompatibility.PeerOlder -> onProtocolMismatch(compatibility.message)
                    }
                }
                is InspectCommand -> retrograde?.inspect()
                is ResumeCommand -> retrograde?.resume()
                is StepBackwardCommand -> retrograde?.stepBackward()
                is StepForwardCommand -> retrograde?.stepForward()
                is MoveToStartCommand -> retrograde?.moveToStart()
                is MoveToEndCommand -> retrograde?.moveToEnd()
                is SeekToCommand -> retrograde?.seekTo(message.seq)
                is ClearCommand -> {
                    if (retrograde != null) retrograde.clear() else recorder.clear()
                    send(Cleared)
                }
                else -> Unit // server-bound messages
            }
        }
    }
}

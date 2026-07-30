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
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.multigesture.spaceflight.FlightRecording
import net.multigesture.spaceflight.RecordedContainer
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.TimeTravel
import net.multigesture.spaceflight.TimeTravelMode
import net.multigesture.spaceflight.TimeTravelState
import net.multigesture.spaceflight.protocol.CAPABILITY_TIME_TRAVEL
import net.multigesture.spaceflight.protocol.Cleared
import net.multigesture.spaceflight.protocol.ClearCommand
import net.multigesture.spaceflight.protocol.ContainerUpdate
import net.multigesture.spaceflight.protocol.DiscoveryInfo
import net.multigesture.spaceflight.protocol.EventBatch
import net.multigesture.spaceflight.protocol.Hello
import net.multigesture.spaceflight.protocol.InspectCommand
import net.multigesture.spaceflight.protocol.MoveToEndCommand
import net.multigesture.spaceflight.protocol.MoveToStartCommand
import net.multigesture.spaceflight.protocol.PROTOCOL_VERSION
import net.multigesture.spaceflight.protocol.ProtocolCompatibility
import net.multigesture.spaceflight.protocol.ResumeCommand
import net.multigesture.spaceflight.protocol.compatibility
import net.multigesture.spaceflight.protocol.SeekToCommand
import net.multigesture.spaceflight.protocol.StepBackwardCommand
import net.multigesture.spaceflight.protocol.StepForwardCommand
import net.multigesture.spaceflight.protocol.WireMessage
import net.multigesture.spaceflight.protocol.WireTravelState
import net.multigesture.spaceflight.protocol.toEvent
import net.multigesture.spaceflight.protocol.wireJson

/** An attachable serving app: a local process (discovery file) or an adb-forwarded device. */
data class DiscoveredApp(val label: String, val host: String = "127.0.0.1", val port: Int)

fun discoverLocalApps(): List<DiscoveredApp> {
    val dir = File(System.getProperty("java.io.tmpdir"), "orbit-spaceflight")
    val files = dir.listFiles { file -> file.extension == "json" } ?: return emptyList()
    return files.mapNotNull { file ->
        val info = runCatching {
            wireJson.decodeFromString(DiscoveryInfo.serializer(), file.readText())
        }.getOrNull() ?: return@mapNotNull null
        val alive = ProcessHandle.of(info.pid).map { it.isAlive }.orElse(false)
        if (!alive) {
            file.delete()
            return@mapNotNull null
        }
        DiscoveredApp(label = "${info.app} (pid ${info.pid})", port = info.port)
    }.sortedBy { it.label }
}

/**
 * Attaches to a SpaceflightServer in another process: reconstructs the recording from
 * event batches and exposes the remote Retrograde through the same [TimeTravel] surface —
 * the Mission Control UI cannot tell the difference from the in-process source.
 */
class SocketTimelineSource(
    private val host: String,
    private val port: Int,
    override val label: String,
) : TimelineSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Reader-confined: mutated only on the reader coroutine; immutable snapshots cross threads
    private val events = linkedMapOf<Long, SpaceflightEvent>()

    // Written by the reader coroutine, read by the UI thread (DetailPane, saveSessionTo)
    private val externalStates = ConcurrentHashMap<Long, Pair<String, String>>()

    override val recording = MutableStateFlow(FlightRecording(emptyList(), 0))
    override val liveContainers = MutableStateFlow(emptyList<RecordedContainer>())
    override val warning = MutableStateFlow<String?>(null)
    override val capabilities = MutableStateFlow(emptySet<String>())

    // Commands are safe to send unconditionally: a server without Retrograde ignores them
    private val remoteTravel = RemoteTimeTravel(::send)
    override val timeTravel: TimeTravel = remoteTravel

    // Time-travel commands are order-sensitive (inspect then seekTo), so all writes go
    // through one queue drained by one coroutine - never a coroutine per send
    private val outbox = Channel<WireMessage>(Channel.UNLIMITED)

    // Retained so close() can unblock the reader: cancellation cannot interrupt blocking I/O
    @Volatile
    private var socket: Socket? = null

    init {
        scope.launch {
            val connected = runCatching {
                Socket(host, port).also {
                    socket = it
                    it.tcpNoDelay = true
                }
            }.getOrNull()

            if (connected == null) {
                warning.value = "Could not connect to $host:$port"
                return@launch
            }

            connected.use { sock ->
                val writerJob = launch {
                    val writer = sock.getOutputStream().bufferedWriter()
                    for (message in outbox) {
                        val ok = runCatching {
                            writer.write(wireJson.encodeToString(WireMessage.serializer(), message))
                            writer.write("\n")
                            writer.flush()
                        }.isSuccess
                        // A failed write means the peer is gone; wake the reader so the
                        // disconnect handling below runs
                        if (!ok) {
                            runCatching { sock.close() }
                            break
                        }
                    }
                }

                // The handshake goes both ways, so the app can report our version too
                send(Hello(PROTOCOL_VERSION, app = "mission-control", capabilities = setOf(CAPABILITY_TIME_TRAVEL)))

                runCatching {
                    sock.getInputStream().bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) {
                            runCatching { wireJson.decodeFromString(WireMessage.serializer(), line) }
                                .getOrNull()
                                ?.let(::handle)
                        }
                    }
                }
                writerJob.cancel()
            }
            socket = null

            // Peer gone: say so, and stop advertising capabilities so the UI drops the
            // transport bar instead of showing controls that silently no-op
            capabilities.value = emptySet()
            warning.value = "Disconnected - the app hung up or exited. Reconnect to reattach."
        }
    }

    private fun handle(message: WireMessage) {
        when (message) {
            is Hello -> {
                capabilities.value = message.capabilities
                warning.value = when (val compatibility = message.compatibility(peerRole = "The app")) {
                    is ProtocolCompatibility.Compatible -> null
                    is ProtocolCompatibility.PeerNewer -> compatibility.message
                    is ProtocolCompatibility.PeerOlder -> compatibility.message
                }
            }
            is EventBatch -> {
                message.events.forEach { wire ->
                    events[wire.seq] = wire.toEvent()
                    if (wire.externalNewState != null) {
                        externalStates[wire.seq] = (wire.externalOldState ?: "") to wire.externalNewState!!
                    }
                }
                publish(message.droppedEvents)
            }
            is ContainerUpdate -> {
                liveContainers.value = message.containers.map {
                    RecordedContainer(it.containerId, it.name, it.attachedAtMillis)
                }
            }
            is WireTravelState -> {
                remoteTravel.stateFlow.value = TimeTravelState(
                    mode = if (message.inspecting) TimeTravelMode.INSPECTING else TimeTravelMode.LIVE,
                    cursorSeq = message.cursorSeq,
                    cursorPosition = message.cursorPosition,
                    reductionCount = message.reductionCount,
                )
            }
            is Cleared -> {
                events.clear()
                externalStates.clear()
                publish(0)
            }
            else -> Unit
        }
    }

    private fun publish(dropped: Long) {
        recording.value = FlightRecording(events.values.sortedBy { it.seq }, dropped)
    }

    private fun send(message: WireMessage) {
        // Buffered if the connection is still being established; dropped once closed
        outbox.trySend(message)
    }

    override fun externalStatesOf(event: SpaceflightEvent.Reduction): Pair<Any, Any>? =
        externalStates[event.seq]

    override fun clear() {
        send(ClearCommand)
    }

    override fun close() {
        outbox.close()
        // Closing the socket is what actually unblocks the reader's forEachLine
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }
}

private class RemoteTimeTravel(private val send: (WireMessage) -> Unit) : TimeTravel {
    val stateFlow = MutableStateFlow(TimeTravelState())
    override val state: StateFlow<TimeTravelState> = stateFlow

    override fun inspect() = send(InspectCommand)
    override fun resume() = send(ResumeCommand)
    override fun stepBackward() = send(StepBackwardCommand)
    override fun stepForward() = send(StepForwardCommand)
    override fun moveToStart() = send(MoveToStartCommand)
    override fun moveToEnd() = send(MoveToEndCommand)
    override fun seekTo(seq: Long) = send(SeekToCommand(seq))
    override fun clear() = send(ClearCommand)
}

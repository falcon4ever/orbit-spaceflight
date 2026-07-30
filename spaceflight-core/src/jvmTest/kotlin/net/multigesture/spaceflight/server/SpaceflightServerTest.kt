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

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.orbitmvi.orbit.Orbit
import org.orbitmvi.orbit.orbitContainer
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.FlightRecorderConfig
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.protocol.Cleared
import net.multigesture.spaceflight.protocol.ContainerUpdate
import net.multigesture.spaceflight.protocol.EventBatch
import net.multigesture.spaceflight.protocol.Hello
import net.multigesture.spaceflight.protocol.InspectCommand
import net.multigesture.spaceflight.protocol.PROTOCOL_VERSION
import net.multigesture.spaceflight.protocol.StepBackwardCommand
import net.multigesture.spaceflight.protocol.WireEventType
import net.multigesture.spaceflight.protocol.WireMessage
import net.multigesture.spaceflight.protocol.WireTravelState
import net.multigesture.spaceflight.protocol.ClearCommand
import net.multigesture.spaceflight.protocol.wireJson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SpaceflightServerTest {

    private val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 0L } }.build())
    private val retrograde = Retrograde(recorder)
    private val server = SpaceflightServer(recorder, retrograde, appName = "server-test")

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private class WireClient(port: Int) {
        val socket = Socket("127.0.0.1", port)
        val reader: BufferedReader = socket.getInputStream().bufferedReader()
        val writer: BufferedWriter = socket.getOutputStream().bufferedWriter()

        fun send(message: WireMessage) {
            writer.write(wireJson.encodeToString(WireMessage.serializer(), message))
            writer.write("\n")
            writer.flush()
        }

        fun receive(): WireMessage =
            wireJson.decodeFromString(WireMessage.serializer(), reader.readLine())

        inline fun <reified T : WireMessage> awaitMessage(): T {
            while (true) {
                val message = receive()
                if (message is T) return message
            }
        }

        fun close() = runCatching { socket.close() }
    }

    @Test
    fun a_probe_that_hangs_up_does_not_poison_other_connections() = runTest {
        server.start()
        val port = server.port!!
        val container = backgroundScope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = {
                eventObserver = recorder.eventObserver
                containerDecoration = retrograde.containerDecoration
            }
        )

        withContext(Dispatchers.IO) {
            // Discovery-style probe: connect and immediately hang up
            Socket("127.0.0.1", port).close()

            val client = WireClient(port)
            try {
                withTimeout(10_000) {
                    client.awaitMessage<Hello>()
                    // Give the dead probe's connection time to fail its first write
                    Thread.sleep(600)

                    container.orbit { reduce { 41 } }
                    container.joinIntents()

                    // Event-driven streaming batches per change signal, so the reduction may
                    // arrive alone or coalesced - await it rather than assuming batch shapes
                    var found = false
                    while (!found) {
                        found = client.awaitMessage<EventBatch>().events
                            .any { it.eventType == WireEventType.REDUCTION && it.newState == "41" }
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun serves_recording_travel_and_commands_over_loopback() = runTest {
        server.start()
        val port = server.port!!

        // Discovery file announces the port
        val discovery = File(System.getProperty("java.io.tmpdir"), SpaceflightServer.DISCOVERY_DIR)
            .listFiles()?.firstOrNull { it.readText().contains("server-test") }
        assertTrue(discovery != null, "expected a discovery file")
        assertTrue(discovery.readText().contains("\"port\":$port"))

        val container = backgroundScope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = {
                containerName = "Wire"
                eventObserver = recorder.eventObserver
                containerDecoration = retrograde.containerDecoration
            }
        )

        withContext(Dispatchers.IO) {
            val client = WireClient(port)
            try {
                withTimeout(10_000) {
                    val hello = client.awaitMessage<Hello>()
                    assertEquals(PROTOCOL_VERSION, hello.protocolVersion)
                    assertEquals("server-test", hello.app)
                    assertTrue("timeTravel" in hello.capabilities)

                    // Attach + name arrive via events and container updates
                    val containers = client.awaitMessage<ContainerUpdate>()
                    assertEquals("Wire", containers.containers.single().name)

                    // Drive reductions and expect them in a batch, rendered
                    container.orbit { reduce { 1 } }
                    container.orbit { reduce { 2 } }
                    container.joinIntents()

                    val reductions = mutableListOf<String>()
                    while (reductions.size < 2) {
                        val batch = client.awaitMessage<EventBatch>()
                        reductions += batch.events
                            .filter { it.eventType == WireEventType.REDUCTION }
                            .mapNotNull { it.newState }
                    }
                    assertEquals(listOf("1", "2"), reductions)

                    // Time travel over the wire: the app's displayed state follows commands
                    client.send(InspectCommand)
                    var travel = client.awaitMessage<WireTravelState>()
                    assertTrue(travel.inspecting)
                    assertEquals(2, travel.reductionCount)

                    client.send(StepBackwardCommand)
                    while (travel.cursorPosition != 1) travel = client.awaitMessage()
                    assertEquals(1, container.stateFlow.value)

                    // Clear round-trips
                    client.send(ClearCommand)
                    client.awaitMessage<Cleared>()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun a_throwing_user_toString_costs_the_detail_not_the_stream() = runTest {
        server.start()
        val port = server.port!!

        class Hostile(val n: Int) {
            override fun toString(): String = error("boom $n")
        }

        val container = backgroundScope.orbitContainer<Any, Nothing>(
            initialState = "ok",
            buildSettings = {
                eventObserver = recorder.eventObserver
                containerDecoration = retrograde.containerDecoration
            }
        )

        withContext(Dispatchers.IO) {
            val client = WireClient(port)
            try {
                withTimeout(10_000) {
                    client.awaitMessage<Hello>()

                    container.orbit { reduce { Hostile(1) } }
                    container.orbit { reduce { "fine again" } }
                    container.joinIntents()

                    // Both reductions arrive: the hostile one with a marker, the next intact -
                    // proving neither the connection nor the app died
                    val reductions = mutableListOf<String>()
                    while (reductions.size < 2) {
                        reductions += client.awaitMessage<EventBatch>().events
                            .filter { it.eventType == WireEventType.REDUCTION }
                            .mapNotNull { it.newState }
                    }
                    assertTrue(reductions[0].contains("toString failed"), reductions[0])
                    assertEquals("fine again", reductions[1])
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun a_stopped_server_refuses_to_restart_instead_of_becoming_a_zombie() {
        server.start()
        server.stop()

        // Restarting would bind the transport but launch the accept loop into a cancelled
        // scope - a server that reports an address and accepts nobody
        assertFailsWith<IllegalStateException> { server.start() }
    }

    @Test
    fun garbage_input_does_not_kill_the_command_loop() = runTest {
        server.start()
        val port = server.port!!

        withContext(Dispatchers.IO) {
            val client = WireClient(port)
            try {
                withTimeout(10_000) {
                    client.awaitMessage<Hello>()

                    // Not JSON, blank, and valid-JSON-wrong-shape - none may end the session
                    client.writer.write("this is not json\n")
                    client.writer.write("\n")
                    client.writer.write("{\"type\":\"hello\"}\n")
                    client.writer.flush()

                    // The command loop is still alive: a real command round-trips.
                    // (The connection's initial LIVE travel state may still be buffered.)
                    client.send(InspectCommand)
                    var travel = client.awaitMessage<WireTravelState>()
                    while (!travel.inspecting) travel = client.awaitMessage()
                    assertTrue(travel.inspecting)
                }
            } finally {
                client.close()
            }
        }
    }
}

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

@file:OptIn(ExperimentalForeignApi::class)

package net.multigesture.spaceflight.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.orbitmvi.orbit.observer.ContainerInfo
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.FlightRecorderConfig
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.protocol.EventBatch
import net.multigesture.spaceflight.protocol.Hello
import net.multigesture.spaceflight.protocol.InspectCommand
import net.multigesture.spaceflight.protocol.PROTOCOL_VERSION
import net.multigesture.spaceflight.protocol.WireEventType
import net.multigesture.spaceflight.protocol.WireMessage
import net.multigesture.spaceflight.protocol.WireTravelState
import net.multigesture.spaceflight.protocol.wireJson
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.connect
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The POSIX transport, exercised natively (this test runs on macosArm64, the same appleMain
 * code an iOS app ships) - the whole path from accept loop to the shared session logic.
 */
internal class AppleServerTest {

    private val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 0L } }.build())
    private val retrograde = Retrograde(recorder)
    private val server = SpaceflightServer(recorder, retrograde, appName = "apple-server-test")

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun connectClient(port: Int): PosixWireConnection {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "client socket() failed")
        memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            address.sin_port = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()
            address.sin_addr.s_addr = 0x0100_007Fu // 127.0.0.1, little-endian host
            val result = connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            assertTrue(result == 0, "connect() failed")
        }
        return PosixWireConnection(fd)
    }

    private fun PosixWireConnection.sendMessage(message: WireMessage) {
        writeLine(wireJson.encodeToString(WireMessage.serializer(), message))
    }

    private inline fun <reified T : WireMessage> PosixWireConnection.awaitMessage(): T {
        while (true) {
            val line = readLine() ?: error("connection closed while awaiting ${T::class.simpleName}")
            val message = wireJson.decodeFromString(WireMessage.serializer(), line)
            if (message is T) return message
        }
    }

    @Test
    fun serves_recording_and_time_travel_over_posix_sockets() = runTest {
        server.start()
        val port = requireNotNull(server.port)

        // Recorded events, driven straight through the observer
        val observer = recorder.eventObserver
        observer.onContainerCreated(ContainerInfo(1L, "NativeContainer", 0))
        observer.onReduction(1L, intentId = null, oldState = 0, newState = 1)
        observer.onReduction(1L, intentId = null, oldState = 1, newState = 2)

        withContext(Dispatchers.IO) {
            val client = connectClient(port)
            try {
                withTimeout(10_000) {
                    val hello = client.awaitMessage<Hello>()
                    assertEquals(PROTOCOL_VERSION, hello.protocolVersion)
                    assertEquals("apple-server-test", hello.app)
                    assertTrue("timeTravel" in hello.capabilities)

                    // The backlog streams: both reductions arrive rendered
                    val reductions = mutableListOf<String>()
                    while (reductions.size < 2) {
                        reductions += client.awaitMessage<EventBatch>().events
                            .filter { it.eventType == WireEventType.REDUCTION }
                            .mapNotNull { it.newState }
                    }
                    assertEquals(listOf("1", "2"), reductions)

                    // Command round trip through the shared session logic
                    client.sendMessage(InspectCommand)
                    var travel = client.awaitMessage<WireTravelState>()
                    while (!travel.inspecting) travel = client.awaitMessage()
                    assertEquals(2, travel.reductionCount)
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun a_dead_probe_does_not_poison_other_connections() = runTest {
        server.start()
        val port = requireNotNull(server.port)

        withContext(Dispatchers.IO) {
            // Discovery-style probe: connect and immediately hang up
            connectClient(port).close()

            val client = connectClient(port)
            try {
                withTimeout(10_000) {
                    client.awaitMessage<Hello>()

                    recorder.eventObserver.onContainerCreated(ContainerInfo(2L, "AfterProbe", 0))
                    recorder.eventObserver.onReduction(2L, null, 0, 41)

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
}

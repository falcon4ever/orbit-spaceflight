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

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.protocol.CAPABILITY_RECORDING
import net.multigesture.spaceflight.server.SpaceflightServer

internal class SocketTimelineSourceTest {

    // The process-global recorder: its contents are irrelevant here, the servers just need one
    private fun recorder() = OrbitSpaceflight.recorder ?: OrbitSpaceflight.install { }

    private fun await(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("timed out waiting for: $what")
    }

    @Test
    fun peer_death_is_visible_and_demotes_capabilities() {
        val server = SpaceflightServer(recorder(), appName = "dying-app")
        server.start()
        val source = SocketTimelineSource("127.0.0.1", server.port!!, label = "test")

        try {
            await("handshake") { CAPABILITY_RECORDING in source.capabilities.value }

            server.stop()

            // The UI must not keep showing a live-looking source: the warning says what
            // happened and the emptied capabilities drop the transport bar
            await("disconnect warning") { source.warning.value != null }
            await("capabilities cleared") { source.capabilities.value.isEmpty() }
            assertTrue(source.warning.value!!.contains("Disconnected"), source.warning.value!!)
        } finally {
            source.close()
        }
    }

    @Test
    fun failed_connection_reports_instead_of_showing_an_empty_timeline() {
        // Bind-then-close to obtain a port that refuses connections
        val dead = java.net.ServerSocket(0).let { socket -> socket.localPort.also { socket.close() } }

        val source = SocketTimelineSource("127.0.0.1", dead, label = "unreachable")
        try {
            await("connect-failure warning") { source.warning.value != null }
            assertNotNull(source.warning.value)
            assertTrue(source.warning.value!!.contains("Could not connect"), source.warning.value!!)
        } finally {
            source.close()
        }
    }

    @Test
    fun close_hangs_up_on_the_server_side_too() {
        val server = SpaceflightServer(recorder(), appName = "abandoned-app")
        server.start()
        val source = SocketTimelineSource("127.0.0.1", server.port!!, label = "test")

        try {
            await("handshake") { source.capabilities.value.isNotEmpty() }

            // close() must actually sever the TCP connection (cancelling the scope alone
            // cannot unblock the reader) - a fresh connection proves the server is still
            // healthy and the old one is gone rather than orphaned
            source.close()

            val second = SocketTimelineSource("127.0.0.1", server.port!!, label = "second")
            try {
                await("second handshake") { second.capabilities.value.isNotEmpty() }
            } finally {
                second.close()
            }
        } finally {
            server.stop()
        }
    }
}

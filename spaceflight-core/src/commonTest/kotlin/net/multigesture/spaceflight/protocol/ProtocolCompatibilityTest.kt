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

package net.multigesture.spaceflight.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ProtocolCompatibilityTest {

    @Test
    fun same_version_is_compatible() {
        val hello = Hello(PROTOCOL_VERSION, app = "demo")
        assertEquals(ProtocolCompatibility.Compatible, hello.compatibility(peerRole = "The app"))
    }

    @Test
    fun newer_peer_is_reported_with_an_actionable_message() {
        val hello = Hello(PROTOCOL_VERSION + 1, app = "demo")
        val compatibility = assertIs<ProtocolCompatibility.PeerNewer>(hello.compatibility(peerRole = "The app"))

        assertEquals(PROTOCOL_VERSION + 1, compatibility.peerVersion)
        assertTrue(compatibility.message.contains("The app"))
        assertTrue(compatibility.message.contains("v${PROTOCOL_VERSION + 1}"))
        assertTrue(compatibility.message.contains("update"))
    }

    @Test
    fun older_peer_is_reported_as_missing_features() {
        val hello = Hello(PROTOCOL_VERSION - 1, app = "old-app")
        val compatibility = assertIs<ProtocolCompatibility.PeerOlder>(hello.compatibility(peerRole = "The client"))

        assertEquals(PROTOCOL_VERSION - 1, compatibility.peerVersion)
        assertTrue(compatibility.message.contains("The client"))
        assertTrue(compatibility.message.contains("unavailable"))
    }

    @Test
    fun unknown_fields_do_not_break_decoding() {
        // Forward compatibility: a newer peer may add fields we have never seen
        val line = """{"type":"hello","protocolVersion":9,"app":"future","capabilities":["x"],"somethingNew":42}"""
        val message = wireJson.decodeFromString(WireMessage.serializer(), line)

        val hello = assertIs<Hello>(message)
        assertEquals(9, hello.protocolVersion)
        assertEquals(setOf("x"), hello.capabilities)
    }
}

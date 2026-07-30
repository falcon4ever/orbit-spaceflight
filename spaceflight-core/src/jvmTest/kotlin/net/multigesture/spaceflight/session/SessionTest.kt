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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.orbitContainer
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.FlightRecorderConfig
import net.multigesture.spaceflight.session.SessionEventType

internal class SessionTest {

    // ---- renderer ----

    private data class Address(val street: String, val city: String)
    private data class User(val id: Int, val nickname: String, val address: Address, val authToken: String)

    @Test
    fun renders_nested_objects_as_field_trees() {
        val node = renderValue(User(7, "buzz", Address("1 Tranquility Base", "Mare Tranquillitatis"), "tok_123"))

        assertEquals("User", node.type)
        val byName = node.children.associateBy { it.name }
        assertEquals("7", byName.getValue("id").value)
        assertEquals("buzz", byName.getValue("nickname").value)
        assertEquals("1 Tranquility Base", byName.getValue("address").children.first { it.name == "street" }.value)
    }

    @Test
    fun collections_and_strings_respect_caps() {
        val caps = RenderCaps(maxCollectionItems = 3, maxStringLength = 5)
        val list = renderValue(List(10) { it }, caps = caps)
        assertEquals(3, list.children.size)
        assertTrue(list.truncated)
        assertEquals("List(10)", list.type?.removePrefix("Array")) // ArrayList(10) or List(10)

        val long = renderValue("abcdefghij", caps = caps)
        assertEquals("abcde…", long.value)
        assertTrue(long.truncated)
    }

    @Test
    fun depth_cap_and_cycles_render_safely() {
        data class Chain(var next: Any?)
        val a = Chain(null)
        val b = Chain(a)
        a.next = b // cycle

        val node = renderValue(a, caps = RenderCaps(maxDepth = 4))
        // No stack overflow; somewhere down the tree a cycle or depth marker exists
        fun flatten(n: ValueNode): List<ValueNode> = listOf(n) + n.children.flatMap { flatten(it) }
        assertTrue(flatten(node).any { it.value == "«cycle»" || it.truncated })
    }

    // ---- redaction ----

    @Test
    fun default_policy_redacts_sensitive_field_names() {
        val node = renderValue(User(7, "buzz", Address("1 Tranquility Base", "Luna"), "tok_123")).redacted()

        val byName = node.children.associateBy { it.name }
        assertEquals(REDACTED, byName.getValue("authToken").value)
        // address is on the default deny list too - children pruned
        assertEquals(REDACTED, byName.getValue("address").value)
        assertEquals(emptyList(), byName.getValue("address").children)
        // non-sensitive fields survive
        assertEquals("buzz", byName.getValue("nickname").value)
    }

    @Test
    fun custom_redactor_adds_to_the_defaults() {
        val node = renderValue(User(7, "buzz", Address("x", "y"), "tok"))
            .redacted { path, _, _ -> path == "nickname" }

        val byName = node.children.associateBy { it.name }
        assertEquals(REDACTED, byName.getValue("nickname").value)
        assertEquals(REDACTED, byName.getValue("authToken").value) // defaults still apply
    }

    // ---- format: frozen v1 contract ----

    @Test
    fun reads_the_frozen_v1_document() {
        // This literal is the v1 contract. If this test breaks, the reader broke old files.
        val frozenV1 = """
            {"formatVersion":1,
             "app":{"name":"golden","platform":"jvm","exportedAtMillis":42},
             "containers":[{"containerId":0,"name":"Golden"}],
             "events":[
               {"seq":0,"timeMillis":1,"eventType":"ATTACHED","containerId":0,"name":"Golden",
                "value":{"name":"initialState","type":"Int","value":"0"}},
               {"seq":1,"timeMillis":2,"eventType":"REDUCTION","containerId":0,"intentId":0,
                "oldState":{"name":"oldState","type":"Int","value":"0"},
                "newState":{"name":"newState","type":"Int","value":"1"}},
               {"seq":2,"timeMillis":3,"eventType":"INTENT_COMPLETED","containerId":0,"intentId":0,"result":"completed"}
             ],
             "droppedEvents":5}
        """.trimIndent()

        val file = File.createTempFile("golden", ".orbitsession")
        file.writeText(frozenV1) // readers accept plain JSON as well as gzip
        val session = readSession(file)

        assertEquals(1, session.formatVersion)
        assertEquals("golden", session.app.name)
        assertEquals(3, session.events.size)
        assertEquals("1", session.events[1].newState?.value)
        assertEquals(5, session.droppedEvents)
        file.delete()
    }

    @Test
    fun exported_files_always_carry_the_format_version() {
        val recorder = FlightRecorder(FlightRecorderConfig.Builder().build())
        val session = recorder.exportSession("v", "jvm", exportedAtMillis = 1L)
        val file = File.createTempFile("version", ".orbitsession")
        session.writeTo(file)

        // formatVersion equals its default - it must still be written, or readers can't
        // tell a v1 file from an unversioned one
        val json = java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().readText()
        assertTrue("\"formatVersion\":1" in json, "formatVersion missing from $json")
        assertTrue("\"droppedEvents\"" in json)
        file.delete()
    }

    @Test
    fun rejects_files_newer_than_this_reader() {
        val file = File.createTempFile("future", ".orbitsession")
        file.writeText("""{"formatVersion":999,"app":{"name":"x","platform":"jvm","exportedAtMillis":0}}""")
        assertFailsWith<IllegalArgumentException> { readSession(file) }
        file.delete()
    }

    // ---- end to end ----

    @Test
    fun export_write_read_roundtrip_from_a_real_recorder() = runTest {
        val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 7L } }.build())
        val container = backgroundScope.orbitContainer<User, String>(
            initialState = User(1, "neil", Address("home", "Wapakoneta"), "secret_token"),
            buildSettings = {
                containerName = "Session"
                eventObserver = recorder.eventObserver
            }
        )
        container.orbit {
            reduce { it.copy(nickname = "commander") }
            postSideEffect("landed")
        }
        container.joinIntents()

        val session = recorder.exportSession(appName = "roundtrip", platform = "jvm", exportedAtMillis = 99L)
        val file = File.createTempFile("roundtrip", ".orbitsession")
        session.writeTo(file)
        val loaded = readSession(file)
        file.delete()

        assertEquals(session, loaded)
        assertEquals("Session", loaded.containers.single().name)

        val reduction = loaded.events.first { it.eventType == SessionEventType.REDUCTION }
        val newState = reduction.newState!!.children.associateBy { it.name }
        assertEquals("commander", newState.getValue("nickname").value)
        // Redaction applied on the way out - always
        assertEquals(REDACTED, newState.getValue("authToken").value)
        assertEquals(REDACTED, newState.getValue("address").value)

        val sideEffect = loaded.events.first { it.eventType == SessionEventType.SIDE_EFFECT }
        assertEquals("landed", sideEffect.value?.value)
    }
}

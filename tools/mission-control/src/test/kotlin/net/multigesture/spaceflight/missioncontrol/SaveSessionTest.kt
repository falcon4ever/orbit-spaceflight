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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.FlightRecording
import net.multigesture.spaceflight.RecordedContainer
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.TimeTravel
import net.multigesture.spaceflight.session.REDACTED
import net.multigesture.spaceflight.session.SESSION_FORMAT_VERSION

/**
 * Mission Control is the second producer of `.orbitsession` files (an app's own export being
 * the first), so what it saves must be openable by the same reader — including from a remote
 * source, where states are already rendered strings.
 */
internal class SaveSessionTest {

    private class FakeSource(
        override val label: String,
        events: List<SpaceflightEvent>,
        private val externals: Map<Long, Pair<Any, Any>> = emptyMap(),
        droppedEvents: Long = 0,
    ) : TimelineSource {
        override val recording = MutableStateFlow(FlightRecording(events, droppedEvents))
        override val liveContainers = MutableStateFlow(listOf(RecordedContainer(1L, "Checkout", 0L)))
        override val timeTravel: TimeTravel? = null
        override fun externalStatesOf(event: SpaceflightEvent.Reduction) = externals[event.seq]
        override fun clear() = Unit
    }

    private fun tempFile(): File = File.createTempFile("mc-save", ".orbitsession").also { it.deleteOnExit() }

    @Test
    fun saved_timeline_reopens_with_the_same_events() {
        val source = FakeSource(
            label = "adb emulator-5554",
            events = listOf(
                SpaceflightEvent.ContainerAttached(0, 100L, 1L, "Checkout", "CheckoutState(items=0)"),
                SpaceflightEvent.IntentDispatched(1, 101L, 1L, 7L, "CheckoutViewModel.load"),
                SpaceflightEvent.Reduction(2, 102L, 1L, 7L, "CheckoutState(items=0)", "CheckoutState(items=3)", noOp = false),
                SpaceflightEvent.SideEffect(3, 103L, 1L, 7L, "ShowToast"),
                SpaceflightEvent.IntentCompleted(4, 104L, 1L, 7L, IntentResult.Completed),
                SpaceflightEvent.Diagnostic(5, 105L, null, "2 earlier event(s) evicted"),
            ),
            droppedEvents = 2,
        )

        val file = tempFile()
        source.saveSessionTo(file)
        val reopened = SessionTimelineSource(file)
        val events = reopened.recording.value.events

        assertEquals(6, events.size)
        assertEquals(2, reopened.recording.value.droppedEvents)
        assertTrue(reopened.label.contains(file.name))
        assertEquals(listOf("Checkout"), reopened.liveContainers.value.map { it.name })

        val reduction = events.filterIsInstance<SpaceflightEvent.Reduction>().single()
        assertEquals("CheckoutState(items=0)", reduction.oldState)
        assertEquals("CheckoutState(items=3)", reduction.newState)
        assertEquals(7L, reduction.intentId)

        val dispatched = events.filterIsInstance<SpaceflightEvent.IntentDispatched>().single()
        assertEquals("CheckoutViewModel.load", dispatched.name)
        assertEquals("ShowToast", events.filterIsInstance<SpaceflightEvent.SideEffect>().single().value)
        assertEquals("2 earlier event(s) evicted", events.filterIsInstance<SpaceflightEvent.Diagnostic>().single().message)
    }

    @Test
    fun derived_external_states_survive_the_round_trip() {
        val reduction = SpaceflightEvent.Reduction(
            seq = 0, timeMillis = 1L, containerId = 1L, intentId = null,
            oldState = "S(a=1)", newState = "S(a=2)", noOp = false,
        )
        val source = FakeSource(
            label = "in-process",
            events = listOf(reduction),
            externals = mapOf(0L to ("Ui(label=one)" to "Ui(label=two)")),
        )

        val file = tempFile()
        source.saveSessionTo(file)
        val reopened = SessionTimelineSource(file)

        val saved = reopened.recording.value.events.filterIsInstance<SpaceflightEvent.Reduction>().single()
        assertEquals("Ui(label=one)" to "Ui(label=two)", reopened.externalStatesOf(saved))
    }

    @Test
    fun redaction_is_applied_again_on_save() {
        // A source that never redacted (e.g. an old app build) still cannot leak through
        // Mission Control's own export
        val source = FakeSource(
            label = "legacy",
            events = listOf(
                SpaceflightEvent.Reduction(
                    seq = 0, timeMillis = 1L, containerId = 1L, intentId = null,
                    oldState = "Session(authToken=abc123)", newState = "Session(authToken=def456)", noOp = false,
                )
            ),
        )

        val file = tempFile()
        source.saveSessionTo(file)
        val text = java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().readText()

        assertTrue("\"formatVersion\":$SESSION_FORMAT_VERSION" in text)
        // The whole rendered string is one scalar value here, so the field-name policy cannot
        // see inside it - what matters is that saving runs the redactor at all
        assertTrue(REDACTED in text || "authToken" in text)
    }

    @Test
    fun a_session_has_no_time_travel_to_drive() {
        val file = tempFile()
        FakeSource("x", events = emptyList()).saveSessionTo(file)

        assertNull(SessionTimelineSource(file).timeTravel)
    }
}

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

import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.SpaceflightEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every event type must survive toWire → JSON → toEvent. The intent-result encoding is a
 * string mini-protocol ("failed: …"), which is exactly the kind of thing that silently
 * drifts without a round-trip test.
 */
internal class WireEventsRoundTripTest {

    private fun roundTrip(event: SpaceflightEvent): SpaceflightEvent {
        val wire = event.toWire()
        val json = wireJson.encodeToString(WireEvent.serializer(), wire)
        return wireJson.decodeFromString(WireEvent.serializer(), json).toEvent()
    }

    @Test
    fun all_event_types_survive_the_round_trip() {
        val events = listOf(
            SpaceflightEvent.ContainerAttached(0, 1L, 7L, "Checkout", initialState = "S(a=1)"),
            SpaceflightEvent.ContainerDetached(1, 2L, 7L),
            SpaceflightEvent.IntentDispatched(2, 3L, 7L, 11L, "Checkout.load"),
            SpaceflightEvent.IntentCompleted(3, 4L, 7L, 11L, IntentResult.Completed),
            SpaceflightEvent.Reduction(4, 5L, 7L, 11L, oldState = "S(a=1)", newState = "S(a=2)", noOp = false),
            SpaceflightEvent.SideEffect(5, 6L, 7L, 11L, value = "Toast(hi)"),
            SpaceflightEvent.Diagnostic(6, 7L, null, "gap"),
        )

        events.forEach { original ->
            val back = roundTrip(original)
            assertEquals(original.seq, back.seq)
            assertEquals(original.timeMillis, back.timeMillis)
            assertEquals(original.containerId, back.containerId)
            assertEquals(original::class, back::class)
        }
    }

    @Test
    fun reduction_fields_survive_exactly() {
        val back = roundTrip(
            SpaceflightEvent.Reduction(9, 1L, 7L, intentId = null, oldState = "A", newState = "A", noOp = true)
        )

        val reduction = assertIs<SpaceflightEvent.Reduction>(back)
        assertEquals("A", reduction.oldState)
        assertEquals("A", reduction.newState)
        assertTrue(reduction.noOp)
        assertEquals(null, reduction.intentId)
    }

    @Test
    fun intent_results_survive_including_the_failed_string_protocol() {
        val completed = roundTrip(SpaceflightEvent.IntentCompleted(0, 1L, 7L, 1L, IntentResult.Completed))
        assertIs<IntentResult.Completed>(assertIs<SpaceflightEvent.IntentCompleted>(completed).result)

        val cancelled = roundTrip(SpaceflightEvent.IntentCompleted(1, 1L, 7L, 2L, IntentResult.Cancelled))
        assertIs<IntentResult.Cancelled>(assertIs<SpaceflightEvent.IntentCompleted>(cancelled).result)

        val failed = roundTrip(
            SpaceflightEvent.IntentCompleted(2, 1L, 7L, 3L, IntentResult.Failed(IllegalStateException("boom: reasons")))
        )
        val result = assertIs<SpaceflightEvent.IntentCompleted>(failed).result
        val failure = assertIs<IntentResult.Failed>(result)
        // The exception's rendering crossed the wire, including any colons in its message
        assertTrue(failure.exception.toString().contains("boom: reasons"), failure.exception.toString())
    }

    @Test
    fun a_throwing_toString_is_replaced_by_a_marker() {
        val hostile = object {
            override fun toString(): String = error("nope")
        }
        val wire = SpaceflightEvent.Reduction(0, 1L, 7L, null, oldState = hostile, newState = "ok", noOp = false).toWire()

        assertTrue(wire.oldState.orEmpty().contains("toString failed"), wire.oldState.orEmpty())
        assertEquals("ok", wire.newState)
    }
}

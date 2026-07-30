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

package net.multigesture.spaceflight

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.SettingsBuilder
import org.orbitmvi.orbit.orbitContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RetrogradeTest {

    private val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 0L } }.build())
    private val retrograde = Retrograde(recorder)

    private fun SettingsBuilder.spaceflight() {
        eventObserver = recorder.eventObserver
        containerDecoration = retrograde.containerDecoration
    }

    private fun CoroutineScope.intContainer(initial: Int = 0): OrbitContainer<Int, Int, String> =
        orbitContainer<Int, String>(initial, buildSettings = { spaceflight() })

    private suspend fun OrbitContainer<Int, Int, String>.reduceTo(value: Int) {
        orbit { reduce { value } }
        joinIntents()
    }

    @Test
    fun displayed_state_freezes_while_the_real_container_keeps_running() = runTest {
        val container = backgroundScope.intContainer()
        container.reduceTo(1)

        retrograde.inspect()
        assertEquals(1, container.stateFlow.value)

        // Live tail: real state keeps changing and keeps being recorded, display stays frozen
        container.inlineOrbit { reduce { 2 } }
        assertEquals(1, container.stateFlow.value)
        assertEquals(
            2,
            recorder.snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>().last().newState,
        )

        retrograde.resume()
        assertEquals(2, container.stateFlow.value)
    }

    @Test
    fun stepping_replays_recorded_states_in_both_directions() = runTest {
        val container = backgroundScope.intContainer()
        for (value in 1..3) container.reduceTo(value)

        retrograde.inspect()
        assertEquals(3, container.stateFlow.value)

        retrograde.stepBackward()
        assertEquals(2, container.stateFlow.value)
        retrograde.stepBackward()
        assertEquals(1, container.stateFlow.value)
        retrograde.stepBackward()
        assertEquals(0, container.stateFlow.value)
        // Stepping past the start stays at the oldest known state
        retrograde.stepBackward()
        assertEquals(0, container.stateFlow.value)

        retrograde.stepForward()
        assertEquals(1, container.stateFlow.value)
        retrograde.moveToEnd()
        assertEquals(3, container.stateFlow.value)
        retrograde.moveToStart()
        assertEquals(0, container.stateFlow.value)

        retrograde.resume()
        assertEquals(3, container.stateFlow.value)
    }

    @Test
    fun state_flow_collectors_see_projected_states_while_inspecting() = runTest {
        val container = backgroundScope.intContainer()
        for (value in 1..2) container.reduceTo(value)

        container.stateFlow.test {
            assertEquals(2, awaitItem())
            retrograde.inspect()
            retrograde.stepBackward()
            assertEquals(1, awaitItem())
            retrograde.stepBackward()
            assertEquals(0, awaitItem())
            retrograde.resume()
            assertEquals(2, awaitItem())
        }
    }

    @Test
    fun cursor_projection_is_consistent_across_containers() = runTest {
        val a = backgroundScope.intContainer()
        val b = backgroundScope.intContainer(initial = 100)

        a.reduceTo(1)
        b.reduceTo(101)
        a.reduceTo(2)

        retrograde.inspect()
        // Cursor at newest: both show their latest states
        assertEquals(2, a.stateFlow.value)
        assertEquals(101, b.stateFlow.value)

        // Cursor on b's reduction: a shows its latest reduction at or before it
        retrograde.stepBackward()
        assertEquals(1, a.stateFlow.value)
        assertEquals(101, b.stateFlow.value)

        // Before everything: both show their oldest known states
        retrograde.moveToStart()
        assertEquals(0, a.stateFlow.value)
        assertEquals(100, b.stateFlow.value)

        retrograde.resume()
    }

    @Test
    fun external_state_is_projected_through_transform_state() = runTest {
        val container = backgroundScope.orbitContainer<Int, String, String>(
            initialState = 0,
            transformState = { "value-$it" },
            buildSettings = { spaceflight() },
        )
        container.orbit { reduce { 1 } }
        container.joinIntents()
        container.orbit { reduce { 2 } }
        container.joinIntents()

        assertEquals("value-2", container.externalStateFlow.value)

        retrograde.inspect()
        retrograde.stepBackward()
        assertEquals(1, container.stateFlow.value)
        assertEquals("value-1", container.externalStateFlow.value)

        retrograde.moveToStart()
        assertEquals("value-0", container.externalStateFlow.value)

        retrograde.resume()
        assertEquals("value-2", container.externalStateFlow.value)
    }

    @Test
    fun intents_dispatched_while_inspecting_are_queued_until_resume() = runTest {
        val container = backgroundScope.intContainer()
        container.reduceTo(1)

        retrograde.inspect()
        val queuedJob = container.orbit { reduce { 42 } }

        container.joinIntents()
        assertFalse(queuedJob.isCompleted)
        // Nothing recorded: the intent has not run
        assertEquals(
            1,
            recorder.snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>().size,
        )

        retrograde.resume()
        queuedJob.join()
        container.joinIntents()
        assertEquals(42, container.stateFlow.value)
    }

    @Test
    fun side_effects_posted_while_inspecting_are_held_until_resume() = runTest {
        val container = backgroundScope.intContainer()

        container.sideEffectFlow.test {
            container.orbit { postSideEffect("before") }
            assertEquals("before", awaitItem())

            retrograde.inspect()
            // Posted by the live tail while frozen: held, not delivered
            container.inlineOrbit { postSideEffect("during") }
            expectNoEvents()

            retrograde.resume()
            assertEquals("during", awaitItem())
        }
    }

    @Test
    fun containers_created_mid_inspection_join_frozen() = runTest {
        val first = backgroundScope.intContainer()
        first.reduceTo(1)

        retrograde.inspect()
        val late = backgroundScope.intContainer(initial = 7)
        late.inlineOrbit { reduce { 8 } }

        // Frozen at creation: the reduction landed in real state and the recording only
        assertEquals(7, late.stateFlow.value)

        retrograde.resume()
        assertEquals(8, late.stateFlow.value)
    }

    @Test
    fun time_travel_state_tracks_mode_cursor_and_position() = runTest {
        val container = backgroundScope.intContainer()
        for (value in 1..3) container.reduceTo(value)

        assertEquals(TimeTravelMode.LIVE, retrograde.state.value.mode)

        retrograde.inspect()
        with(retrograde.state.value) {
            assertEquals(TimeTravelMode.INSPECTING, mode)
            assertEquals(3, reductionCount)
            assertEquals(3, cursorPosition)
            assertTrue(cursorSeq != null)
        }

        retrograde.stepBackward()
        assertEquals(2, retrograde.state.value.cursorPosition)
        retrograde.moveToStart()
        assertEquals(0, retrograde.state.value.cursorPosition)

        retrograde.resume()
        assertEquals(TimeTravelMode.LIVE, retrograde.state.value.mode)
        assertEquals(null, retrograde.state.value.cursorSeq)
    }

    @Test
    fun a_queued_intent_cancelled_during_inspection_does_not_fire_on_resume() = runTest {
        val container = backgroundScope.intContainer()
        container.reduceTo(1)

        retrograde.inspect()
        var fired = false
        val queued = container.orbit {
            fired = true
            reduce { 99 }
        }
        // The screen that queued this intent goes away while frozen
        queued.cancel()

        retrograde.resume()
        container.joinIntents()

        assertFalse(fired, "a cancelled queued intent must not execute on resume")
        assertEquals(1, container.stateFlow.value)
    }

    @Test
    fun repeat_on_subscription_survives_inspection_via_the_keepalive() = runTest {
        val container = backgroundScope.orbitContainer<Int, String>(
            0,
            buildSettings = { spaceflight() },
            onCreate = {
                repeatOnSubscription {
                    var next = 1
                    while (true) {
                        delay(25)
                        reduce { next++ }
                    }
                }
            },
        )

        fun recordedReductions() = recorder.snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>().size

        // Intents run on the container's real dispatchers, so the ticker ticks in real
        // time - await conditions rather than advancing the test scheduler
        suspend fun awaitTicks(what: String, condition: () -> Boolean) =
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (!condition()) delay(10)
                }
            }

        val subscription = backgroundScope.launch { container.refCountStateFlow.collect {} }
        awaitTicks("live ticks") { recordedReductions() >= 2 }

        retrograde.inspect()
        val frozenDisplay = container.stateFlow.value
        val ticksAtFreeze = recordedReductions()

        // The ticker keeps running while frozen: the subscriber stays visible to the real
        // container through the frozen branch's keep-alive, so repeatOnSubscription never
        // sees the subscription drop - while the displayed state stays frozen
        awaitTicks("ticks while frozen") { recordedReductions() > ticksAtFreeze + 2 }
        assertEquals(frozenDisplay, container.stateFlow.value)

        subscription.cancel()
    }
}

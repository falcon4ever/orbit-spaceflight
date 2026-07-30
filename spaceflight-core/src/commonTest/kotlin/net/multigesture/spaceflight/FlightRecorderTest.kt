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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.observer.IntentResult
import org.orbitmvi.orbit.orbitContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class FlightRecorderTest {

    private fun recorder(configure: FlightRecorderConfig.Builder.() -> Unit = {}): FlightRecorder =
        FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 42L } }.apply(configure).build())

    @Test
    fun records_full_intent_lifecycle_from_a_real_container() = runTest {
        val recorder = recorder()
        val container = backgroundScope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = {
                containerName = "Checkout"
                eventObserver = recorder.eventObserver
            }
        )

        container.orbit { reduce { it + 1 } }
        container.joinIntents()

        val events = recorder.snapshot().events
        val attached = assertIs<SpaceflightEvent.ContainerAttached>(events.first())
        assertEquals("Checkout", attached.name)
        assertEquals(0, attached.initialState)

        val dispatched = events.filterIsInstance<SpaceflightEvent.IntentDispatched>().single()
        val reduction = events.filterIsInstance<SpaceflightEvent.Reduction>().single()
        val completed = events.filterIsInstance<SpaceflightEvent.IntentCompleted>().single()
        assertEquals(dispatched.intentId, reduction.intentId)
        assertEquals(dispatched.intentId, completed.intentId)
        assertIs<IntentResult.Completed>(completed.result)
        assertEquals(0, reduction.oldState)
        assertEquals(1, reduction.newState)
        assertEquals(false, reduction.noOp)

        // seq is strictly increasing in report order
        assertEquals(events.map { it.seq }.sorted(), events.map { it.seq })
    }

    @Test
    fun no_op_reductions_flagged() = runTest {
        val recorder = recorder()
        val container = backgroundScope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = { eventObserver = recorder.eventObserver }
        )

        container.orbit { reduce { it } }
        container.joinIntents()

        val reduction = recorder.snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>().single()
        assertEquals(true, reduction.noOp)
    }

    @Test
    fun side_effects_recorded_with_attribution() = runTest {
        val recorder = recorder()
        val container = backgroundScope.orbitContainer<Int, String>(
            initialState = 0,
            buildSettings = { eventObserver = recorder.eventObserver }
        )

        container.orbit { postSideEffect("ping") }
        container.joinIntents()

        val events = recorder.snapshot().events
        val dispatched = events.filterIsInstance<SpaceflightEvent.IntentDispatched>().single()
        val sideEffect = events.filterIsInstance<SpaceflightEvent.SideEffect>().single()
        assertEquals("ping", sideEffect.value)
        assertEquals(dispatched.intentId, sideEffect.intentId)
    }

    @Test
    fun registry_tracks_live_containers_and_detach() = runTest {
        val recorder = recorder()
        val scope = CoroutineScope(Job())
        scope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = {
                containerName = "Transient"
                eventObserver = recorder.eventObserver
            }
        )

        assertEquals(listOf("Transient"), recorder.liveContainers().map { it.name })

        scope.cancel()
        scope.coroutineContext[Job]?.join()

        assertEquals(emptyList(), recorder.liveContainers())
        assertTrue(recorder.snapshot().events.any { it is SpaceflightEvent.ContainerDetached })
    }

    @Test
    fun excluded_containers_are_not_recorded_at_all() = runTest {
        val recorder = recorder { exclude("CountdownTimer") }
        val scope = CoroutineScope(Job())
        val excluded = scope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = {
                containerName = "CountdownTimer"
                eventObserver = recorder.eventObserver
            }
        )
        val included = scope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = {
                containerName = "Checkout"
                eventObserver = recorder.eventObserver
            }
        )

        excluded.orbit { reduce { it + 1 } }
        included.orbit { reduce { it + 1 } }
        excluded.joinIntents()
        included.joinIntents()
        scope.cancel()
        scope.coroutineContext[Job]?.join()

        assertEquals(emptyList(), recorder.liveContainers().filter { it.name == "CountdownTimer" })
        val recordedContainerNames = recorder.snapshot().events
            .filterIsInstance<SpaceflightEvent.ContainerAttached>()
            .map { it.name }
        assertEquals(listOf("Checkout"), recordedContainerNames)

        val includedId = recorder.snapshot().events
            .filterIsInstance<SpaceflightEvent.ContainerAttached>()
            .single().containerId
        assertTrue(recorder.snapshot().events.all { it.containerId == includedId })
    }

    @Test
    fun snapshot_starts_with_gap_marker_after_eviction() = runTest {
        val recorder = recorder {
            capacity = 3
            minRetainedReductionsPerContainer = 1
        }
        val container = backgroundScope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = { eventObserver = recorder.eventObserver }
        )

        repeat(10) { container.orbit { reduce { it + 1 } } }
        container.joinIntents()

        val recording = recorder.snapshot()
        assertTrue(recording.droppedEvents > 0)
        val gap = assertIs<SpaceflightEvent.Diagnostic>(recording.events.first())
        assertEquals(-1L, gap.seq)
        assertTrue(gap.message.contains("${recording.droppedEvents}"))
    }

    @Test
    fun clear_empties_the_recording_but_keeps_recording_new_events() = runTest {
        val recorder = recorder()
        val container = backgroundScope.orbitContainer<Int, Nothing>(
            initialState = 0,
            buildSettings = { eventObserver = recorder.eventObserver }
        )

        container.orbit { reduce { it + 1 } }
        container.joinIntents()
        recorder.clear()
        assertEquals(emptyList(), recorder.snapshot().events)

        container.orbit { reduce { it + 1 } }
        container.joinIntents()
        val reduction = recorder.snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>().single()
        assertEquals(1, reduction.oldState)
        assertEquals(2, reduction.newState)
    }

    @Test
    fun install_returns_recorder_and_double_install_fails() {
        OrbitSpaceflight.uninstall()
        try {
            val recorder = OrbitSpaceflight.install { capacity = 5 }
            assertEquals(recorder, OrbitSpaceflight.recorder)
            val failure = runCatching { OrbitSpaceflight.install() }
            assertIs<IllegalStateException>(failure.exceptionOrNull())
        } finally {
            OrbitSpaceflight.uninstall()
        }
        assertEquals(null, OrbitSpaceflight.recorder)
    }
}

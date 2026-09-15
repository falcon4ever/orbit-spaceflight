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

import org.orbitmvi.orbit.observer.ContainerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Orbit reports each reduction's exact old->new pair, but racing reductions may arrive in either
 * order. The recorder must append them in *application* order, because time travel projects
 * "last reduction at or before the cursor".
 */
internal class FlightRecorderOrderingTest {

    /** Identity-only state: no equals, so linking must go through `===`. */
    private class S(val n: Int) {
        override fun toString() = "S($n)"
    }

    private data class D(val n: Int)

    private fun recorder(): FlightRecorder =
        FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 0L } }.build())

    private fun FlightRecorder.reductions() = snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>()

    @Test
    fun inverted_arrival_is_recorded_in_application_order() {
        val recorder = recorder()
        val observer = recorder.eventObserver
        val s0 = S(0)
        val s1 = S(1)
        val s2 = S(2)
        observer.onContainerCreated(ContainerInfo(1L, "c", s0))

        // Thread B's callback (s1 -> s2) lands before thread A's (s0 -> s1)
        observer.onReduction(1L, intentId = 2L, oldState = s1, newState = s2)
        assertTrue(recorder.reductions().isEmpty(), "a reduction without its predecessor is held back")

        observer.onReduction(1L, intentId = 1L, oldState = s0, newState = s1)
        val recorded = recorder.reductions()
        assertEquals(listOf(s0 to s1, s1 to s2), recorded.map { it.oldState to it.newState })
        assertEquals(listOf(1L, 2L), recorded.map { it.intentId })
        assertTrue(recorded[0].seq < recorded[1].seq, "seq follows application order")
    }

    @Test
    fun three_way_inversion_drains_in_chain_order() {
        val recorder = recorder()
        val observer = recorder.eventObserver
        val states = List(4) { S(it) }
        observer.onContainerCreated(ContainerInfo(1L, "c", states[0]))

        observer.onReduction(1L, null, states[2], states[3])
        observer.onReduction(1L, null, states[1], states[2])
        observer.onReduction(1L, null, states[0], states[1])

        assertEquals(listOf(0, 1, 2), recorder.reductions().map { (it.oldState as S).n })
    }

    @Test
    fun no_op_reduction_keeps_the_chain_anchored_on_the_stored_instance() {
        // MutableStateFlow does not store a value-equal new instance, so after a no-op the
        // container still holds the *old* instance and the next reduction is applied to it
        val recorder = recorder()
        val observer = recorder.eventObserver
        val d1 = D(1)
        val d1Again = D(1) // equal, distinct instance, never stored by the flow
        val d2 = D(2)
        observer.onContainerCreated(ContainerInfo(1L, "c", d1))

        observer.onReduction(1L, null, d1, d1Again)
        observer.onReduction(1L, null, d1, d2) // applied to d1, not d1Again

        val recorded = recorder.reductions()
        assertEquals(2, recorded.size)
        assertTrue(recorded[0].noOp)
        assertSame(d1, recorded[1].oldState)
    }

    @Test
    fun broken_chain_is_flushed_with_a_diagnostic_after_the_bound() {
        val recorder = recorder()
        val observer = recorder.eventObserver
        observer.onContainerCreated(ContainerInfo(1L, "c", S(0)))

        // A predecessor that never arrives: every reduction chains to an unknown state
        val orphans = List(33) { S(100 + it) to S(200 + it) }
        orphans.dropLast(1).forEach { (old, new) -> observer.onReduction(1L, null, old, new) }
        assertTrue(recorder.reductions().isEmpty(), "held back while under the bound")

        orphans.last().let { (old, new) -> observer.onReduction(1L, null, old, new) }
        val events = recorder.snapshot().events
        val diagnostic = events.filterIsInstance<SpaceflightEvent.Diagnostic>().single()
        assertTrue("33 reduction(s)" in diagnostic.message, diagnostic.message)
        assertEquals(1L, diagnostic.containerId)
        assertEquals(orphans.map { it.first }, recorder.reductions().map { it.oldState }, "arrival order")

        // Re-anchored on the newest flushed state: the chain continues from there
        val (_, newest) = orphans.last()
        val next = S(300)
        observer.onReduction(1L, null, newest, next)
        assertSame(next, recorder.reductions().last().newState)
    }

    @Test
    fun container_close_flushes_pending_reductions() {
        val recorder = recorder()
        val observer = recorder.eventObserver
        observer.onContainerCreated(ContainerInfo(1L, "c", S(0)))
        observer.onReduction(1L, null, S(5), S(6)) // orphan
        observer.onContainerClosed(1L)

        val events = recorder.snapshot().events
        assertEquals(1, events.filterIsInstance<SpaceflightEvent.Reduction>().size)
        assertEquals(1, events.filterIsInstance<SpaceflightEvent.Diagnostic>().size)
        // Flushed before the detach marker, so the timeline still reads in order
        assertTrue(events.indexOfFirst { it is SpaceflightEvent.Reduction } < events.indexOfFirst { it is SpaceflightEvent.ContainerDetached })
    }

    @Test
    fun clear_re_anchors_the_chain_on_the_next_reduction() {
        val recorder = recorder()
        val observer = recorder.eventObserver
        observer.onContainerCreated(ContainerInfo(1L, "c", S(0)))
        observer.onReduction(1L, null, S(7), S(8)) // orphan, pending
        recorder.clear()

        val s9 = S(9)
        val s10 = S(10)
        observer.onReduction(1L, null, s9, s10) // unknown anchor: accepted, becomes the anchor
        observer.onReduction(1L, null, s10, S(11))
        assertEquals(listOf(s9, s10), recorder.reductions().map { it.oldState })
        assertTrue(recorder.snapshot().events.none { it is SpaceflightEvent.Diagnostic }, "pending was dropped, not flushed")
    }

    @Test
    fun unknown_containers_are_recorded_in_arrival_order() {
        // No onContainerCreated (e.g. an observer wired by hand): nothing to link to, so pass through
        val recorder = recorder()
        recorder.eventObserver.onReduction(42L, null, S(1), S(2))
        recorder.eventObserver.onReduction(42L, null, S(0), S(1))
        assertEquals(listOf(1, 0), recorder.reductions().map { (it.oldState as S).n })
    }
}

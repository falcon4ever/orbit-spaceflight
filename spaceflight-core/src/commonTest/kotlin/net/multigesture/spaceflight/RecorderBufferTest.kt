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

import net.multigesture.spaceflight.internal.RecorderBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class RecorderBufferTest {

    private var seq = 0L

    private fun reduction(containerId: Long): SpaceflightEvent.Reduction =
        SpaceflightEvent.Reduction(
            seq = seq++,
            timeMillis = 0L,
            containerId = containerId,
            intentId = null,
            oldState = "old",
            newState = "new",
            noOp = false,
        )

    private fun sideEffect(containerId: Long): SpaceflightEvent.SideEffect =
        SpaceflightEvent.SideEffect(seq = seq++, timeMillis = 0L, containerId = containerId, intentId = null, value = "se")

    @Test
    fun events_below_capacity_are_all_retained_in_order() {
        val buffer = RecorderBuffer(capacity = 10, minRetainedReductions = 2)
        buffer.attach(1L)
        val events = List(5) { reduction(1L) }
        events.forEach(buffer::append)

        assertEquals(events, buffer.snapshot())
        assertEquals(0L, buffer.droppedEvents)
    }

    @Test
    fun oldest_events_evicted_deterministically_at_capacity() {
        val buffer = RecorderBuffer(capacity = 3, minRetainedReductions = 0)
        buffer.attach(1L)
        val events = List(5) { sideEffect(1L) }
        events.forEach(buffer::append)

        assertEquals(events.drop(2), buffer.snapshot())
        assertEquals(2L, buffer.droppedEvents)
    }

    @Test
    fun chatty_container_cannot_flush_quiet_containers_reductions() {
        val buffer = RecorderBuffer(capacity = 4, minRetainedReductions = 2)
        buffer.attach(QUIET)
        buffer.attach(CHATTY)

        val quietReductions = List(3) { reduction(QUIET) }
        quietReductions.forEach(buffer::append)
        repeat(20) { buffer.append(reduction(CHATTY)) }

        val snapshot = buffer.snapshot()
        val retainedQuiet = snapshot.filter { it.containerId == QUIET }
        // The quiet container's two newest reductions survived global eviction
        assertEquals(quietReductions.drop(1), retainedQuiet)
        // The ring itself only holds the chatty container's newest events
        assertTrue(snapshot.filter { it.containerId == CHATTY }.size >= 2)
    }

    @Test
    fun protected_reductions_bounded_per_container() {
        val buffer = RecorderBuffer(capacity = 2, minRetainedReductions = 3)
        buffer.attach(1L)
        val events = List(10) { reduction(1L) }
        events.forEach(buffer::append)

        // 2 in the ring + 3 protected = newest 5 retained
        assertEquals(events.drop(5), buffer.snapshot())
        assertEquals(5L, buffer.droppedEvents)
    }

    @Test
    fun detach_releases_protection() {
        val buffer = RecorderBuffer(capacity = 2, minRetainedReductions = 2)
        buffer.attach(1L)
        repeat(6) { buffer.append(reduction(1L)) }
        assertEquals(4, buffer.snapshot().size)

        buffer.detach(1L)
        assertEquals(2, buffer.snapshot().size)
        assertEquals(4L, buffer.droppedEvents)
    }

    @Test
    fun detached_containers_reductions_are_not_protected() {
        val buffer = RecorderBuffer(capacity = 2, minRetainedReductions = 2)
        val events = List(4) { reduction(1L) }
        events.forEach(buffer::append)

        // Never attached: plain ring semantics
        assertEquals(events.drop(2), buffer.snapshot())
    }

    @Test
    fun snapshot_is_ordered_by_seq_across_ring_and_protected_events() {
        val buffer = RecorderBuffer(capacity = 3, minRetainedReductions = 2)
        buffer.attach(QUIET)
        buffer.attach(CHATTY)
        buffer.append(reduction(QUIET))
        buffer.append(reduction(CHATTY))
        buffer.append(reduction(QUIET))
        repeat(10) { buffer.append(reduction(CHATTY)) }

        val seqs = buffer.snapshot().map { it.seq }
        assertEquals(seqs.sorted(), seqs)
    }

    @Test
    fun clear_resets_events_and_drop_count() {
        val buffer = RecorderBuffer(capacity = 2, minRetainedReductions = 1)
        buffer.attach(1L)
        repeat(5) { buffer.append(reduction(1L)) }
        buffer.clear()

        assertEquals(emptyList(), buffer.snapshot())
        assertEquals(0L, buffer.droppedEvents)

        val next = reduction(1L)
        buffer.append(next)
        assertEquals(listOf<SpaceflightEvent>(next), buffer.snapshot())
    }

    private companion object {
        const val QUIET = 1L
        const val CHATTY = 2L
    }
}

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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recorder's locking design is otherwise only exercised under single-threaded test
 * schedulers - this is the real-threads stress the whole design must survive.
 */
internal class FlightRecorderThreadingTest {

    @Test
    fun concurrent_appends_and_snapshots_stay_consistent() {
        val recorder = FlightRecorder(
            FlightRecorderConfig.Builder().apply {
                capacity = WRITERS * EVENTS_PER_WRITER + WRITERS // room for attach events
                timeSource = { 0L }
            }.build()
        )
        val observer = recorder.eventObserver

        val start = CountDownLatch(1)
        val writersDone = AtomicBoolean(false)
        val snapshotFailures = mutableListOf<String>()

        // Writers: each its own container, each appending reductions as fast as possible
        val writers = (0 until WRITERS).map { writerIndex ->
            thread(name = "writer-$writerIndex") {
                val containerId = 1_000L + writerIndex
                observer.onContainerCreated(
                    org.orbitmvi.orbit.observer.ContainerInfo(containerId, "w$writerIndex", 0)
                )
                start.await()
                repeat(EVENTS_PER_WRITER) { i ->
                    observer.onReduction(containerId, intentId = null, oldState = i, newState = i + 1)
                }
            }
        }

        // Snapshotters: read continuously while writers hammer the lock
        val snapshotters = (0 until SNAPSHOTTERS).map { snapshotterIndex ->
            thread(name = "snapshotter-$snapshotterIndex") {
                start.await()
                while (!writersDone.get()) {
                    val snapshot = recorder.snapshot()
                    // A consistent snapshot is strictly ordered with no duplicate seqs
                    // (the gap marker is seq -1 and exempt)
                    val seqs = snapshot.events.filter { it.seq >= 0 }.map { it.seq }
                    if (seqs != seqs.sorted() || seqs.size != seqs.toSet().size) {
                        synchronized(snapshotFailures) {
                            snapshotFailures += "inconsistent snapshot: ${seqs.size} events"
                        }
                        return@thread
                    }
                }
            }
        }

        start.countDown()
        writers.forEach { it.join() }
        writersDone.set(true)
        snapshotters.forEach { it.join() }

        assertTrue(snapshotFailures.isEmpty(), snapshotFailures.joinToString())

        // Every event was recorded exactly once with a unique seq
        val final = recorder.snapshot()
        assertEquals(0, final.droppedEvents)
        val reductions = final.events.filterIsInstance<SpaceflightEvent.Reduction>()
        assertEquals(WRITERS * EVENTS_PER_WRITER, reductions.size)
        assertEquals(reductions.size + WRITERS, final.events.map { it.seq }.toSet().size)

        // Per container, the recorded transitions reconstruct the exact 0..N chain
        reductions.groupBy { it.containerId }.forEach { (_, containerReductions) ->
            val ordered = containerReductions.sortedBy { it.oldState as Int }
            ordered.forEachIndexed { index, reduction ->
                assertEquals(index, reduction.oldState)
                assertEquals(index + 1, reduction.newState)
            }
        }
    }

    @Test
    fun clear_races_with_appends_without_losing_generation_bumps() {
        val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 0L } }.build())
        val observer = recorder.eventObserver
        observer.onContainerCreated(org.orbitmvi.orbit.observer.ContainerInfo(1L, "c", 0))

        val start = CountDownLatch(1)
        val appender = thread {
            start.await()
            repeat(EVENTS_PER_WRITER) { i -> observer.onReduction(1L, null, i, i + 1) }
        }
        val clearer = thread {
            start.await()
            repeat(CLEARS) { recorder.clear() }
        }

        start.countDown()
        appender.join()
        clearer.join()

        // Every clear bumped the generation - a busy app can't mask a clear
        assertEquals(CLEARS.toLong(), recorder.snapshot().generation)
    }

    private companion object {
        const val WRITERS = 8
        const val SNAPSHOTTERS = 4
        const val EVENTS_PER_WRITER = 5_000
        const val CLEARS = 100
    }
}

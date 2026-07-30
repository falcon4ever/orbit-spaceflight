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

package net.multigesture.spaceflight.internal

import net.multigesture.spaceflight.SpaceflightEvent

/**
 * The global event ring with per-container minimum retention.
 *
 * Eviction rule: when the ring exceeds capacity, the oldest event is removed. If that event is
 * a [SpaceflightEvent.Reduction] of a live container, it moves to the container's protected
 * deque (bounded by [minRetainedReductions]) instead of being dropped, so a quiet container
 * always keeps its newest reductions no matter how chatty its neighbours are. Protection ends
 * when the container detaches.
 *
 * Not thread-safe: callers must hold the recorder lock.
 */
internal class RecorderBuffer(
    private val capacity: Int,
    private val minRetainedReductions: Int,
) {
    private val entries = ArrayDeque<SpaceflightEvent>()
    private val protectedReductions = mutableMapOf<Long, ArrayDeque<SpaceflightEvent.Reduction>>()
    private val liveContainers = mutableSetOf<Long>()

    var droppedEvents: Long = 0L
        private set

    fun attach(containerId: Long) {
        liveContainers += containerId
    }

    fun detach(containerId: Long) {
        liveContainers -= containerId
        protectedReductions.remove(containerId)?.let { droppedEvents += it.size }
    }

    fun append(event: SpaceflightEvent) {
        entries.addLast(event)
        while (entries.size > capacity) {
            evictOldest()
        }
    }

    private fun evictOldest() {
        val evicted = entries.removeFirst()
        if (evicted is SpaceflightEvent.Reduction && minRetainedReductions > 0 && evicted.containerId in liveContainers) {
            val retained = protectedReductions.getOrPut(evicted.containerId) { ArrayDeque() }
            retained.addLast(evicted)
            if (retained.size > minRetainedReductions) {
                retained.removeFirst()
                droppedEvents++
            }
        } else {
            droppedEvents++
        }
    }

    /** All retained events in global [SpaceflightEvent.seq] order. */
    fun snapshot(): List<SpaceflightEvent> {
        if (protectedReductions.isEmpty()) return entries.toList()
        return (protectedReductions.values.flatten() + entries).sortedBy { it.seq }
    }

    fun clear() {
        entries.clear()
        protectedReductions.clear()
        droppedEvents = 0L
    }
}

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
import kotlin.test.assertTrue

internal class FlightRecorderGenerationTest {

    private val recorder = FlightRecorder(FlightRecorderConfig.Builder().apply { timeSource = { 0L } }.build())

    @Test
    fun clear_bumps_the_generation_and_keeps_seqs_growing() {
        val observer = recorder.eventObserver
        observer.onContainerCreated(ContainerInfo(1L, "c", 0))
        observer.onReduction(1L, null, 0, 1)
        assertEquals(0L, recorder.snapshot().generation)

        recorder.clear()
        // A busy app appends immediately after the clear - the seqs alone cannot reveal
        // the clear (they keep growing), which is exactly why the generation exists
        observer.onReduction(1L, null, 1, 2)
        val after = recorder.snapshot()

        assertEquals(1L, after.generation)
        val maxSeqBefore = 1L
        assertTrue(after.events.all { it.seq > maxSeqBefore || it.seq < 0 })
    }

    @Test
    fun revision_bumps_on_every_append_and_clear() {
        val observer = recorder.eventObserver
        val before = recorder.revision.value

        observer.onContainerCreated(ContainerInfo(1L, "c", 0))
        observer.onReduction(1L, null, 0, 1)
        recorder.clear()

        assertEquals(before + 3, recorder.revision.value)
    }
}

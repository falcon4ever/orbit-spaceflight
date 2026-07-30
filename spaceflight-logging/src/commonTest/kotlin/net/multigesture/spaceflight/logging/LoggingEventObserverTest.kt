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

package net.multigesture.spaceflight.logging

import org.orbitmvi.orbit.observer.ContainerInfo
import org.orbitmvi.orbit.observer.IntentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class LoggingEventObserverTest {

    private val lines = mutableListOf<String>()

    @Test
    fun logs_structured_lines_for_the_event_lifecycle() {
        val observer = LoggingEventObserver(sink = { lines += it })

        observer.onContainerCreated(ContainerInfo(1L, "Checkout", "Initial"))
        observer.onIntentDispatched(1L, 7L, "Checkout.loadData")
        observer.onReduction(1L, 7L, "Initial", "Loaded")
        observer.onSideEffect(1L, 7L, "Toast")
        observer.onIntentCompleted(1L, 7L, IntentResult.Completed)
        observer.onContainerClosed(1L)

        assertEquals(
            listOf(
                "Checkout#1 created (initial=Initial)",
                "Checkout#1 > intent Checkout.loadData#7 dispatched",
                "Checkout#1 ~ Initial -> Loaded [intent#7]",
                "Checkout#1 ! side effect Toast [intent#7]",
                "Checkout#1 < intent#7 completed",
                "Checkout#1 closed",
            ),
            lines
        )
    }

    @Test
    fun unnamed_containers_and_untracked_intents_fall_back_to_ids() {
        val observer = LoggingEventObserver(sink = { lines += it })

        observer.onReduction(3L, null, "A", "A")

        assertEquals(listOf("container#3 ~ A -> A (no-op)"), lines)
    }

    @Test
    fun failed_intents_log_the_exception() {
        val observer = LoggingEventObserver(sink = { lines += it })

        observer.onIntentCompleted(1L, 2L, IntentResult.Failed(IllegalStateException("boom")))

        assertTrue(lines.single().contains("failed"))
        assertTrue(lines.single().contains("boom"))
    }

    @Test
    fun disabled_logging_builds_no_strings() {
        val observer = LoggingEventObserver(sink = { lines += it }, isEnabled = { false })
        val state = ExplodingToString()

        observer.onReduction(1L, null, state, state)

        assertEquals(emptyList(), lines)
    }

    private class ExplodingToString {
        override fun toString(): String = error("toString must not be called when logging is disabled")
    }
}

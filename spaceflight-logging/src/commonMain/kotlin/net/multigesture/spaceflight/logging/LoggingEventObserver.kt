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

import kotlin.concurrent.atomics.AtomicReference
import org.orbitmvi.orbit.observer.ContainerInfo
import org.orbitmvi.orbit.observer.IntentResult
import org.orbitmvi.orbit.observer.OrbitEventObserver

/** Where log lines go. Defaults to println, which Android forwards to logcat. */
public fun interface LogSink {
    public fun log(message: String)
}

/**
 * Logs a structured one-line message for every Orbit event.
 *
 * Messages are built lazily: when [isEnabled] returns false, no strings are built and states
 * are never toString-ed. Install standalone or alongside the flight recorder via
 * `compositeEventObserver`:
 *
 * ```
 * Orbit.configureDefaults {
 *     eventObserver = LoggingEventObserver(sink = { Log.d("Orbit", it) })
 * }
 * ```
 *
 * Container display names come from `SettingsBuilder.containerName` and fall back to the
 * container id.
 */
public class LoggingEventObserver(
    private val sink: LogSink = LogSink(::println),
    private val isEnabled: () -> Boolean = { true },
) : OrbitEventObserver {

    private val containerNames = AtomicReference<Map<Long, String>>(emptyMap())

    override fun onContainerCreated(containerInfo: ContainerInfo) {
        val label = "${containerInfo.containerName ?: "container"}#${containerInfo.containerId}"
        while (true) {
            val current = containerNames.load()
            if (containerNames.compareAndSet(current, current + (containerInfo.containerId to label))) break
        }
        log { "$label created (initial=${containerInfo.initialState})" }
    }

    override fun onContainerClosed(containerId: Long) {
        log { "${label(containerId)} closed" }
        while (true) {
            val current = containerNames.load()
            if (containerNames.compareAndSet(current, current - containerId)) break
        }
    }

    override fun onIntentDispatched(containerId: Long, intentId: Long, name: String?) {
        log { "${label(containerId)} > intent ${name ?: "intent"}#$intentId dispatched" }
    }

    override fun onIntentCompleted(containerId: Long, intentId: Long, result: IntentResult) {
        log {
            val outcome = when (result) {
                is IntentResult.Completed -> "completed"
                is IntentResult.Cancelled -> "cancelled"
                is IntentResult.Failed -> "failed: ${result.exception}"
            }
            "${label(containerId)} < intent#$intentId $outcome"
        }
    }

    override fun onReduction(containerId: Long, intentId: Long?, oldState: Any, newState: Any) {
        log {
            val attribution = intentId?.let { " [intent#$it]" }.orEmpty()
            val noOp = if (oldState == newState) " (no-op)" else ""
            "${label(containerId)} ~ $oldState -> $newState$attribution$noOp"
        }
    }

    override fun onSideEffect(containerId: Long, intentId: Long?, sideEffect: Any) {
        log {
            val attribution = intentId?.let { " [intent#$it]" }.orEmpty()
            "${label(containerId)} ! side effect $sideEffect$attribution"
        }
    }

    private fun label(containerId: Long): String =
        containerNames.load()[containerId] ?: "container#$containerId"

    private inline fun log(message: () -> String) {
        if (isEnabled()) {
            sink.log(message())
        }
    }
}

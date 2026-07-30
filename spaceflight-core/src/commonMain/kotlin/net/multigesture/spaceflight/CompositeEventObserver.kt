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
import org.orbitmvi.orbit.observer.IntentResult
import org.orbitmvi.orbit.observer.OrbitEventObserver

/**
 * Fans out Orbit events to multiple observers, since Orbit settings hold a single
 * [OrbitEventObserver]. Use to combine the [FlightRecorder] with e.g. the logging observer:
 *
 * ```
 * Orbit.configureDefaults {
 *     eventObserver = compositeEventObserver(recorder.eventObserver, LoggingEventObserver())
 * }
 * ```
 */
public fun compositeEventObserver(vararg observers: OrbitEventObserver): OrbitEventObserver =
    CompositeEventObserver(observers.toList())

private class CompositeEventObserver(
    private val observers: List<OrbitEventObserver>,
) : OrbitEventObserver {

    override fun onContainerCreated(containerInfo: ContainerInfo) {
        observers.forEach { it.onContainerCreated(containerInfo) }
    }

    override fun onContainerClosed(containerId: Long) {
        observers.forEach { it.onContainerClosed(containerId) }
    }

    override fun onIntentDispatched(containerId: Long, intentId: Long, name: String?) {
        observers.forEach { it.onIntentDispatched(containerId, intentId, name) }
    }

    override fun onIntentCompleted(containerId: Long, intentId: Long, result: IntentResult) {
        observers.forEach { it.onIntentCompleted(containerId, intentId, result) }
    }

    override fun onReduction(containerId: Long, intentId: Long?, oldState: Any, newState: Any) {
        observers.forEach { it.onReduction(containerId, intentId, oldState, newState) }
    }

    override fun onSideEffect(containerId: Long, intentId: Long?, sideEffect: Any) {
        observers.forEach { it.onSideEffect(containerId, intentId, sideEffect) }
    }
}

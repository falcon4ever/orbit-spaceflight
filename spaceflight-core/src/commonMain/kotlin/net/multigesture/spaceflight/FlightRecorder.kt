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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.orbitmvi.orbit.observer.ContainerInfo
import org.orbitmvi.orbit.observer.IntentResult
import org.orbitmvi.orbit.observer.OrbitEventObserver
import net.multigesture.spaceflight.internal.RecorderBuffer
import net.multigesture.spaceflight.internal.RecorderLock
import net.multigesture.spaceflight.internal.withLock

/**
 * Always-on, bounded, in-memory recording of Orbit events across all containers — the engine
 * behind the Orbit Spaceflight flight recorder.
 *
 * There is deliberately no start/stop: when a bug happens you want the events from *before*
 * you thought to press record. The ring is bounded by [FlightRecorderConfig.capacity] with
 * per-container minimum retention, so memory is capped and quiet containers keep their recent
 * history.
 *
 * Wire it into Orbit by installing [eventObserver]:
 *
 * ```
 * val recorder = OrbitSpaceflight.install()
 * Orbit.configureDefaults {
 *     eventObserver = recorder.eventObserver
 * }
 * ```
 *
 * Recording appends a small event under a lock (microseconds); no I/O, serialization or
 * rendering ever happens on this path.
 */
public class FlightRecorder internal constructor(
    private val config: FlightRecorderConfig,
) {
    private val lock = RecorderLock()
    private val buffer = RecorderBuffer(config.capacity, config.minRetainedReductionsPerContainer)
    private val containers = mutableMapOf<Long, RecordedContainer>()
    private val excludedContainerIds = mutableSetOf<Long>()
    private var nextSeq = 0L
    private var generation = 0L
    private val _revision = MutableStateFlow(0L)

    /**
     * Bumps after every recorded event and every [clear]. Collect it and call [snapshot] on
     * each emission instead of polling: quiet apps cost nothing and busy apps coalesce
     * naturally (StateFlow conflates). The value itself is only a change signal.
     */
    public val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Install on Orbit containers, e.g. via `Orbit.configureDefaults { eventObserver = ... }`. */
    public val eventObserver: OrbitEventObserver = RecorderEventObserver()

    /** The currently live (attached) containers, newest first. */
    public fun liveContainers(): List<RecordedContainer> = lock.withLock {
        containers.values.toList()
    }

    /**
     * A consistent copy of the retained events. When events have been evicted beyond the
     * per-container retention, the recording starts with a synthetic
     * [SpaceflightEvent.Diagnostic] gap marker (seq -1).
     */
    public fun snapshot(): FlightRecording = lock.withLock {
        val events = buffer.snapshot()
        val dropped = buffer.droppedEvents
        val withGapMarker = if (dropped > 0) {
            listOf(
                SpaceflightEvent.Diagnostic(
                    seq = GAP_MARKER_SEQ,
                    timeMillis = config.timeSource(),
                    containerId = null,
                    message = "$dropped earlier event(s) evicted from the ring buffer",
                )
            ) + events
        } else {
            events
        }
        FlightRecording(events = withGapMarker, droppedEvents = dropped, generation = generation)
    }

    /** Clears all retained events. Live container registrations are kept. */
    public fun clear() {
        // The generation is how consumers detect a clear: inferring one from shrinking seqs
        // misses clears that are immediately followed by new events on a busy app
        lock.withLock {
            buffer.clear()
            generation++
        }
        _revision.update { it + 1 }
    }

    /** Records a marker visible in every timeline view, e.g. tooling degradations. */
    internal fun recordDiagnostic(message: String, containerId: Long? = null) {
        val time = config.timeSource()
        lock.withLock {
            buffer.append(
                SpaceflightEvent.Diagnostic(
                    seq = nextSeq++,
                    timeMillis = time,
                    containerId = containerId,
                    message = message,
                )
            )
        }
        _revision.update { it + 1 }
    }

    private inner class RecorderEventObserver : OrbitEventObserver {
        override fun onContainerCreated(containerInfo: ContainerInfo) {
            val time = config.timeSource()
            lock.withLock {
                if (containerInfo.containerName in config.excludedContainerNames) {
                    excludedContainerIds += containerInfo.containerId
                    return
                }
                containers[containerInfo.containerId] = RecordedContainer(
                    containerId = containerInfo.containerId,
                    name = containerInfo.containerName,
                    attachedAtMillis = time,
                )
                buffer.attach(containerInfo.containerId)
                buffer.append(
                    SpaceflightEvent.ContainerAttached(
                        seq = nextSeq++,
                        timeMillis = time,
                        containerId = containerInfo.containerId,
                        name = containerInfo.containerName,
                        initialState = containerInfo.initialState,
                    )
                )
            }
            _revision.update { it + 1 }
        }

        override fun onContainerClosed(containerId: Long) {
            val time = config.timeSource()
            lock.withLock {
                if (excludedContainerIds.remove(containerId)) return
                containers.remove(containerId)
                buffer.detach(containerId)
                buffer.append(
                    SpaceflightEvent.ContainerDetached(
                        seq = nextSeq++,
                        timeMillis = time,
                        containerId = containerId,
                    )
                )
            }
            _revision.update { it + 1 }
        }

        override fun onIntentDispatched(containerId: Long, intentId: Long, name: String?) {
            val time = config.timeSource()
            lock.withLock {
                if (containerId in excludedContainerIds) return
                buffer.append(
                    SpaceflightEvent.IntentDispatched(
                        seq = nextSeq++,
                        timeMillis = time,
                        containerId = containerId,
                        intentId = intentId,
                        name = name,
                    )
                )
            }
            _revision.update { it + 1 }
        }

        override fun onIntentCompleted(containerId: Long, intentId: Long, result: IntentResult) {
            val time = config.timeSource()
            lock.withLock {
                if (containerId in excludedContainerIds) return
                buffer.append(
                    SpaceflightEvent.IntentCompleted(
                        seq = nextSeq++,
                        timeMillis = time,
                        containerId = containerId,
                        intentId = intentId,
                        result = result,
                    )
                )
            }
            _revision.update { it + 1 }
        }

        override fun onReduction(containerId: Long, intentId: Long?, oldState: Any, newState: Any) {
            val time = config.timeSource()
            val noOp = oldState == newState
            lock.withLock {
                if (containerId in excludedContainerIds) return
                buffer.append(
                    SpaceflightEvent.Reduction(
                        seq = nextSeq++,
                        timeMillis = time,
                        containerId = containerId,
                        intentId = intentId,
                        oldState = oldState,
                        newState = newState,
                        noOp = noOp,
                    )
                )
            }
            _revision.update { it + 1 }
        }

        override fun onSideEffect(containerId: Long, intentId: Long?, sideEffect: Any) {
            val time = config.timeSource()
            lock.withLock {
                if (containerId in excludedContainerIds) return
                buffer.append(
                    SpaceflightEvent.SideEffect(
                        seq = nextSeq++,
                        timeMillis = time,
                        containerId = containerId,
                        intentId = intentId,
                        value = sideEffect,
                    )
                )
            }
            _revision.update { it + 1 }
        }
    }

    private companion object {
        private const val GAP_MARKER_SEQ = -1L
    }
}

/**
 * A live container known to the recorder.
 */
public data class RecordedContainer(
    public val containerId: Long,
    public val name: String?,
    public val attachedAtMillis: Long,
)

/**
 * A consistent snapshot of the recorder's retained events, in global sequence order.
 *
 * @property events retained events; starts with a synthetic gap [SpaceflightEvent.Diagnostic]
 * when [droppedEvents] is non-zero.
 * @property droppedEvents how many events have been evicted beyond per-container retention
 * since the last [FlightRecorder.clear].
 * @property generation bumped by every [FlightRecorder.clear]; a changed generation means the
 * recording restarted, however the seq numbers compare.
 */
public data class FlightRecording(
    public val events: List<SpaceflightEvent>,
    public val droppedEvents: Long,
    public val generation: Long = 0,
)

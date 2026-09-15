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

    // Per-container application-order chains. Orbit reports each reduction's exact old->new
    // pair but racing reductions may *arrive* in either order, so a reduction is appended only
    // once its oldState is the state the container actually held (identity, or equality for
    // the flow's own no-op deduplication). Anything else waits, briefly, in `pending`.
    private val chains = mutableMapOf<Long, ReductionChain>()
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

    /** The currently live (attached) containers, in attach order. */
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
            // Whatever was waiting for a predecessor is gone with the recording; the next
            // reduction re-anchors the chain
            chains.values.forEach { it.reset() }
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

    // Must be called under `lock`
    private fun appendReduction(
        containerId: Long,
        timeMillis: Long,
        intentId: Long?,
        oldState: Any,
        newState: Any,
        noOp: Boolean,
    ) {
        buffer.append(
            SpaceflightEvent.Reduction(
                seq = nextSeq++,
                timeMillis = timeMillis,
                containerId = containerId,
                intentId = intentId,
                oldState = oldState,
                newState = newState,
                noOp = noOp,
            )
        )
    }

    // Must be called under `lock`
    private fun appendReduction(containerId: Long, reduction: PendingReduction) = appendReduction(
        containerId, reduction.timeMillis, reduction.intentId, reduction.oldState, reduction.newState, reduction.noOp,
    )

    // Must be called under `lock`. Appends every pending reduction whose predecessor has now
    // been applied, in chain order.
    private fun drainPending(chain: ReductionChain, containerId: Long) {
        while (chain.pending.isNotEmpty()) {
            val next = chain.pending.indexOfFirst { chain.accepts(it.oldState) }
            if (next < 0) return
            val reduction = chain.pending.removeAt(next)
            appendReduction(containerId, reduction)
            chain.advance(reduction.noOp, reduction.oldState, reduction.newState)
        }
    }

    // Must be called under `lock`. The chain is broken (a predecessor will never arrive):
    // say so in the timeline, append what we have in arrival order and re-anchor on the newest.
    private fun flushOutOfOrder(chain: ReductionChain, containerId: Long) {
        if (chain.pending.isEmpty()) return
        buffer.append(
            SpaceflightEvent.Diagnostic(
                seq = nextSeq++,
                timeMillis = config.timeSource(),
                containerId = containerId,
                message = "${chain.pending.size} reduction(s) could not be placed in application " +
                    "order (predecessor never arrived); recorded in arrival order",
            )
        )
        chain.pending.forEach { appendReduction(containerId, it) }
        chain.pending.last().let { chain.advance(it.noOp, it.oldState, it.newState) }
        chain.pending.clear()
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
                chains[containerInfo.containerId] = ReductionChain(containerInfo.initialState)
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
                chains.remove(containerId)?.let { flushOutOfOrder(it, containerId) }
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
            val appended = lock.withLock {
                if (containerId in excludedContainerIds) return
                val chain = chains[containerId]
                when {
                    // In order (the overwhelmingly common case), or an unknown container with
                    // nothing to link to: one identity compare, then append as before
                    chain == null || chain.accepts(oldState) -> {
                        appendReduction(containerId, time, intentId, oldState, newState, noOp)
                        if (chain != null) {
                            chain.advance(noOp, oldState, newState)
                            if (chain.pending.isNotEmpty()) drainPending(chain, containerId)
                        }
                        true
                    }
                    else -> {
                        chain.pending.addLast(PendingReduction(time, intentId, oldState, newState, noOp))
                        if (chain.pending.size > MAX_PENDING_REDUCTIONS) {
                            flushOutOfOrder(chain, containerId)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            if (appended) _revision.update { it + 1 }
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

        /** Racing reductions resolve within a handful of events; more than this means a broken chain. */
        private const val MAX_PENDING_REDUCTIONS = 32
    }
}

/** A reduction that has arrived but not yet been given a seq. */
private class PendingReduction(
    val timeMillis: Long,
    val intentId: Long?,
    val oldState: Any,
    val newState: Any,
    val noOp: Boolean,
)

/**
 * The application-order chain of one container's reductions: the state the container currently
 * holds (as far as the recording knows) and reductions that arrived before their predecessor.
 */
private class ReductionChain(initialState: Any) {
    /** Last applied state, or null when unknown (after [reset]); null accepts anything and re-anchors. */
    private var last: Any? = initialState
    val pending = ArrayDeque<PendingReduction>()

    fun accepts(oldState: Any): Boolean {
        val current = last ?: return true
        // Identity is the production case: Orbit passes the flow's stored instance as oldState.
        // Equality covers the flow's own deduplication (a no-op reduction's new instance is
        // never stored) and states that are value-equal by construction, e.g. boxed primitives.
        return current === oldState || current == oldState
    }

    fun advance(noOp: Boolean, oldState: Any, newState: Any) {
        // The flow does not store a value-equal new instance, so after a no-op the container
        // still holds the old instance
        last = if (noOp) oldState else newState
    }

    fun reset() {
        last = null
        pending.clear()
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

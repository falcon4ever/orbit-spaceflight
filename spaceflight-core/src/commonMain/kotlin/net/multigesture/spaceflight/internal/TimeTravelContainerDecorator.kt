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

// The one place Spaceflight couples to Orbit internals (see README "Coupling to Orbit
// internals"): keep this opt-in file-level so new internal-API usage can't creep in silently
@file:OptIn(OrbitInternal::class)

package net.multigesture.spaceflight.internal

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerDecorator
import org.orbitmvi.orbit.annotation.OrbitExperimental
import org.orbitmvi.orbit.annotation.OrbitInternal
import org.orbitmvi.orbit.syntax.ContainerContext
import net.multigesture.spaceflight.TimeTravelMode

/**
 * The dual-state trick behind Retrograde: the decorator owns *displayed* state flows that
 * mirror the real container while LIVE and detach from it while INSPECTING, when the cursor
 * drives them through recorded snapshots instead.
 *
 * While INSPECTING:
 * - in-flight intents keep running against real state (the live tail, still recorded)
 * - new [orbit] dispatches are queued and dispatched in order on resume
 * - side effects are held by suspending the delivery collector, so they buffer in the
 *   container's own side effect cache with its existing bounds, and release on resume
 * - subscription counts still reach the inner refCount flows, so `repeatOnSubscription`
 *   blocks survive inspection
 */
internal class TimeTravelContainerDecorator<INTERNAL_STATE : Any, EXTERNAL_STATE : Any, SIDE_EFFECT : Any>(
    override val actual: OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT>,
    internal val containerId: Long,
    private val transformState: (INTERNAL_STATE) -> EXTERNAL_STATE,
) : OrbitContainerDecorator<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT> {

    private val lock = RecorderLock()
    private val mode = MutableStateFlow(TimeTravelMode.LIVE)
    private val displayed = MutableStateFlow(actual.stateFlow.value)
    private val displayedExternal = MutableStateFlow(transformState(actual.stateFlow.value))
    private val queuedIntents = ArrayDeque<QueuedIntent<INTERNAL_STATE, SIDE_EFFECT>>()

    override val stateFlow: StateFlow<INTERNAL_STATE> =
        SwitchingStateFlow(mode, live = { actual.stateFlow }, frozen = displayed)

    override val refCountStateFlow: StateFlow<INTERNAL_STATE> =
        SwitchingStateFlow(
            mode,
            live = { actual.refCountStateFlow },
            frozen = displayed,
            frozenKeepAlive = { actual.refCountStateFlow },
        )

    override val externalStateFlow: StateFlow<EXTERNAL_STATE> =
        SwitchingStateFlow(mode, live = { actual.externalStateFlow }, frozen = displayedExternal)

    override val externalRefCountStateFlow: StateFlow<EXTERNAL_STATE> =
        SwitchingStateFlow(
            mode,
            live = { actual.externalRefCountStateFlow },
            frozen = displayedExternal,
            frozenKeepAlive = { actual.refCountStateFlow },
        )

    override val sideEffectFlow: Flow<SIDE_EFFECT> = gated(actual.sideEffectFlow)

    override val refCountSideEffectFlow: Flow<SIDE_EFFECT> = gated(actual.refCountSideEffectFlow)

    override fun orbit(orbitIntent: suspend ContainerContext<INTERNAL_STATE, SIDE_EFFECT>.() -> Unit): Job =
        orbit(null, orbitIntent)

    @OrbitExperimental
    override fun orbit(name: String?, orbitIntent: suspend ContainerContext<INTERNAL_STATE, SIDE_EFFECT>.() -> Unit): Job {
        lock.withLock {
            if (mode.value == TimeTravelMode.INSPECTING) {
                val queued = QueuedIntent(Job(), name, orbitIntent)
                queuedIntents.addLast(queued)
                return queued.job
            }
        }
        return actual.orbit(name, orbitIntent)
    }

    internal fun freeze() {
        // transformState is user code of arbitrary cost: run it outside the lock (a spin
        // lock on native - unbounded spinning must never wait on user code). A state that
        // advances between read and lock was already indistinguishable from a slightly
        // earlier freeze: in-flight intents keep mutating real state either way.
        if (mode.value == TimeTravelMode.INSPECTING) return
        val current = actual.stateFlow.value
        val external = transformState(current)
        lock.withLock {
            if (mode.value == TimeTravelMode.INSPECTING) return
            displayed.value = current
            displayedExternal.value = external
            mode.value = TimeTravelMode.INSPECTING
        }
    }

    /** One displayed write per cursor move, and only when the projection actually changed. */
    internal fun showState(state: Any) {
        @Suppress("UNCHECKED_CAST")
        val typed = state as INTERNAL_STATE
        if (mode.value != TimeTravelMode.INSPECTING || displayed.value == typed) return
        val external = transformState(typed) // user code - outside the lock, see freeze()
        lock.withLock {
            if (mode.value != TimeTravelMode.INSPECTING) return
            displayed.value = typed
            displayedExternal.value = external
        }
    }

    /** Derives the external state for a recorded internal [state] of this container. */
    internal fun renderExternal(state: Any): Any {
        @Suppress("UNCHECKED_CAST")
        return transformState(state as INTERNAL_STATE)
    }

    internal fun resumeLive() {
        val toDispatch = lock.withLock {
            if (mode.value == TimeTravelMode.LIVE) return
            mode.value = TimeTravelMode.LIVE
            val drained = queuedIntents.toList()
            queuedIntents.clear()
            drained
        }
        toDispatch.forEach { queued ->
            // A queued intent whose Job was cancelled while frozen (e.g. its screen went
            // away) must not fire on resume
            if (queued.job.isCancelled) return@forEach
            @OptIn(OrbitExperimental::class)
            val realJob = actual.orbit(queued.name, queued.orbitIntent)
            realJob.invokeOnCompletion { queued.job.complete() }
            queued.job.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) realJob.cancel()
            }
        }
    }

    /**
     * Holds delivery while INSPECTING by suspending the collector: pending side effects stay
     * in the container's own cache (its existing bounds and backpressure apply) and flow
     * again on resume, in order.
     */
    private fun gated(upstream: Flow<SIDE_EFFECT>): Flow<SIDE_EFFECT> = flow {
        upstream.collect { sideEffect ->
            mode.first { it == TimeTravelMode.LIVE }
            emit(sideEffect)
        }
    }
}

private class QueuedIntent<S : Any, SE : Any>(
    val job: CompletableJob,
    val name: String?,
    val orbitIntent: suspend ContainerContext<S, SE>.() -> Unit,
)

/**
 * A [StateFlow] view that follows the live flow while LIVE and the frozen displayed flow
 * while INSPECTING. The live flow is looked up lazily so the decorator never subscribes
 * before a real consumer does (preserving lazy onCreate semantics). [frozenKeepAlive] is
 * collected (and discarded) alongside the frozen branch so the container's subscribed
 * counter still sees the consumer during inspection.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
private class SwitchingStateFlow<T : Any>(
    private val mode: StateFlow<TimeTravelMode>,
    private val live: () -> StateFlow<T>,
    private val frozen: StateFlow<T>,
    private val frozenKeepAlive: (() -> Flow<*>)? = null,
) : StateFlow<T> {

    override val value: T
        get() = if (mode.value == TimeTravelMode.LIVE) live().value else frozen.value

    override val replayCache: List<T>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var previous: Any? = null
        mode
            .flatMapLatest { current ->
                when (current) {
                    TimeTravelMode.LIVE -> live()
                    TimeTravelMode.INSPECTING -> frozenWithKeepAlive()
                }
            }
            .collect { state ->
                if (previous == null || previous != state) {
                    previous = state
                    collector.emit(state)
                }
            }
        awaitCancellation()
    }

    private fun frozenWithKeepAlive(): Flow<T> {
        val keepAlive = frozenKeepAlive ?: return frozen
        return flow {
            coroutineScope {
                val driver = launch { keepAlive().collect {} }
                try {
                    emitAll(frozen)
                } finally {
                    driver.cancel()
                }
            }
        }
    }
}

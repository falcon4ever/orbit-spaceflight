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

package net.multigesture.spaceflight

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.orbitmvi.orbit.ContainerDecoration
import org.orbitmvi.orbit.annotation.OrbitInternal
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerDecorator
import org.orbitmvi.orbit.internal.RealContainer
import org.orbitmvi.orbit.internal.TestContainerDecorator
import net.multigesture.spaceflight.internal.RecorderLock
import net.multigesture.spaceflight.internal.TimeTravelContainerDecorator
import net.multigesture.spaceflight.internal.withLock

/**
 * Retrograde — time travel over the flight recording, the orbital-mechanics way: moving
 * backward along an orbit.
 *
 * Install [containerDecoration] alongside the recorder's observer and drive it through the
 * [TimeTravel] surface:
 *
 * ```
 * val recorder = OrbitSpaceflight.install()
 * val retrograde = Retrograde(recorder)
 * Orbit.configureDefaults {
 *     eventObserver = recorder.eventObserver
 *     containerDecoration = retrograde.containerDecoration
 * }
 * ```
 *
 * Navigation follows the plan's projection rule: the cursor moves over the global sequence
 * of recorded reductions; each container displays the `newState` of its latest reduction at
 * or before the cursor, else the `oldState` of its earliest retained reduction after the
 * cursor, else it keeps its frozen state.
 */
public class Retrograde(
    private val recorder: FlightRecorder,
) : TimeTravel {

    private val lock = RecorderLock()
    private val decorators = mutableMapOf<Long, TimeTravelContainerDecorator<*, *, *>>()
    private var cursor: Long? = null

    private val _state = MutableStateFlow(TimeTravelState())
    override val state: StateFlow<TimeTravelState> = _state.asStateFlow()

    /** Install via `SettingsBuilder.containerDecoration` on every container to time travel. */
    public val containerDecoration: ContainerDecoration = RetrogradeDecoration()

    override fun inspect() {
        // freeze() runs user transformState: keep it outside this lock (spin lock on
        // native). Publishing INSPECTING first means containers created in the gap join
        // frozen via decorate(); each freeze() is individually guarded and idempotent.
        val toFreeze = lock.withLock {
            if (_state.value.mode == TimeTravelMode.INSPECTING) return
            val reductions = reductions()
            cursor = reductions.lastOrNull()?.seq
            publish(reductions)
            decorators.values.toList()
        }
        toFreeze.forEach { it.freeze() }
    }

    override fun resume() {
        val toResume = lock.withLock {
            if (_state.value.mode == TimeTravelMode.LIVE) return
            cursor = null
            _state.value = TimeTravelState(mode = TimeTravelMode.LIVE)
            decorators.values.toList()
        }
        toResume.forEach { it.resumeLive() }
    }

    override fun stepBackward() {
        navigate { reductions, current ->
            reductions.lastOrNull { it.seq < current }?.seq ?: beforeAll(reductions)
        }
    }

    override fun stepForward() {
        navigate { reductions, current ->
            reductions.firstOrNull { it.seq > current }?.seq ?: current
        }
    }

    override fun moveToStart() {
        navigate { reductions, _ -> beforeAll(reductions) }
    }

    override fun moveToEnd() {
        navigate { reductions, current -> reductions.lastOrNull()?.seq ?: current }
    }

    override fun seekTo(seq: Long) {
        navigate { reductions, current ->
            reductions.lastOrNull { it.seq <= seq }?.seq ?: beforeAll(reductions).takeIf { reductions.isNotEmpty() } ?: current
        }
    }

    /**
     * Derives the external state a recorded internal state maps to, via the container's own
     * transformState — external state is never recorded, it is recomputed at display time.
     * Returns null for unknown containers or when the state is not this container's type.
     */
    public fun externalStateOf(containerId: Long, internalState: Any): Any? {
        val decorator = lock.withLock { decorators[containerId] } ?: return null
        return runCatching { decorator.renderExternal(internalState) }.getOrNull()
    }

    override fun clear() {
        lock.withLock {
            recorder.clear()
            if (_state.value.mode == TimeTravelMode.INSPECTING) {
                cursor = null
                publish(emptyList())
            }
        }
    }

    private fun navigate(target: (reductions: List<SpaceflightEvent.Reduction>, current: Long) -> Long) {
        // Projection targets are computed under the lock; showState (which runs user
        // transformState) is applied outside it. A command racing another may display one
        // stale frame, which the next command corrects - unbounded spinning on user code
        // (the native lock is a spin lock) would be strictly worse.
        val toShow = lock.withLock {
            if (_state.value.mode != TimeTravelMode.INSPECTING) return
            val reductions = reductions()
            val current = cursor ?: reductions.lastOrNull()?.seq ?: return
            cursor = target(reductions, current)
            publish(reductions)
            projectionTargets(reductions)
        }
        toShow.forEach { (decorator, projected) -> decorator.showState(projected) }
    }

    private fun projectionTargets(
        reductions: List<SpaceflightEvent.Reduction>,
    ): List<Pair<TimeTravelContainerDecorator<*, *, *>, Any>> {
        val currentCursor = cursor ?: return emptyList()
        val byContainer = reductions.groupBy { it.containerId }
        return decorators.mapNotNull { (containerId, decorator) ->
            val containerReductions = byContainer[containerId] ?: return@mapNotNull null
            val projected = containerReductions.lastOrNull { it.seq <= currentCursor }?.newState
                ?: containerReductions.first().oldState
            decorator to projected
        }
    }

    private fun reductions(): List<SpaceflightEvent.Reduction> =
        recorder.snapshot().events.filterIsInstance<SpaceflightEvent.Reduction>()

    private fun beforeAll(reductions: List<SpaceflightEvent.Reduction>): Long =
        (reductions.firstOrNull()?.seq ?: 0L) - 1L

    private fun publish(reductions: List<SpaceflightEvent.Reduction>) {
        val currentCursor = cursor
        _state.value = TimeTravelState(
            mode = TimeTravelMode.INSPECTING,
            cursorSeq = currentCursor,
            cursorPosition = if (currentCursor == null) 0 else reductions.count { it.seq <= currentCursor },
            reductionCount = reductions.size,
        )
    }

    private inner class RetrogradeDecoration : ContainerDecoration {
        override fun <INTERNAL_STATE : Any, EXTERNAL_STATE : Any, SIDE_EFFECT : Any> decorate(
            container: OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT>
        ): OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT> {
            // If either chain walk fails, the container keeps recording but cannot freeze.
            // That must never be silent: the timeline says so, in the timeline itself.
            val real = container.findRealContainer() ?: return container.also {
                recorder.recordDiagnostic(
                    "Retrograde could not decorate a container (no RealContainer in its " +
                        "decorator chain) - it will keep recording but will not freeze"
                )
            }
            val transform = container.findTransformState() ?: return container.also {
                recorder.recordDiagnostic(
                    "Retrograde could not decorate container #${real.containerId} (no " +
                        "TestContainerDecorator in its chain) - it will keep recording but " +
                        "will not freeze",
                    containerId = real.containerId,
                )
            }
            val decorator = TimeTravelContainerDecorator(container, real.containerId, transform)

            val joinInspecting = lock.withLock {
                decorators[real.containerId] = decorator
                _state.value.mode == TimeTravelMode.INSPECTING
            }
            if (joinInspecting) {
                // A container created mid-inspection joins frozen, like everyone else -
                // freeze() runs user transformState, so outside the lock
                decorator.freeze()
            }
            real.scope.coroutineContext[Job]?.invokeOnCompletion {
                lock.withLock { decorators.remove(real.containerId) }
            }
            return decorator
        }
    }
}

private fun <INTERNAL_STATE : Any, EXTERNAL_STATE : Any, SIDE_EFFECT : Any>
    OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT>.findRealContainer():
    RealContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT>? {
    var current: OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT> = this
    while (true) {
        current = when (current) {
            is RealContainer -> return current
            is OrbitContainerDecorator -> current.actual
            else -> return null
        }
    }
}

private fun <INTERNAL_STATE : Any, EXTERNAL_STATE : Any, SIDE_EFFECT : Any>
    OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT>.findTransformState():
    ((INTERNAL_STATE) -> EXTERNAL_STATE)? {
    var current: OrbitContainer<INTERNAL_STATE, EXTERNAL_STATE, SIDE_EFFECT> = this
    while (true) {
        current = when (current) {
            is TestContainerDecorator -> return current.originalTransformState
            is OrbitContainerDecorator -> current.actual
            else -> return null
        }
    }
}

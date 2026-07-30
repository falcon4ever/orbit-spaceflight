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

package net.multigesture.spaceflight.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.orbitContainer
import net.multigesture.spaceflight.FlightRecorderConfig
import net.multigesture.spaceflight.OrbitSpaceflight

/**
 * Micro-benchmark for the reduction hot path: observer-null (today's exact path) vs recording
 * into the flight recorder at steady state (ring full, eviction active).
 *
 * Each op performs [REDUCTIONS_PER_OP] reductions through inlineOrbit, so per-reduction cost is
 * score / 100. A dispatched-intent pair measures the full orbit() dispatch path per op.
 */
@State(Scope.Benchmark)
open class ReductionBenchmark {

    private lateinit var scope: CoroutineScope
    private lateinit var unobserved: OrbitContainer<BenchState, BenchState, Nothing>
    private lateinit var recorded: OrbitContainer<BenchState, BenchState, Nothing>

    @Setup
    fun setup() {
        OrbitSpaceflight.uninstall()
        scope = CoroutineScope(SupervisorJob())
        val recorder = OrbitSpaceflight.install()
        unobserved = scope.orbitContainer(BenchState())
        recorded = scope.orbitContainer(
            initialState = BenchState(),
            buildSettings = {
                containerName = "Bench"
                eventObserver = recorder.eventObserver
            }
        )
        // Reach recording steady state: ring full so eviction cost is included
        runBlocking {
            recorded.inlineOrbit {
                repeat(FlightRecorderConfig.DEFAULT_CAPACITY + 1) {
                    reduce { it.next() }
                }
            }
        }
    }

    @TearDown
    fun tearDown() {
        scope.cancel()
        OrbitSpaceflight.uninstall()
    }

    @Benchmark
    fun reduce_observer_null(): BenchState = runBlocking {
        unobserved.inlineOrbit {
            repeat(REDUCTIONS_PER_OP) {
                reduce { it.next() }
            }
        }
        unobserved.stateFlow.value
    }

    @Benchmark
    fun reduce_recording(): BenchState = runBlocking {
        recorded.inlineOrbit {
            repeat(REDUCTIONS_PER_OP) {
                reduce { it.next() }
            }
        }
        recorded.stateFlow.value
    }

    @Benchmark
    fun intent_observer_null(): BenchState = runBlocking {
        unobserved.orbit { reduce { it.next() } }.join()
        unobserved.stateFlow.value
    }

    @Benchmark
    fun intent_recording(): BenchState = runBlocking {
        recorded.orbit { reduce { it.next() } }.join()
        recorded.stateFlow.value
    }

    data class BenchState(
        val counter: Int = 0,
        val label: String = "benchmark",
    ) {
        fun next(): BenchState = copy(counter = counter + 1)
    }

    private companion object {
        const val REDUCTIONS_PER_OP = 100
    }
}

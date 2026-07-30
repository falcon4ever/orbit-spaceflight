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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.observer.OrbitEventObserver
import org.orbitmvi.orbit.orbitContainer
import net.multigesture.spaceflight.OrbitSpaceflight

/**
 * Synthetic stand-in for the Android macrobenchmark (which needs a device): simulates a UI
 * frame loop where each frame dispatches an intent performing a few reductions and a side
 * effect, then reads the state. Reports per-frame work-time percentiles with the recorder
 * off vs on.
 *
 * The two variants are measured in interleaved blocks so JIT and GC drift affect both
 * equally. This measures the recorder's contribution to per-frame CPU work, not real frame
 * scheduling — run the on-device macrobenchmark for real P50/P99 frame times before dogfood
 * rollout.
 */
fun main() {
    val recorder = OrbitSpaceflight.install()
    val off = Scenario(observer = null)
    val on = Scenario(observer = recorder.eventObserver)

    repeat(WARMUP_BLOCKS) {
        off.runBlock(discard = true)
        on.runBlock(discard = true)
    }
    repeat(MEASURED_BLOCKS) {
        off.runBlock()
        on.runBlock()
    }

    val frames = MEASURED_BLOCKS * FRAMES_PER_BLOCK
    println(
        "Frame work-time over $frames frames per variant, interleaved in $MEASURED_BLOCKS blocks " +
            "($REDUCTIONS_PER_FRAME reductions + 1 side effect + 1 intent dispatch per frame)"
    )
    println()
    println("percentile | recorder off | recorder on | delta")
    println("-----------|--------------|-------------|------")
    for (p in listOf(50.0, 90.0, 99.0, 99.9)) {
        val offNs = off.durations.percentile(p)
        val onNs = on.durations.percentile(p)
        val delta = onNs - offNs
        val sign = if (delta >= 0) "+" else "-"
        println(
            "P${p.toString().removeSuffix(".0").padEnd(9)}| ${format(offNs).padEnd(13)}| ${format(onNs).padEnd(12)}| " +
                "$sign${format(kotlin.math.abs(delta))}"
        )
    }

    off.close()
    on.close()
    OrbitSpaceflight.uninstall()
}

private class Scenario(observer: OrbitEventObserver?) {
    private val scope = CoroutineScope(SupervisorJob())
    private val container: OrbitContainer<FrameState, FrameState, String> = scope.orbitContainer(
        initialState = FrameState(),
        buildSettings = {
            containerName = "FrameSim"
            eventObserver = observer
            // No side effect collector in the sim; the default 64-slot buffer would suspend intents
            sideEffectBufferSize = Channel.UNLIMITED
        }
    )

    val durations = mutableListOf<Long>()

    fun runBlock(discard: Boolean = false) {
        runBlocking {
            repeat(FRAMES_PER_BLOCK) { frame ->
                val start = System.nanoTime()
                container.orbit {
                    repeat(REDUCTIONS_PER_FRAME) {
                        reduce { it.next() }
                    }
                    postSideEffect("frame-$frame")
                }.join()
                container.stateFlow.value
                val duration = System.nanoTime() - start
                if (!discard) durations += duration
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}

private fun List<Long>.percentile(p: Double): Long {
    val sorted = sorted()
    val index = ((p / 100.0) * (sorted.size - 1)).toInt()
    return sorted[index]
}

private fun format(nanos: Long): String = "${"%,.1f".format(nanos / 1000.0)} us"

private data class FrameState(
    val counter: Int = 0,
    val items: List<Int> = listOf(1, 2, 3),
) {
    fun next(): FrameState = copy(counter = counter + 1)
}

private const val REDUCTIONS_PER_FRAME = 5
private const val FRAMES_PER_BLOCK = 1_000
private const val WARMUP_BLOCKS = 10
private const val MEASURED_BLOCKS = 30

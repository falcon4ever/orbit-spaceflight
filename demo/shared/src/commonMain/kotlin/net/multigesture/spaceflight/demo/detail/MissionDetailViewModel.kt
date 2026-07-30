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

package net.multigesture.spaceflight.demo.detail

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.viewmodel.orbitContainer
import net.multigesture.spaceflight.demo.Mission
import net.multigesture.spaceflight.demo.MissionRepository

/**
 * Internal state: what the reducers work with. The flight recorder records these -
 * internal state is the source of truth.
 */
data class MissionDetailState(
    val mission: Mission? = null,
    val thrust: Int = 0,
    val countdown: Int? = null,
    val launched: Boolean = false,
)

/**
 * External state: a slim, UI-ready render model derived via transformState - flat fields,
 * no domain objects. Never recorded: during time travel it is re-derived from projected
 * internal snapshots.
 */
data class MissionDetailUiState(
    val title: String,
    val destination: String?,
    val description: String?,
    val loading: Boolean,
    val thrustPercent: Int,
    val countdown: Int?,
    val launched: Boolean,
    val countingDown: Boolean,
    val readyToLaunch: Boolean,
    val phase: String,
)

sealed class MissionDetailSideEffect {
    data class Message(val text: String) : MissionDetailSideEffect()
}

class MissionDetailViewModel(
    private val missionId: Int,
) : ViewModel(), OrbitContainerHost<MissionDetailState, MissionDetailUiState, MissionDetailSideEffect> {

    override val container = orbitContainer<MissionDetailState, MissionDetailUiState, MissionDetailSideEffect>(
        initialState = MissionDetailState(),
        transformState = { state ->
            val countingDown = state.countdown != null && !state.launched
            MissionDetailUiState(
                title = state.mission?.name ?: "Loading…",
                destination = state.mission?.destination,
                description = state.mission?.description,
                loading = state.mission == null,
                thrustPercent = state.thrust,
                countdown = state.countdown,
                launched = state.launched,
                countingDown = countingDown,
                readyToLaunch = state.thrust >= 100 && !state.launched && !countingDown,
                phase = when {
                    state.launched -> "In flight"
                    countingDown -> "Countdown"
                    state.thrust >= 100 -> "Go for launch"
                    else -> "Fueling (${state.thrust}%)"
                },
            )
        },
    ) {
        val mission = MissionRepository.mission(missionId)
        reduce { state.copy(mission = mission) }
    }

    fun igniteBooster() = intent {
        reduce { state.copy(thrust = (state.thrust + 20).coerceAtMost(100)) }
    }

    // One long-running intent ticking reductions - the launch sequence reads beautifully in
    // the flight recorder: dispatch, ten attributed reductions, side effect, completion
    fun launch() = intent {
        if (state.thrust < 100) {
            postSideEffect(MissionDetailSideEffect.Message("Need full thrust to launch (${state.thrust}%)"))
            return@intent
        }
        if (state.countdown != null || state.launched) return@intent

        for (t in 10 downTo 0) {
            reduce { state.copy(countdown = t) }
            delay(300)
        }
        reduce { state.copy(launched = true) }
        postSideEffect(MissionDetailSideEffect.Message("${state.mission?.name} has lifted off!"))
    }
}

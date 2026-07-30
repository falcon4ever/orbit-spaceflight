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

package net.multigesture.spaceflight.demo.launchpad

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.blockingIntent
import org.orbitmvi.orbit.viewmodel.orbitContainer
import net.multigesture.spaceflight.demo.Mission
import net.multigesture.spaceflight.demo.MissionRepository

data class LaunchpadState(
    val loading: Boolean = true,
    val query: String = "",
    val missions: List<Mission> = emptyList(),
) {
    val filtered: List<Mission>
        get() = if (query.isBlank()) {
            missions
        } else {
            missions.filter { mission ->
                listOf(mission.name, mission.destination, mission.description)
                    .any { it.contains(query, ignoreCase = true) }
            }
        }
}

class LaunchpadViewModel : ViewModel(), OrbitContainerHost<LaunchpadState, LaunchpadState, Nothing> {

    // The lazy onCreate intent shows up in the flight recording named "onCreate"
    override val container = orbitContainer<LaunchpadState, Nothing>(LaunchpadState()) {
        delay(300)
        reduce { state.copy(loading = false, missions = MissionRepository.missions) }
    }

    // Text input uses blockingIntent so the reduction lands before the TextField recomposes.
    // Still one recorded reduction per keystroke - the chatty container of the demo - but
    // unattributed: Phase 1 records inline intents' work without a separate intent id.
    fun search(query: String) = blockingIntent {
        reduce { state.copy(query = query) }
    }
}

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

package net.multigesture.spaceflight.demo

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import net.multigesture.spaceflight.demo.detail.MissionDetailScreen
import net.multigesture.spaceflight.demo.launchpad.LaunchpadScreen
import net.multigesture.spaceflight.demo.recorder.FlightRecorderScreen

// Nav keys are polymorphic @Serializable so the back stack survives process death
private val navSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(LaunchpadKey::class)
            subclass(MissionDetailKey::class)
            subclass(FlightRecorderKey::class)
        }
    }
}

@Composable
fun App() {
    MaterialTheme {
        val backStack = rememberNavBackStack(navSavedStateConfiguration, LaunchpadKey)
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.padding(padding),
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    // Scopes each screen's ViewModel to its back stack entry, so popping a
                    // screen closes its container - watch for "detached" in the recorder
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<LaunchpadKey> {
                        LaunchpadScreen(
                            onMission = { mission -> backStack.add(MissionDetailKey(mission.id)) },
                            onFlightRecorder = { backStack.add(FlightRecorderKey) },
                        )
                    }
                    entry<MissionDetailKey> { key ->
                        MissionDetailScreen(
                            missionId = key.missionId,
                            snackbarHostState = snackbarHostState,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<FlightRecorderKey> {
                        FlightRecorderScreen(onBack = { backStack.removeLastOrNull() })
                    }
                }
            )
        }
    }
}

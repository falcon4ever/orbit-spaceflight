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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.orbitmvi.orbit.compose.collectAsState
import net.multigesture.spaceflight.demo.Mission
import net.multigesture.spaceflight.demo.PlatformVerticalScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchpadScreen(
    onMission: (Mission) -> Unit,
    onFlightRecorder: () -> Unit,
) {
    val viewModel = viewModel { LaunchpadViewModel() }
    val state by viewModel.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Launchpad") },
                actions = {
                    TextButton(onClick = onFlightRecorder) { Text("Flight Recorder") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search missions (every keystroke is an intent)") },
                singleLine = true,
            )
            if (state.loading) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize().padding(top = 16.dp)) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.filtered, key = { it.id }) { mission ->
                            Card(onClick = { onMission(mission) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(mission.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${mission.destination} — ${mission.description}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                    PlatformVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

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

package net.multigesture.spaceflight.missioncontrol

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.TimeTravel
import net.multigesture.spaceflight.TimeTravelMode
import net.multigesture.spaceflight.TimeTravelState
import net.multigesture.spaceflight.protocol.CAPABILITY_TIME_TRAVEL
import net.multigesture.spaceflight.display.changedFields
import net.multigesture.spaceflight.display.orEmptyQuotes
import net.multigesture.spaceflight.display.topLevelFieldRanges
import net.multigesture.spaceflight.display.truncateForDisplay

enum class EventCategory(val label: String) {
    REDUCTIONS("Reductions"),
    SIDE_EFFECTS("Side effects"),
    INTENTS("Intents"),
    LIFECYCLE("Lifecycle"),
}

private val SpaceflightEvent.category: EventCategory
    get() = when (this) {
        is SpaceflightEvent.Reduction -> EventCategory.REDUCTIONS
        is SpaceflightEvent.SideEffect -> EventCategory.SIDE_EFFECTS
        is SpaceflightEvent.IntentDispatched, is SpaceflightEvent.IntentCompleted -> EventCategory.INTENTS
        else -> EventCategory.LIFECYCLE
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionControlApp(
    source: TimelineSource?,
    onDiscoverApps: (() -> List<DiscoveredApp>)? = null,
    onSelectApp: ((DiscoveredApp?) -> Unit)? = null,
    hasInProcessSource: Boolean = true,
    onOpenSession: (() -> Unit)? = null,
    onSaveSession: (() -> Unit)? = null,
    statusMessage: String? = null,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (source == null) {
                Column(Modifier.fillMaxSize()) {
                    ConnectionBar(
                        label = "not connected",
                        onDiscoverApps = onDiscoverApps,
                        onSelectApp = onSelectApp,
                        hasInProcessSource = hasInProcessSource,
                        onOpenSession = onOpenSession,
                        onSaveSession = null,
                        statusMessage = statusMessage,
                    )
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("No app attached", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Connect to a serving app, or open a .orbitsession file to review a recording",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                return@Surface
            }
            key(source) {
            val capabilities by source.capabilities.collectAsState()
            // Capability-gated, not version-gated: an app built without Retrograde simply
            // does not advertise timeTravel, and its transport bar stays hidden
            val timeTravel = source.timeTravel?.takeIf { CAPABILITY_TIME_TRAVEL in capabilities }
            val recording by source.recording.collectAsState()
            val live by source.liveContainers.collectAsState()
            val sourceWarning by source.warning.collectAsState()
            val travelState by (timeTravel?.state ?: remember { MutableStateFlow(TimeTravelState()) }).collectAsState()

            var selectedContainer by remember { mutableStateOf<Long?>(null) }
            var selectedSeq by remember { mutableStateOf<Long?>(null) }
            var enabledCategories by remember { mutableStateOf(EventCategory.entries.toSet()) }
            var follow by remember { mutableStateOf(true) }
            var followCursor by remember { mutableStateOf(true) }
            val inspecting = travelState.mode == TimeTravelMode.INSPECTING
            // Session files have no app to drive, so stepping moves a review cursor instead
            val reviewMode = timeTravel == null

            // Scrubbing and follow-live fight over attention: freezing swaps follow-live
            // for follow-cursor, so stepping keeps the cursor's event in view
            LaunchedEffect(travelState.mode) {
                if (inspecting) {
                    follow = false
                    followCursor = true
                }
            }
            LaunchedEffect(travelState.cursorSeq, followCursor, inspecting) {
                if (inspecting && followCursor && travelState.cursorSeq != null) {
                    selectedSeq = travelState.cursorSeq
                }
            }

            val names = remember(recording, live) {
                // Attach events fall off the ring under load; ContainerUpdate keeps carrying
                // authoritative names, so live entries win over (and outlive) recorded ones
                recording.events
                    .filterIsInstance<SpaceflightEvent.ContainerAttached>()
                    .associate { it.containerId to (it.name ?: "container#${it.containerId}") }
                    .plus(live.mapNotNull { container -> container.name?.let { container.containerId to it } })
            }
            val liveIds = live.map { it.containerId }.toSet()
            val knownContainers = names.keys + liveIds

            val filtered = recording.events.filter { event ->
                (selectedContainer == null || event.containerId == selectedContainer) &&
                    event.category in enabledCategories
            }
            val selectedEvent = selectedSeq?.let { seq -> recording.events.find { it.seq == seq } }

            // While following, the newest visible event is selected so the detail pane
            // rides the stream; picking a row by hand takes over and unfollows
            val newestSeq = filtered.lastOrNull()?.seq
            LaunchedEffect(newestSeq, follow) {
                if (follow && newestSeq != null) {
                    selectedSeq = newestSeq
                }
            }

            Column(Modifier.fillMaxSize()) {
                ConnectionBar(
                    label = source.label,
                    onDiscoverApps = onDiscoverApps,
                    onSelectApp = onSelectApp,
                    hasInProcessSource = hasInProcessSource,
                    onOpenSession = onOpenSession,
                    onSaveSession = onSaveSession,
                    statusMessage = statusMessage,
                    warning = sourceWarning,
                )
                if (timeTravel != null) {
                    TransportBar(timeTravel, travelState)
                    HorizontalDivider()
                } else {
                    // Review cursor over the filtered timeline: same controls, read-only
                    val index = filtered.indexOfFirst { it.seq == selectedSeq }
                    fun moveTo(target: Int) {
                        filtered.getOrNull(target.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)))?.let {
                            // Stepping takes over from follow-newest, same as clicking a row -
                            // otherwise the next filter change silently jumps the cursor back
                            // to the newest event and the review position is lost
                            follow = false
                            selectedSeq = it.seq
                            followCursor = true
                        }
                    }
                    ReviewBar(
                        position = if (index >= 0) index + 1 else 0,
                        total = filtered.size,
                        onFirst = { moveTo(0) },
                        onPrevious = { moveTo(if (index < 0) filtered.lastIndex else index - 1) },
                        onNext = { moveTo(if (index < 0) 0 else index + 1) },
                        onLast = { moveTo(filtered.lastIndex) },
                    )
                    HorizontalDivider()
                }
                Row(Modifier.fillMaxSize()) {
                ContainerSidebar(
                    containerIds = knownContainers.sorted(),
                    names = names,
                    liveIds = liveIds,
                    selected = selectedContainer,
                    onSelect = { selectedContainer = it },
                )
                VerticalDivider()
                TimelinePane(
                    modifier = Modifier.weight(1f),
                    events = filtered,
                    names = names,
                    droppedEvents = recording.droppedEvents,
                    enabledCategories = enabledCategories,
                    onToggleCategory = { category ->
                        enabledCategories =
                            if (category in enabledCategories) enabledCategories - category else enabledCategories + category
                    },
                    selectedSeq = selectedSeq,
                    cursorSeq = if (reviewMode) selectedSeq else travelState.cursorSeq,
                    onSelect = {
                        follow = false
                        followCursor = false
                        selectedSeq = if (selectedSeq == it) null else it
                    },
                    inspecting = inspecting || reviewMode,
                    follow = follow,
                    onFollowChange = { follow = it },
                    followCursor = followCursor,
                    onFollowCursorChange = { followCursor = it },
                    onClear = {
                        source.clear()
                        selectedSeq = null
                    },
                )
                VerticalDivider()
                DetailPane(
                    event = selectedEvent,
                    names = names,
                    timeTravel = timeTravel,
                    inspecting = travelState.mode == TimeTravelMode.INSPECTING,
                    externalStates = source::externalStatesOf,
                )
                }
            }
            }
        }
    }
}

@Composable
private fun ConnectionBar(
    label: String,
    onDiscoverApps: (() -> List<DiscoveredApp>)?,
    onSelectApp: ((DiscoveredApp?) -> Unit)?,
    hasInProcessSource: Boolean,
    onOpenSession: (() -> Unit)?,
    onSaveSession: (() -> Unit)?,
    statusMessage: String?,
    warning: String? = null,
) {
    if (onDiscoverApps == null || onSelectApp == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Source:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
        if (statusMessage != null) {
            Text(
                statusMessage,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (warning != null) {
            Text(
                "⚠ $warning",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.weight(1f))
        if (onOpenSession != null) {
            TextButton(onClick = onOpenSession) { Text("Open session…") }
        }
        if (onSaveSession != null) {
            TextButton(onClick = onSaveSession) { Text("Save session…") }
        }

        var expanded by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf(emptyList<DiscoveredApp>()) }
        var discovering by remember { mutableStateOf(false) }
        val discoveryScope = rememberCoroutineScope()
        Box {
            TextButton(onClick = {
                // Discovery spawns adb processes and probes sockets - never on the UI thread
                expanded = true
                discovering = true
                apps = emptyList()
                discoveryScope.launch {
                    apps = withContext(Dispatchers.IO) {
                        runCatching { onDiscoverApps() }.getOrDefault(emptyList())
                    }
                    discovering = false
                }
            }) { Text("Connect") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (hasInProcessSource) {
                    DropdownMenuItem(
                        text = { Text("In-process demo") },
                        onClick = {
                            onSelectApp(null)
                            expanded = false
                        },
                    )
                }
                if (discovering) {
                    DropdownMenuItem(text = { Text("Discovering…") }, enabled = false, onClick = {})
                } else if (apps.isEmpty()) {
                    DropdownMenuItem(text = { Text("No serving apps found") }, enabled = false, onClick = {})
                }
                apps.forEach { app ->
                    DropdownMenuItem(
                        text = { Text("${app.label} — ${app.host}:${app.port}") },
                        onClick = {
                            onSelectApp(app)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun TransportBar(timeTravel: TimeTravel, travelState: TimeTravelState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val inspecting = travelState.mode == TimeTravelMode.INSPECTING
        Text(
            if (inspecting) "INSPECTING" else "LIVE",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = if (inspecting) Color(0xFFFFB74D) else Color(0xFF6BCB77),
        )
        if (!inspecting) {
            TextButton(onClick = timeTravel::inspect) { Text("Inspect (freeze)") }
            Text(
                "state is live; freeze to scrub the recording",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            TextButton(onClick = timeTravel::moveToStart) { Text("|<") }
            TextButton(onClick = timeTravel::stepBackward) { Text("<") }
            TextButton(onClick = timeTravel::stepForward) { Text(">") }
            TextButton(onClick = timeTravel::moveToEnd) { Text(">|") }
            Text(
                "reduction ${travelState.cursorPosition}/${travelState.reductionCount}" +
                    (travelState.cursorSeq?.let { " (seq #$it)" } ?: ""),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "in-flight intents keep running (live tail); new intents queue until resume",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            TextButton(onClick = timeTravel::resume) { Text("Resume live") }
        }
    }
}

@Composable
private fun ReviewBar(
    position: Int,
    total: Int,
    onFirst: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "REVIEW",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onFirst, enabled = total > 0) { Text("|<") }
        TextButton(onClick = onPrevious, enabled = total > 0) { Text("<") }
        TextButton(onClick = onNext, enabled = total > 0) { Text(">") }
        TextButton(onClick = onLast, enabled = total > 0) { Text(">|") }
        Text(
            "event $position/$total",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "recorded session — stepping moves the review cursor (no app to drive)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ContainerSidebar(
    containerIds: List<Long>,
    names: Map<Long, String>,
    liveIds: Set<Long>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(Modifier.width(230.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().verticalScroll(scrollState).padding(12.dp)) {
            Text("Containers", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(4.dp))
            SidebarRow(label = "All containers", live = null, selected = selected == null) { onSelect(null) }
            containerIds.forEach { id ->
                SidebarRow(
                    label = names[id] ?: "container#$id",
                    live = id in liveIds,
                    selected = selected == id,
                ) { onSelect(id) }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun SidebarRow(label: String, live: Boolean?, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when (live) {
            true -> Text("live", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6BCB77))
            false -> Text("closed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            null -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelinePane(
    modifier: Modifier,
    events: List<SpaceflightEvent>,
    names: Map<Long, String>,
    droppedEvents: Long,
    enabledCategories: Set<EventCategory>,
    onToggleCategory: (EventCategory) -> Unit,
    selectedSeq: Long?,
    cursorSeq: Long?,
    onSelect: (Long) -> Unit,
    inspecting: Boolean,
    follow: Boolean,
    onFollowChange: (Boolean) -> Unit,
    followCursor: Boolean,
    onFollowCursorChange: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier.fillMaxHeight().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleSmall)
            Text(
                "${events.size} events" + if (droppedEvents > 0) ", $droppedEvents evicted" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear") }
        }
        val listState = rememberLazyListState()

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EventCategory.entries.forEach { category ->
                FilterChip(
                    selected = category in enabledCategories,
                    onClick = { onToggleCategory(category) },
                    label = { Text(category.label) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (inspecting) {
                FilterChip(
                    selected = followCursor,
                    onClick = { onFollowCursorChange(!followCursor) },
                    label = { Text("Follow cursor") },
                )
            } else {
                FilterChip(
                    selected = follow,
                    onClick = { onFollowChange(!follow) },
                    label = { Text("Follow live") },
                )
            }
        }
        Spacer(Modifier.padding(4.dp))

        // Pin to the newest event while following live; scrolling away unfollows,
        // returning to the top re-engages
        val newestSeq = events.lastOrNull()?.seq
        LaunchedEffect(newestSeq, follow) {
            if (!inspecting && follow && newestSeq != null) {
                listState.scrollToItem(0)
            }
        }
        LaunchedEffect(listState, inspecting) {
            if (!inspecting) {
                snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
                    .collect { (scrolling, firstVisible) ->
                        if (scrolling) {
                            onFollowChange(firstVisible == 0)
                        }
                    }
            }
        }

        // While inspecting with follow-cursor on, keep the cursor's row in view as it moves
        // (and as the live tail shifts rows)
        LaunchedEffect(cursorSeq, followCursor, inspecting, events.size) {
            if (inspecting && followCursor && cursorSeq != null) {
                val index = events.asReversed().indexOfFirst { it.seq == cursorSeq }
                if (index >= 0) {
                    listState.animateScrollToItem(index)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(events.asReversed(), key = { it.seq }) { event ->
                    TimelineRow(
                        event = event,
                        label = event.containerId?.let { names[it] ?: "container#$it" } ?: "recorder",
                        selected = event.seq == selectedSeq,
                        atCursor = cursorSeq != null && event.seq == cursorSeq,
                        onClick = { onSelect(event.seq) },
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TimelineRow(event: SpaceflightEvent, label: String, selected: Boolean, atCursor: Boolean, onClick: () -> Unit) {
    val background = when {
        atCursor -> MaterialTheme.colorScheme.tertiaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${event.seq}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            event.badge(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = event.badgeColor(),
        )
        Text(
            "$label ${event.summary()}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailPane(
    event: SpaceflightEvent?,
    names: Map<Long, String>,
    timeTravel: TimeTravel? = null,
    inspecting: Boolean = false,
    externalStates: ((SpaceflightEvent.Reduction) -> Pair<Any, Any>?)? = null,
) {
    val scrollState = rememberScrollState()
    Box(Modifier.width(400.dp).fillMaxHeight()) {
    Column(Modifier.fillMaxHeight().verticalScroll(scrollState).padding(12.dp)) {
        Text("Detail", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.padding(4.dp))

        if (event == null) {
            Text(
                "Select a timeline event",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            return@Column
        }

        if (timeTravel != null && inspecting && event is SpaceflightEvent.Reduction) {
            TextButton(onClick = { timeTravel.seekTo(event.seq) }) { Text("Travel here") }
        }

        SelectionContainer {
        Column {
        DetailField("seq", "#${event.seq}")
        DetailField("time", formatEventDateTime(event.timeMillis))
        DetailField("container", event.containerId?.let { names[it] ?: "container#$it" } ?: "—")

        when (event) {
            is SpaceflightEvent.ContainerAttached -> {
                DetailField("event", "container attached")
                DetailField("initial state", event.initialState.toString())
            }
            is SpaceflightEvent.ContainerDetached -> DetailField("event", "container detached")
            is SpaceflightEvent.IntentDispatched -> {
                DetailField("event", "intent dispatched")
                DetailField("intent", "${event.name ?: "unnamed"} (#${event.intentId})")
            }
            is SpaceflightEvent.IntentCompleted -> {
                DetailField("event", "intent completed")
                DetailField("intent", "#${event.intentId}")
                DetailField(
                    "result",
                    when (val result = event.result) {
                        is IntentResult.Completed -> "completed"
                        is IntentResult.Cancelled -> "cancelled"
                        is IntentResult.Failed -> "failed: ${result.exception}"
                    }
                )
            }
            is SpaceflightEvent.Reduction -> {
                DetailField("event", if (event.noOp) "reduction (no-op)" else "reduction")
                DetailField("intent", event.intentId?.let { "#$it" } ?: "inline / untracked")

                val changes = changedFields(event.oldState, event.newState)
                if (changes != null && changes.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Changed fields", style = MaterialTheme.typography.titleSmall)
                    changes.forEach { change ->
                        Spacer(Modifier.padding(3.dp))
                        Text(
                            change.field,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        DiffValue("-", change.oldValue, RemovedColor)
                        DiffValue("+", change.newValue, AddedColor)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Internal state (recorded)", style = MaterialTheme.typography.titleSmall)
                val changedNames = changes.orEmpty().map { it.field }.toSet()
                DetailField("before", annotateState(event.oldState.toString(), changedNames, RemovedColor))
                DetailField("after", annotateState(event.newState.toString(), changedNames, AddedColor))

                // External state is never recorded - it is derived from the internal snapshot
                // through the container's own transformState (in-process on demand, or by the
                // producing app at send time over the wire)
                val external = externalStates?.invoke(event)
                if (external != null) {
                    val (externalBefore, externalAfter) = external
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("External state (derived)", style = MaterialTheme.typography.titleSmall)
                    val externalChanged = changedFields(externalBefore, externalAfter)
                        .orEmpty().map { it.field }.toSet()
                    DetailField(
                        "before",
                        annotateState(externalBefore.toString(), externalChanged, RemovedColor),
                    )
                    DetailField(
                        "after",
                        annotateState(externalAfter.toString(), externalChanged, AddedColor),
                    )
                }
            }
            is SpaceflightEvent.SideEffect -> {
                DetailField("event", "side effect")
                DetailField("intent", event.intentId?.let { "#$it" } ?: "inline / untracked")
                DetailField("value", event.value.toString())
            }
            is SpaceflightEvent.Diagnostic -> {
                DetailField("event", "diagnostic")
                DetailField("message", event.message)
            }
        }
        }
        }
    }
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    DetailField(label, AnnotatedString(value))
}

@Composable
private fun DetailField(label: String, value: AnnotatedString) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}

private val RemovedColor = Color(0xFFE57373)
private val AddedColor = Color(0xFF81C784)

/**
 * Renders a full data-class-style state string, colouring the values of [changedFields] so
 * the before/after sections show where the change sits in context.
 */
private fun annotateState(rendered: String, changedFields: Set<String>, color: Color): AnnotatedString {
    val open = rendered.indexOf('(')
    if (changedFields.isEmpty() || open <= 0 || !rendered.endsWith(")")) return AnnotatedString(rendered)

    val bodyStart = open + 1
    val body = rendered.substring(bodyStart, rendered.length - 1)

    return buildAnnotatedString {
        append(rendered)
        for (range in topLevelFieldRanges(body)) {
            val part = body.substring(range.first, range.last + 1)
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() in changedFields) {
                addStyle(
                    SpanStyle(color = color, fontWeight = FontWeight.Bold),
                    bodyStart + range.first + eq + 1,
                    bodyStart + range.last + 1,
                )
            }
        }
    }
}

@Composable
private fun DiffValue(sign: String, value: String, color: Color) {
    Text(
        "$sign ${value.ifEmpty { "''" }}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

private fun SpaceflightEvent.badge(): String = when (this) {
    is SpaceflightEvent.ContainerAttached -> "ATT"
    is SpaceflightEvent.ContainerDetached -> "DET"
    is SpaceflightEvent.IntentDispatched -> " > "
    is SpaceflightEvent.IntentCompleted -> " < "
    is SpaceflightEvent.Reduction -> " ~ "
    is SpaceflightEvent.SideEffect -> " ! "
    is SpaceflightEvent.Diagnostic -> " ⚠ "
}

@Composable
private fun SpaceflightEvent.badgeColor(): Color = when (this) {
    is SpaceflightEvent.Reduction -> MaterialTheme.colorScheme.primary
    is SpaceflightEvent.SideEffect -> Color(0xFFFFB74D)
    is SpaceflightEvent.Diagnostic -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun SpaceflightEvent.summary(): String = when (this) {
    is SpaceflightEvent.ContainerAttached -> "attached"
    is SpaceflightEvent.ContainerDetached -> "detached"
    is SpaceflightEvent.IntentDispatched -> "${name ?: "intent"}#$intentId"
    is SpaceflightEvent.IntentCompleted -> "intent#$intentId ${
        when (result) {
            is IntentResult.Completed -> "completed"
            is IntentResult.Cancelled -> "cancelled"
            is IntentResult.Failed -> "failed"
        }
    }"
    is SpaceflightEvent.Reduction -> changedFields(oldState, newState)
        ?.joinToString(separator = ", ") { "${it.field}: ${it.oldValue.short()} -> ${it.newValue.short()}" }
        ?.ifEmpty { "no fields changed" }
        ?: "${oldState.toString().short()} -> ${newState.toString().short()}"
    is SpaceflightEvent.SideEffect -> value.toString().short()
    is SpaceflightEvent.Diagnostic -> message
}

private fun String.short(max: Int = 40): String = truncateForDisplay(max).orEmptyQuotes()

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

package net.multigesture.spaceflight.demo.recorder

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.FlightRecording
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.SpaceflightEvent
import net.multigesture.spaceflight.display.changedFields
import net.multigesture.spaceflight.display.orEmptyQuotes
import net.multigesture.spaceflight.display.topLevelFieldRanges
import net.multigesture.spaceflight.display.truncateForDisplay
import net.multigesture.spaceflight.demo.PlatformVerticalScrollbar
import net.multigesture.spaceflight.demo.formatEventTime
import net.multigesture.spaceflight.demo.shareCurrentSession

/**
 * Renders the app's own black box: a live view of [OrbitSpaceflight.recorder]'s snapshot.
 *
 * Deliberately plain Compose state, not an Orbit container — the recorder view must not
 * record itself (the same rule the Retrograde overlay will follow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightRecorderScreen(onBack: () -> Unit) {
    val recorder = OrbitSpaceflight.recorder

    var clearCount by remember { mutableStateOf(0) }
    var shareRequested by remember { mutableStateOf(false) }
    var shareResult by remember { mutableStateOf<String?>(null) }

    // Export renders and redacts the whole ring - off the UI thread, so a spinner-worthy
    // pause here is expected and fine
    LaunchedEffect(shareRequested) {
        if (shareRequested) {
            shareResult = "Exporting…"
            shareResult = runCatching { shareCurrentSession() }.getOrElse { "Export failed: ${it.message}" }
            shareRequested = false
        }
    }
    val recording by produceState(FlightRecording(emptyList(), 0), clearCount) {
        // Event-driven: re-snapshot when the recorder says something changed, not on a timer
        recorder?.revision?.collect { value = recorder.snapshot() }
            ?: run { value = FlightRecording(emptyList(), 0) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flight Recorder") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { shareRequested = true }) { Text("Share") }
                    TextButton(onClick = {
                        recorder?.clear()
                        clearCount++
                    }) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "${recording.events.size} events retained, ${recording.droppedEvents} evicted",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            shareResult?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            val names = recording.events
                .filterIsInstance<SpaceflightEvent.ContainerAttached>()
                .associate { it.containerId to (it.name ?: "container#${it.containerId}") }

            var expandedSeqs by remember { mutableStateOf(emptySet<Long>()) }

            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                // Newest first via a reversed list - reverseLayout scrolls poorly with a mouse wheel
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(recording.events.asReversed(), key = { it.seq }) { event ->
                        val expanded = event.seq in expandedSeqs
                        // Tap a reduction to expand truncated values to full width
                        Card(
                            onClick = {
                                expandedSeqs = if (expanded) expandedSeqs - event.seq else expandedSeqs + event.seq
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                event.annotated(names, MaterialTheme.colorScheme.primary, expanded),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                PlatformVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

private fun SpaceflightEvent.annotated(names: Map<Long, String>, highlight: Color, expanded: Boolean): AnnotatedString {
    val label = containerId?.let { names[it] ?: "container#$it" } ?: "recorder"
    return buildAnnotatedString {
        when (val event = this@annotated) {
            is SpaceflightEvent.ContainerAttached -> append("#$seq $label attached (initial=${event.initialState})")
            is SpaceflightEvent.ContainerDetached -> append("#$seq $label detached")
            is SpaceflightEvent.IntentDispatched -> append("#$seq $label > ${event.name ?: "intent"}#${event.intentId}")
            is SpaceflightEvent.IntentCompleted ->
                append("#$seq $label < intent#${event.intentId} ${event.result.describe()}")
            is SpaceflightEvent.Reduction -> appendReduction(event, label, highlight, expanded)
            is SpaceflightEvent.SideEffect -> append("#$seq $label ! ${event.value} [intent#${event.intentId ?: "?"}]")
            is SpaceflightEvent.Diagnostic -> append("#$seq ⚠ ${event.message}")
        }
        if (expanded) {
            append("\nat ${formatEventTime(timeMillis)}")
        }
    }
}

// Diff colors tuned for the demo's light theme (Mission Control uses lighter dark-theme shades)
private val RemovedColor = Color(0xFFC62828)
private val AddedColor = Color(0xFF2E7D32)
private val DimColor = Color(0xFF757575)

private fun AnnotatedString.Builder.appendReduction(
    event: SpaceflightEvent.Reduction,
    label: String,
    highlight: Color,
    expanded: Boolean,
) {
    val changes = changedFields(event.oldState, event.newState)
    val fieldStyle = SpanStyle(color = highlight, fontWeight = FontWeight.Bold)
    val removedStyle = SpanStyle(color = RemovedColor, fontWeight = FontWeight.Bold)
    val addedStyle = SpanStyle(color = AddedColor, fontWeight = FontWeight.Bold)
    val dimStyle = SpanStyle(color = DimColor)

    if (expanded) {
        append("#${event.seq} $label ~ [intent#${event.intentId ?: "?"}]")
        if (event.noOp) append(" (no-op)")
        if (changes != null) {
            if (changes.isNotEmpty()) {
                appendSectionHeader("Changed fields")
                changes.forEach { change ->
                    append("\n")
                    withStyle(fieldStyle) { append(change.field) }
                    append("\n")
                    withStyle(removedStyle) { append("- ${change.oldValue.orEmptyQuotes()}") }
                    append("\n")
                    withStyle(addedStyle) { append("+ ${change.newValue.orEmptyQuotes()}") }
                }
            }
            val changedNames = changes.map { it.field }.toSet()
            appendSectionHeader("Internal state (recorded)")
            append("\n")
            withStyle(dimStyle) { append("before") }
            append("\n")
            appendAnnotatedState(event.oldState.toString(), changedNames, RemovedColor)
            append("\n")
            withStyle(dimStyle) { append("after") }
            append("\n")
            appendAnnotatedState(event.newState.toString(), changedNames, AddedColor)
        } else {
            appendSectionHeader("Internal state (recorded)")
            append("\n")
            withStyle(removedStyle) { append("- ${event.oldState}") }
            append("\n")
            withStyle(addedStyle) { append("+ ${event.newState}") }
        }
        return
    }

    append("#${event.seq} $label ~ ")
    when {
        event.oldState == event.newState -> withStyle(fieldStyle) { append("unchanged") }
        changes != null -> changes.forEachIndexed { index, change ->
            if (index > 0) append(", ")
            withStyle(fieldStyle) { append("${change.field}: ") }
            withStyle(removedStyle) { append(change.oldValue.truncateForDisplay().orEmptyQuotes()) }
            append(" -> ")
            withStyle(addedStyle) { append(change.newValue.truncateForDisplay().orEmptyQuotes()) }
        }
        else -> {
            withStyle(removedStyle) { append(event.oldState.toString().truncateForDisplay()) }
            append(" -> ")
            withStyle(addedStyle) { append(event.newState.toString().truncateForDisplay()) }
        }
    }
    append(" [intent#${event.intentId ?: "?"}]")
    if (event.noOp) append(" (no-op)")
}

/** Section headers matching Mission Control's detail pane vocabulary. */
private fun AnnotatedString.Builder.appendSectionHeader(title: String) {
    append("\n\n")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
}

/**
 * Appends a full data-class-style state rendering, colouring the values of [changedFields]
 * in place — the same diff-in-context treatment as Mission Control's detail pane.
 */
private fun AnnotatedString.Builder.appendAnnotatedState(
    rendered: String,
    changedFields: Set<String>,
    color: Color,
) {
    val open = rendered.indexOf('(')
    if (changedFields.isEmpty() || open <= 0 || !rendered.endsWith(")")) {
        append(rendered)
        return
    }

    val startOffset = length
    append(rendered)

    val bodyStart = open + 1
    val body = rendered.substring(bodyStart, rendered.length - 1)

    for (range in topLevelFieldRanges(body)) {
        val part = body.substring(range.first, range.last + 1)
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        if (part.substring(0, eq).trim() in changedFields) {
            addStyle(
                SpanStyle(color = color, fontWeight = FontWeight.Bold),
                startOffset + bodyStart + range.first + eq + 1,
                startOffset + bodyStart + range.last + 1,
            )
        }
    }
}

private fun IntentResult.describe(): String = when (this) {
    is IntentResult.Completed -> "completed"
    is IntentResult.Cancelled -> "cancelled"
    is IntentResult.Failed -> "failed: $exception"
}


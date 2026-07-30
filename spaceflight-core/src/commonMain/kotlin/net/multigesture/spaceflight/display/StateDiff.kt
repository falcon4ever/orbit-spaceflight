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

package net.multigesture.spaceflight.display

/** One field whose value differs between two states. */
public data class FieldChange(
    public val field: String,
    public val oldValue: String,
    public val newValue: String,
)

/**
 * Display-time field diff of two states rendered as `Type(a=…, b=…)` — Kotlin data class
 * `toString` shape, which is what both live recordings and session files carry.
 *
 * Returns null when either side does not parse, or when the two have different field sets
 * (a state type change); callers then fall back to showing whole values. An empty list means
 * the states parsed and nothing changed.
 *
 * This is a *preview* diff, not a structural one: it splits at top level only, so a change
 * nested inside a field shows as that whole field changing. Mission Control renders value
 * trees for the deep view; this keeps one-line rows and phone-sized panes readable, and is
 * shared so the in-app recorder, the overlay and the desktop client agree.
 */
public fun changedFields(oldState: Any?, newState: Any?): List<FieldChange>? {
    val oldFields = parseRenderedFields(oldState.toString()) ?: return null
    val newFields = parseRenderedFields(newState.toString()) ?: return null
    if (oldFields.keys != newFields.keys) return null

    return newFields.mapNotNull { (field, value) ->
        val previous = oldFields.getValue(field)
        if (previous != value) FieldChange(field, previous, value) else null
    }
}

/**
 * Splits a `Type(a=1, b=Nested(c=2))` rendering into its top-level `field to value` pairs,
 * or null when it is not of that shape. Depth-aware: separators inside nested parentheses,
 * brackets and braces are ignored.
 */
public fun parseRenderedFields(rendered: String): Map<String, String>? {
    val open = rendered.indexOf('(')
    if (open <= 0 || !rendered.endsWith(")")) return null
    val body = rendered.substring(open + 1, rendered.length - 1)

    val fields = mutableMapOf<String, String>()
    for (part in splitTopLevel(body)) {
        val separator = part.indexOf('=')
        if (separator <= 0) return null
        fields[part.substring(0, separator).trim()] = part.substring(separator + 1).trim()
    }
    return fields
}

/** Top-level comma-separated spans of [body] as `(startInclusive, endExclusive)` offsets. */
public fun topLevelFieldRanges(body: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var depth = 0
    var start = 0
    body.forEachIndexed { index, char ->
        when (char) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) {
                ranges += start until index
                start = index + 1
            }
        }
    }
    ranges += start until body.length
    return ranges
}

private fun splitTopLevel(body: String): List<String> =
    topLevelFieldRanges(body).map { body.substring(it.first, it.last + 1) }

/** Shortens a value for one-line display, marking the cut with an ellipsis. */
public fun String.truncateForDisplay(max: Int = 48): String =
    if (length <= max) this else take(max - 1) + "…"

/** Renders empty strings visibly, so a cleared field does not look like a broken row. */
public fun String.orEmptyQuotes(): String = ifEmpty { "''" }

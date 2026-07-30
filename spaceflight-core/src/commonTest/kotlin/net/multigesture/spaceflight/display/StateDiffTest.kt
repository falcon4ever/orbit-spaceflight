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

import net.multigesture.spaceflight.session.ValueNode
import net.multigesture.spaceflight.session.flatten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class StateDiffTest {

    private data class Mission(val id: Int, val name: String)
    private data class State(val thrust: Int, val mission: Mission?, val tags: List<String> = emptyList())

    @Test
    fun reports_only_the_fields_that_changed() {
        val changes = changedFields(
            State(thrust = 40, mission = Mission(1, "Apollo 11")),
            State(thrust = 60, mission = Mission(1, "Apollo 11")),
        )

        assertEquals(listOf(FieldChange("thrust", "40", "60")), changes)
    }

    @Test
    fun nested_values_survive_top_level_splitting() {
        // Commas inside the nested Mission and the list must not split fields
        val changes = changedFields(
            State(1, Mission(1, "a, b"), listOf("x", "y")),
            State(1, Mission(2, "a, b"), listOf("x", "y")),
        )

        assertEquals(1, changes?.size)
        assertEquals("mission", changes?.single()?.field)
        assertEquals("Mission(id=1, name=a, b)", changes?.single()?.oldValue)
    }

    @Test
    fun identical_states_produce_an_empty_diff() {
        assertEquals(emptyList(), changedFields(State(1, null), State(1, null)))
    }

    @Test
    fun different_shapes_return_null_so_callers_fall_back() {
        assertNull(changedFields(State(1, null), "just a string"))
        assertNull(changedFields("Loading", "Loaded"))
        // Different field sets = a state type change, not a field diff
        assertNull(changedFields(State(1, null), Mission(1, "x")))
    }

    @Test
    fun parses_and_ranges_agree_on_top_level_fields() {
        val rendered = "State(thrust=1, mission=Mission(id=2, name=x), tags=[a, b])"
        val fields = parseRenderedFields(rendered)

        assertEquals(setOf("thrust", "mission", "tags"), fields?.keys)
        assertEquals("Mission(id=2, name=x)", fields?.get("mission"))
        assertEquals("[a, b]", fields?.get("tags"))

        val body = rendered.substringAfter('(').dropLast(1)
        assertEquals(3, topLevelFieldRanges(body).size)
    }

    @Test
    fun truncation_and_empty_values_are_marked() {
        assertEquals("abcde…", "abcdefghij".truncateForDisplay(6))
        assertEquals("short", "short".truncateForDisplay(10))
        assertEquals("''", "".orEmptyQuotes())
    }

    @Test
    fun value_trees_flatten_into_diffable_renderings() {
        val tree = ValueNode(
            name = "newState",
            type = "State",
            children = listOf(
                ValueNode(name = "thrust", type = "Int", value = "60"),
                ValueNode(
                    name = "mission",
                    type = "Mission",
                    children = listOf(
                        ValueNode(name = "id", type = "Int", value = "1"),
                        ValueNode(name = "name", type = "String", value = "Apollo 11"),
                    ),
                ),
            ),
        )

        val flattened = tree.flatten()
        assertEquals("State(thrust=60, mission=Mission(id=1, name=Apollo 11))", flattened)

        // A flattened tree diffs against another flattened tree - session files behave like
        // live recordings downstream
        val previous = tree.copy(
            children = listOf(tree.children[0].copy(value = "40"), tree.children[1])
        ).flatten()
        assertEquals(listOf(FieldChange("thrust", "40", "60")), changedFields(previous, flattened))
    }

    @Test
    fun truncated_collections_keep_their_ellipsis_when_flattened() {
        val tree = ValueNode(
            name = "items",
            type = "List(500)",
            truncated = true,
            children = listOf(ValueNode(name = "[0]", value = "first")),
        )

        assertEquals("List([0]=first, …)", tree.flatten())
    }
}

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

package net.multigesture.spaceflight.session

import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Rendering caps. Deep object graphs, huge collections and megabyte strings are the failure
 * modes of naive reflective dumping; every cap marks its cut with [ValueNode.truncated].
 */
public data class RenderCaps(
    val maxDepth: Int = 10,
    val maxCollectionItems: Int = 200,
    val maxStringLength: Int = 256,
)

/**
 * Renders any value into a [ValueNode] tree: scalars stay scalar, collections and maps
 * become indexed children, and other objects are field-walked with java reflection.
 * Cycle-guarded along the current path; any reflective failure falls back to `toString`.
 *
 * Only ever invoked at export time or on an explicit value request — never on the
 * recording hot path.
 */
public fun renderValue(value: Any?, name: String? = null, caps: RenderCaps = RenderCaps()): ValueNode =
    render(name, value, caps, depth = 0, visiting = IdentityHashMap())

private fun render(
    name: String?,
    value: Any?,
    caps: RenderCaps,
    depth: Int,
    visiting: IdentityHashMap<Any, Unit>,
): ValueNode {
    if (value == null) return ValueNode(name = name, value = "null")

    val type = value.javaClass.simpleName.ifEmpty { value.javaClass.name.substringAfterLast('.') }

    if (isScalar(value)) {
        return scalar(name, type, value, caps)
    }
    if (depth >= caps.maxDepth) {
        return scalar(name, type, value, caps).copy(truncated = true)
    }
    if (visiting.containsKey(value)) {
        return ValueNode(name = name, type = type, value = "«cycle»")
    }

    visiting[value] = Unit
    try {
        return runCatching { renderContainer(name, type, value, caps, depth, visiting) }
            .getOrElse { scalar(name, type, value, caps) }
    } finally {
        visiting.remove(value)
    }
}

private fun renderContainer(
    name: String?,
    type: String,
    value: Any,
    caps: RenderCaps,
    depth: Int,
    visiting: IdentityHashMap<Any, Unit>,
): ValueNode {
    fun items(sequence: Sequence<Pair<String?, Any?>>, size: Int): ValueNode {
        val children = sequence.take(caps.maxCollectionItems)
            .map { (childName, child) -> render(childName, child, caps, depth + 1, visiting) }
            .toList()
        return ValueNode(
            name = name,
            type = "$type($size)",
            children = children,
            truncated = size > caps.maxCollectionItems,
        )
    }

    return when (value) {
        is Collection<*> -> items(value.asSequence().mapIndexed { i, v -> "[$i]" to v }, value.size)
        is Map<*, *> -> items(
            value.entries.asSequence().map { (k, v) -> k.toString() to v },
            value.size,
        )
        is Array<*> -> items(value.asSequence().mapIndexed { i, v -> "[$i]" to v }, value.size)
        else -> {
            // Platform types render best with their own toString; user types get field-walked
            val className = value.javaClass.name
            if (className.startsWith("java.") || className.startsWith("kotlin.")) {
                return scalar(name, type, value, caps)
            }
            val fields = value.javaClass.declaredFields
                .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            if (fields.isEmpty()) return scalar(name, type, value, caps)

            val children = fields.map { field ->
                field.isAccessible = true
                render(field.name, field.get(value), caps, depth + 1, visiting)
            }
            ValueNode(name = name, type = type, children = children)
        }
    }
}

private fun isScalar(value: Any): Boolean = when (value) {
    is String, is Boolean, is Number, is Char, is Enum<*> -> true
    else -> false
}

private fun scalar(name: String?, type: String, value: Any, caps: RenderCaps): ValueNode {
    val rendered = runCatching { value.toString() }
        .getOrElse { "«toString failed: ${it.javaClass.simpleName}»" }
    val capped = rendered.length > caps.maxStringLength
    return ValueNode(
        name = name,
        type = type,
        value = if (capped) rendered.take(caps.maxStringLength) + "…" else rendered,
        truncated = capped,
    )
}

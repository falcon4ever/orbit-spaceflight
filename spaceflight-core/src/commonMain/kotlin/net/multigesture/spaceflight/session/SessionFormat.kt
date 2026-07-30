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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `.orbitsession` file format: a gzipped JSON [OrbitSession] envelope.
 *
 * One versioned schema, two producers — on-device dumps and Mission Control's "save
 * session" write the same format. Session files are a second, asynchronous wire protocol:
 * producers write only the current [SESSION_FORMAT_VERSION]; readers must open every version
 * they ever supported, because field builds keep producing old versions long after the
 * tooling updates.
 */
public const val SESSION_FORMAT_VERSION: Int = 1

/**
 * Session files always encode defaults: `formatVersion` is the whole compatibility contract
 * and must never be omitted just because it happens to equal the current default, and a
 * missing `droppedEvents` would silently read as "no gaps".
 */
public val sessionJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
public data class OrbitSession(
    /** Required on read: a file without it is not a session file this reader trusts. */
    val formatVersion: Int,
    val app: SessionApp,
    val containers: List<SessionContainer> = emptyList(),
    val events: List<SessionEvent> = emptyList(),
    val droppedEvents: Long = 0,
)

@Serializable
public data class SessionApp(
    val name: String,
    val platform: String,
    val exportedAtMillis: Long,
)

@Serializable
public data class SessionContainer(
    val containerId: Long,
    val name: String? = null,
)

/**
 * A recorded event with states eagerly rendered (and redacted) as [ValueNode] trees —
 * unlike the live wire protocol, a session file's producing app is gone by read time, so
 * everything must be self-contained.
 */
/**
 * The session file's own event vocabulary. Deliberately NOT the wire protocol's
 * [net.multigesture.spaceflight.protocol.SessionEventType]: session files are the long-lived format
 * (old files must open forever), while the wire protocol only needs to span adjacent
 * versions - they evolve under different pressure and must not be coupled. The serialized
 * names below are frozen by the v1 golden test.
 */
@Serializable
public enum class SessionEventType {
    ATTACHED,
    DETACHED,
    INTENT_DISPATCHED,
    INTENT_COMPLETED,
    REDUCTION,
    SIDE_EFFECT,
    DIAGNOSTIC,
}

@Serializable
public data class SessionEvent(
    val seq: Long,
    val timeMillis: Long,
    val eventType: SessionEventType,
    val containerId: Long? = null,
    val intentId: Long? = null,
    val name: String? = null,
    val result: String? = null,
    val noOp: Boolean = false,
    val message: String? = null,
    val oldState: ValueNode? = null,
    val newState: ValueNode? = null,
    val externalOldState: ValueNode? = null,
    val externalNewState: ValueNode? = null,
    val value: ValueNode? = null,
)

/**
 * A rendered value: scalar ([value] set) or container ([children] set). Rendering applies
 * caps — depth, collection size, string length — and marks where they cut with [truncated].
 */
@Serializable
public data class ValueNode(
    val name: String? = null,
    val type: String? = null,
    val value: String? = null,
    val children: List<ValueNode> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * Renders a value tree back to the `Type(field=value, …)` shape that display code (and
 * [net.multigesture.spaceflight.display.changedFields]) expects, so session files and live
 * recordings look identical downstream. Truncated nodes keep an ellipsis so caps stay visible.
 */
public fun ValueNode.flatten(): String = when {
    children.isEmpty() -> value.orEmpty()
    else -> {
        val body = children.joinToString(", ") { child ->
            val childName = child.name?.let { "$it=" }.orEmpty()
            "$childName${child.flatten()}"
        }
        val typeName = type?.substringBefore('(').orEmpty()
        val ellipsis = if (truncated) ", …" else ""
        "$typeName($body$ellipsis)"
    }
}

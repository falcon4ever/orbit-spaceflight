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

/**
 * Caller-supplied redaction rules, applied *in addition to* the built-in field-name policy.
 * There is deliberately no way to opt out of the defaults: session files leave devices via
 * Slack, email and bug trackers, and states contain tokens and PII.
 */
public fun interface SessionRedactor {
    /**
     * @param path dot-joined location of the value inside its state, e.g. `user.address.city`
     * @param name the field name (or index) of this node, when known
     * @param value the rendered scalar value, when the node is a scalar
     * @return true to replace this node's value (and prune its children) with `«redacted»`
     */
    public fun shouldRedact(path: String, name: String?, value: String?): Boolean
}

public const val REDACTED: String = "«redacted»"

/**
 * Conservative-by-default field-name deny list, matched case-insensitively as substrings.
 * False positives (e.g. `author` matching `auth`) are accepted: over-redacting a debug dump
 * is annoying, under-redacting it is an incident.
 */
public val DefaultSensitiveFieldTokens: Set<String> = setOf(
    "password", "passwd", "secret", "token", "auth", "bearer", "cookie",
    "credential", "apikey", "api_key", "accesskey", "privatekey", "private_key",
    "email", "phone", "address", "ssn",
)

/**
 * Returns a copy of this tree with sensitive nodes replaced by [REDACTED] (value substituted,
 * children pruned). Applied unconditionally on every export path.
 */
public fun ValueNode.redacted(custom: SessionRedactor? = null): ValueNode = redactNode(this, "", custom)

private fun redactNode(node: ValueNode, parentPath: String, custom: SessionRedactor?): ValueNode {
    val path = when {
        node.name == null -> parentPath
        parentPath.isEmpty() -> node.name
        else -> "$parentPath.${node.name}"
    }

    val nameMatches = node.name?.lowercase()?.let { lower ->
        DefaultSensitiveFieldTokens.any { token -> token in lower }
    } ?: false

    if (nameMatches || custom?.shouldRedact(path, node.name, node.value) == true) {
        return node.copy(value = REDACTED, children = emptyList())
    }

    if (node.children.isEmpty()) return node
    return node.copy(children = node.children.map { child -> redactNode(child, path, custom) })
}

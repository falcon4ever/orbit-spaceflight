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

package net.multigesture.spaceflight.protocol

import org.orbitmvi.orbit.observer.IntentResult
import net.multigesture.spaceflight.SpaceflightEvent

/**
 * Renders a user object for the wire. `toString` is user code and this runs on the serving
 * app's side — a throwing render must cost the event's detail, never the host app.
 */
private fun Any?.render(): String = try {
    toString()
} catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
    "«toString failed: ${e::class.simpleName}»"
}

/**
 * Flattens a recorded event for the wire, rendering states with `toString`.
 * [externalStates] supplies derived (external old, external new) renderings for reductions,
 * or null when the container's transform is identity.
 */
public fun SpaceflightEvent.toWire(
    externalStates: (SpaceflightEvent.Reduction) -> Pair<String, String>? = { null },
): WireEvent = when (this) {
    is SpaceflightEvent.ContainerAttached -> WireEvent(
        seq = seq, timeMillis = timeMillis, eventType = WireEventType.ATTACHED,
        containerId = containerId, name = name, value = initialState.render(),
    )
    is SpaceflightEvent.ContainerDetached -> WireEvent(
        seq = seq, timeMillis = timeMillis, eventType = WireEventType.DETACHED,
        containerId = containerId,
    )
    is SpaceflightEvent.IntentDispatched -> WireEvent(
        seq = seq, timeMillis = timeMillis, eventType = WireEventType.INTENT_DISPATCHED,
        containerId = containerId, intentId = intentId, name = name,
    )
    is SpaceflightEvent.IntentCompleted -> WireEvent(
        seq = seq, timeMillis = timeMillis, eventType = WireEventType.INTENT_COMPLETED,
        containerId = containerId, intentId = intentId,
        result = when (val intentResult = result) {
            is IntentResult.Completed -> "completed"
            is IntentResult.Cancelled -> "cancelled"
            is IntentResult.Failed -> "failed: ${intentResult.exception.render()}"
        },
    )
    is SpaceflightEvent.Reduction -> {
        val external = externalStates(this)
        WireEvent(
            seq = seq, timeMillis = timeMillis, eventType = WireEventType.REDUCTION,
            containerId = containerId, intentId = intentId,
            oldState = oldState.render(), newState = newState.render(),
            externalOldState = external?.first, externalNewState = external?.second,
            noOp = noOp,
        )
    }
    is SpaceflightEvent.SideEffect -> WireEvent(
        seq = seq, timeMillis = timeMillis, eventType = WireEventType.SIDE_EFFECT,
        containerId = containerId, intentId = intentId, value = value.render(),
    )
    is SpaceflightEvent.Diagnostic -> WireEvent(
        seq = seq, timeMillis = timeMillis, eventType = WireEventType.DIAGNOSTIC,
        containerId = containerId, value = message,
    )
}

/**
 * Reconstructs a [SpaceflightEvent] on the client side. States are the rendered strings —
 * everything downstream (diffing, display) already operates on `toString` renderings, so the
 * UI is agnostic to whether it is looking at live objects or wire strings.
 */
public fun WireEvent.toEvent(): SpaceflightEvent = when (eventType) {
    WireEventType.ATTACHED -> SpaceflightEvent.ContainerAttached(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        name = name, initialState = value.orEmpty(),
    )
    WireEventType.DETACHED -> SpaceflightEvent.ContainerDetached(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
    )
    WireEventType.INTENT_DISPATCHED -> SpaceflightEvent.IntentDispatched(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        intentId = intentId ?: -1, name = name,
    )
    WireEventType.INTENT_COMPLETED -> SpaceflightEvent.IntentCompleted(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        intentId = intentId ?: -1,
        result = when {
            result == "completed" || result == null -> IntentResult.Completed
            result == "cancelled" -> IntentResult.Cancelled
            else -> IntentResult.Failed(RemoteIntentFailure(result.removePrefix("failed: ")))
        },
    )
    WireEventType.REDUCTION -> SpaceflightEvent.Reduction(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        intentId = intentId, oldState = oldState.orEmpty(), newState = newState.orEmpty(),
        noOp = noOp,
    )
    WireEventType.SIDE_EFFECT -> SpaceflightEvent.SideEffect(
        seq = seq, timeMillis = timeMillis, containerId = containerId ?: -1,
        intentId = intentId, value = value.orEmpty(),
    )
    WireEventType.DIAGNOSTIC -> SpaceflightEvent.Diagnostic(
        seq = seq, timeMillis = timeMillis, containerId = containerId,
        message = value.orEmpty(),
    )
}

/** Stand-in for a remote app's exception; only its rendered message crossed the wire. */
public class RemoteIntentFailure(message: String) : RuntimeException(message) {
    override fun toString(): String = message.orEmpty()
}

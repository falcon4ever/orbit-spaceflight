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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public const val PROTOCOL_VERSION: Int = 1

/**
 * Advertised feature names. Peers should gate behaviour on these rather than on
 * [PROTOCOL_VERSION] comparisons: a capability set degrades gracefully in both directions,
 * where a version number forces every peer to know the whole history.
 */
public const val CAPABILITY_RECORDING: String = "recording"
public const val CAPABILITY_TIME_TRAVEL: String = "timeTravel"

/**
 * Describes how a peer's [Hello] relates to this build's [PROTOCOL_VERSION].
 *
 * The protocol degrades rather than refuses: JSON frames ignore unknown fields, so a version
 * gap usually costs features, not the connection. Callers surface [message] and keep going.
 */
public sealed interface ProtocolCompatibility {
    public data object Compatible : ProtocolCompatibility

    /** The peer speaks a newer protocol; some of its data may not be understood here. */
    public data class PeerNewer(public val peerVersion: Int, public val message: String) : ProtocolCompatibility

    /** The peer speaks an older protocol; newer features are unavailable. */
    public data class PeerOlder(public val peerVersion: Int, public val message: String) : ProtocolCompatibility
}

/** Classifies a peer's handshake against [PROTOCOL_VERSION]. */
public fun Hello.compatibility(peerRole: String): ProtocolCompatibility = when {
    protocolVersion == PROTOCOL_VERSION -> ProtocolCompatibility.Compatible
    protocolVersion > PROTOCOL_VERSION -> ProtocolCompatibility.PeerNewer(
        protocolVersion,
        "$peerRole speaks protocol v$protocolVersion, this build speaks v$PROTOCOL_VERSION - " +
            "update it to see everything",
    )
    else -> ProtocolCompatibility.PeerOlder(
        protocolVersion,
        "$peerRole speaks protocol v$protocolVersion, this build speaks v$PROTOCOL_VERSION - " +
            "some features are unavailable",
    )
}

/**
 * NDJSON wire format: one [WireMessage] as a JSON object per line, both directions. The
 * `type` discriminator plus ignore-unknown-keys keeps the protocol debuggable with `nc` and
 * tolerant of version drift; a versioned [Hello] opens both sides of every connection.
 */
public val wireJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}

@Serializable
public sealed interface WireMessage

// ---- Both directions ----

@Serializable
@SerialName("hello")
public data class Hello(
    val protocolVersion: Int,
    val app: String,
    val capabilities: Set<String> = emptySet(),
) : WireMessage

// ---- Server to client ----

@Serializable
@SerialName("eventBatch")
public data class EventBatch(
    val events: List<WireEvent>,
    val droppedEvents: Long = 0,
) : WireMessage

@Serializable
@SerialName("containers")
public data class ContainerUpdate(
    val containers: List<WireContainer>,
) : WireMessage

@Serializable
@SerialName("travelState")
public data class WireTravelState(
    val inspecting: Boolean,
    val cursorSeq: Long? = null,
    val cursorPosition: Int = 0,
    val reductionCount: Int = 0,
) : WireMessage

@Serializable
@SerialName("cleared")
public data object Cleared : WireMessage

// ---- Client to server ----

@Serializable
@SerialName("inspect")
public data object InspectCommand : WireMessage

@Serializable
@SerialName("resume")
public data object ResumeCommand : WireMessage

@Serializable
@SerialName("stepBackward")
public data object StepBackwardCommand : WireMessage

@Serializable
@SerialName("stepForward")
public data object StepForwardCommand : WireMessage

@Serializable
@SerialName("moveToStart")
public data object MoveToStartCommand : WireMessage

@Serializable
@SerialName("moveToEnd")
public data object MoveToEndCommand : WireMessage

@Serializable
@SerialName("seekTo")
public data class SeekToCommand(val seq: Long) : WireMessage

@Serializable
@SerialName("clear")
public data object ClearCommand : WireMessage

// ---- Payload types ----

@Serializable
public data class WireContainer(
    val containerId: Long,
    val name: String? = null,
    val attachedAtMillis: Long = 0,
)

/** Contents of the `$TMPDIR/orbit-spaceflight/<pid>.json` discovery file. */
@Serializable
public data class DiscoveryInfo(
    val port: Int,
    val app: String,
    val pid: Long,
)

public enum class WireEventType {
    ATTACHED,
    DETACHED,
    INTENT_DISPATCHED,
    INTENT_COMPLETED,
    REDUCTION,
    SIDE_EFFECT,
    DIAGNOSTIC,
}

/**
 * A recorded event flattened for the wire. States and values are *rendered strings* — live
 * object references never cross process boundaries. External states are derived and rendered
 * by the producing app at send time, and omitted when the container's transform is identity.
 */
@Serializable
public data class WireEvent(
    val seq: Long,
    val timeMillis: Long,
    val eventType: WireEventType,
    val containerId: Long? = null,
    val intentId: Long? = null,
    val name: String? = null,
    val result: String? = null,
    val oldState: String? = null,
    val newState: String? = null,
    val externalOldState: String? = null,
    val externalNewState: String? = null,
    val noOp: Boolean = false,
    val value: String? = null,
)

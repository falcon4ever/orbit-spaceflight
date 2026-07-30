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

package net.multigesture.spaceflight.android

import android.content.Context
import android.content.pm.ApplicationInfo
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.server.SpaceflightServer

/** Why [serveSpaceflight] did or did not start. */
public sealed interface ServeResult {
    /** Serving on [address], e.g. `localabstract:orbit-spaceflight:com.example.app`. */
    public data class Serving(public val address: String, public val server: SpaceflightServer) : ServeResult

    /** Refused because the app is not debuggable — the default, deliberate behaviour. */
    public data object NotDebuggable : ServeResult

    public data class Failed(public val cause: Throwable) : ServeResult
}

/**
 * Serves the recording to Mission Control over an abstract Unix domain socket, **only if the
 * app is debuggable**.
 *
 * The socket accepts commands that can freeze and rewind the app's UI, so it must never be
 * reachable in a shipped build. Two layers guard that:
 *
 * 1. This debuggable check, which has no opt-out.
 * 2. Depending on `orbit-spaceflight-noop` in release variants, so none of this code is
 *    compiled into public builds at all.
 *
 * Host side:
 * ```
 * adb forward tcp:0 localabstract:orbit-spaceflight:<applicationId>
 * ```
 * Mission Control does this for you when you pick a device under **Connect**.
 */
public fun serveSpaceflight(
    context: Context,
    recorder: FlightRecorder,
    retrograde: Retrograde? = null,
    process: String? = null,
): ServeResult {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return ServeResult.NotDebuggable

    return runCatching {
        val name = LocalSocketTransport.nameFor(context.packageName, process)
        val server = SpaceflightServer(
            recorder = recorder,
            retrograde = retrograde,
            appName = context.packageName,
            transport = LocalSocketTransport(name),
        )
        ServeResult.Serving(server.start(), server)
    }.getOrElse { ServeResult.Failed(it) }
}

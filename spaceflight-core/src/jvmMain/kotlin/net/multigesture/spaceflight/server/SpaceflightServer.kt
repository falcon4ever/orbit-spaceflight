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

package net.multigesture.spaceflight.server

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.protocol.DiscoveryInfo
import net.multigesture.spaceflight.protocol.wireJson
import java.io.File

/**
 * Serves the flight recording (and Retrograde, when provided) to Mission Control over a
 * [SpaceflightTransport]: a loopback-only TCP socket on desktop, or an abstract Unix domain
 * socket on Android (`orbit-spaceflight-android`), which needs no INTERNET permission.
 *
 * Where the transport has a port, it is announced through a discovery file in
 * `$TMPDIR/orbit-spaceflight/<pid>.json` so clients can list local recordable apps.
 *
 * Encoding and socket I/O run on their own dispatcher; a slow or dead client never touches
 * a container's hot path.
 */
public class SpaceflightServer(
    private val recorder: FlightRecorder,
    private val retrograde: Retrograde? = null,
    private val appName: String = "app",
    private val transport: SpaceflightTransport = LoopbackTcpTransport(),
    /** Called when a client's handshake reports a different protocol version. */
    private val onProtocolMismatch: (String) -> Unit = {},
) {
    // The last line of defence: nothing served to a debugger may ever kill the host app.
    // Reaching this handler is a Spaceflight bug; it costs the failing connection only.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            System.err.println("SpaceflightServer: connection failed: $e")
        }
    )
    private var started = false
    private var stopped = false
    private var discoveryFile: File? = null

    /** The transport's port, when it has one. */
    public val port: Int? get() = transport.port

    /** Binds, publishes discovery where possible, and begins accepting clients. */
    public fun start(): String {
        check(!started) { "SpaceflightServer is already started" }
        // Single-use: stop() cancels the scope, so a restarted accept loop would never run
        check(!stopped) { "SpaceflightServer cannot be restarted - create a new instance" }
        started = true

        transport.bind()

        // Best effort, and only meaningful for port-based transports: Android has no
        // ProcessHandle and no host-visible tmpdir, so there `adb forward localabstract:`
        // plus the socket name is the discovery mechanism instead
        val currentPort = transport.port
        if (currentPort != null) {
            runCatching {
                val pid = ProcessHandle.current().pid()
                val dir = File(System.getProperty("java.io.tmpdir"), DISCOVERY_DIR).apply { mkdirs() }
                discoveryFile = File(dir, "$pid.json").apply {
                    writeText(wireJson.encodeToString(DiscoveryInfo.serializer(), DiscoveryInfo(currentPort, appName, pid)))
                    deleteOnExit()
                }
            }
        }

        scope.launch {
            while (true) {
                val client = transport.accept() ?: break
                // Direct child of the supervisor scope: one client dying (including
                // discovery probes that connect and immediately hang up) must never
                // take down the accept loop or the other connections
                this@SpaceflightServer.scope.launch { serve(client) }
            }
        }
        return transport.description
    }

    public fun stop() {
        stopped = true
        runCatching { transport.close() }
        discoveryFile?.delete()
        scope.cancel()
    }

    private val session = SpaceflightSession(recorder, retrograde, appName, onProtocolMismatch)

    private suspend fun serve(client: SpaceflightConnection) {
        // Adapt the stream pair to the protocol's line-oriented view; the session logic
        // itself is common code shared with the appleMain server
        val connection = object : WireConnection {
            private val reader = client.input.bufferedReader()
            private val writer = client.output.bufferedWriter()

            override fun readLine(): String? = runCatching { reader.readLine() }.getOrNull()

            override fun writeLine(line: String) {
                try {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                } catch (e: java.io.IOException) {
                    throw ConnectionClosedException(e)
                }
            }

            override fun close() {
                runCatching { client.close() }
            }
        }
        session.serve(connection)
    }

    public companion object {
        public const val DISCOVERY_DIR: String = "orbit-spaceflight"
    }
}

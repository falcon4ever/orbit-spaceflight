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

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * How [SpaceflightServer] listens for Mission Control. The protocol is transport-agnostic:
 *
 * - [LoopbackTcpTransport] — desktop JVM apps, loopback-only on an OS-assigned port.
 * - `LocalSocketTransport` (in `orbit-spaceflight-android`) — an abstract Unix domain socket,
 *   so Android apps need no INTERNET permission and open no network listener at all.
 */
public interface SpaceflightTransport : Closeable {

    /** Human-readable address, valid after [bind] — logged and shown in tooling. */
    public val description: String

    /** The TCP port, when this transport has one; null for socket types without ports. */
    public val port: Int?

    public fun bind()

    /** Blocks until a client arrives; returns null once the transport is closed. */
    public fun accept(): SpaceflightConnection?
}

public interface SpaceflightConnection : Closeable {
    public val input: InputStream
    public val output: OutputStream
}

/**
 * Binds `127.0.0.1` on [requestedPort] (0 = OS-assigned). Loopback-only, so nothing is
 * reachable from another machine, and an ephemeral port cannot collide with another service
 * the way a fixed one can.
 */
public class LoopbackTcpTransport(private val requestedPort: Int = 0) : SpaceflightTransport {

    private var serverSocket: ServerSocket? = null

    override val port: Int? get() = serverSocket?.localPort

    override val description: String get() = "127.0.0.1:${port ?: "-"}"

    override fun bind() {
        check(serverSocket == null) { "Transport is already bound" }
        serverSocket = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort))
        }
    }

    override fun accept(): SpaceflightConnection? {
        val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: return null
        socket.tcpNoDelay = true
        return object : SpaceflightConnection {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override fun close() {
                runCatching { socket.close() }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

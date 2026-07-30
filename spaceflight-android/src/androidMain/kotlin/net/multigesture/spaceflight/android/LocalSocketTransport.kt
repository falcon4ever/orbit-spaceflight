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

import android.net.LocalServerSocket
import android.os.Process
import java.io.InputStream
import java.io.OutputStream
import net.multigesture.spaceflight.server.SpaceflightConnection
import net.multigesture.spaceflight.server.SpaceflightTransport

/**
 * Serves over an **abstract Unix domain socket** rather than TCP. Compared with a network
 * socket this means:
 *
 * - no `INTERNET` permission,
 * - no listener reachable from anywhere but this device,
 * - no port to collide with another app or service.
 *
 * Abstract sockets carry no filesystem permissions, so [accept] additionally verifies the
 * peer's uid: only this app itself and adb's shell user are admitted. Other apps on the
 * device are disconnected without a reply.
 *
 * The host side reaches it with `adb forward tcp:0 localabstract:<name>`.
 */
public class LocalSocketTransport(private val name: String) : SpaceflightTransport {

    private var serverSocket: LocalServerSocket? = null

    /** Abstract sockets have no port. */
    override val port: Int? = null

    override val description: String get() = "localabstract:$name"

    override fun bind() {
        check(serverSocket == null) { "Transport is already bound" }
        serverSocket = LocalServerSocket(name)
    }

    override fun accept(): SpaceflightConnection? {
        // Loop: a rejected peer must not end the accept loop (returning null would)
        while (true) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: return null

            // Abstract sockets have no filesystem permissions, so any app on the device can
            // connect. The recording crosses this socket unredacted and the commands can
            // freeze the UI, so only trusted peers are admitted: this app itself, and adb
            // (an `adb forward` connection presents as the shell user).
            val peerUid = runCatching { socket.peerCredentials.uid }.getOrNull()
            if (peerUid == null || (peerUid != Process.myUid() && peerUid != SHELL_UID && peerUid != ROOT_UID)) {
                runCatching { socket.close() }
                continue
            }

            return object : SpaceflightConnection {
                override val input: InputStream = socket.inputStream
                override val output: OutputStream = socket.outputStream
                override fun close() {
                    runCatching { socket.close() }
                }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    public companion object {
        /** `adb forward` connections arrive as the shell user. */
        private const val SHELL_UID = 2000

        /** Rooted debug scenarios (`adb root`) arrive as root. */
        private const val ROOT_UID = 0

        /**
         * The conventional socket name for an app: `orbit-spaceflight:<applicationId>`.
         * Multi-process apps should add a process suffix.
         */
        public fun nameFor(applicationId: String, process: String? = null): String =
            "orbit-spaceflight:$applicationId" + (process?.let { ":$it" } ?: "")
    }
}

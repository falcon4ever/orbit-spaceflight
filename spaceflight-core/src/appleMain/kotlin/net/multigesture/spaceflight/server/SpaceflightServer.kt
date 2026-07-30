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

// UnsafeNumber: recv/send/NSStringEncoding commonize with platform-dependent integer
// widths; every target this module ships is 64-bit Apple, so the widths agree in practice
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package net.multigesture.spaceflight.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.protocol.DiscoveryInfo
import net.multigesture.spaceflight.protocol.wireJson
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * The Apple-platform Spaceflight server: a loopback-only POSIX socket, OS-assigned port.
 *
 * On the **iOS simulator** this is directly reachable from the host — the simulator shares
 * the Mac's network stack, and simulator processes are host processes, so the discovery
 * file written to the app's `NSTemporaryDirectory()` is found by Mission Control scanning
 * the simulator containers, pid liveness checks included.
 *
 * On a **physical device** the loopback socket is reachable only on-device; bridging to a
 * host (usbmux port forwarding) is future work.
 *
 * The protocol logic is [SpaceflightSession], shared with the JVM server.
 */
public class SpaceflightServer(
    recorder: FlightRecorder,
    retrograde: Retrograde? = null,
    private val appName: String = "app",
    private val requestedPort: Int = 0,
    onProtocolMismatch: (String) -> Unit = {},
) {
    // The last line of defence: nothing served to a debugger may ever kill the host app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            println("SpaceflightServer: connection failed: $e")
        }
    )
    private val session = SpaceflightSession(recorder, retrograde, appName, onProtocolMismatch)
    private var serverFd: Int = -1
    private var stopped = false
    private var discoveryPath: String? = null

    public var port: Int? = null
        private set

    /** Binds, writes the discovery file, and begins accepting clients. Returns the address. */
    public fun start(): String {
        check(serverFd < 0) { "SpaceflightServer is already started" }
        check(!stopped) { "SpaceflightServer cannot be restarted - create a new instance" }

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed" }
        serverFd = fd

        memScoped {
            val enable = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, enable.ptr, sizeOf<IntVar>().convert())

            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            address.sin_port = requestedPort.toNetworkOrder()
            // 127.0.0.1 in network byte order, written for little-endian Apple targets
            address.sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
            check(bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
            check(listen(fd, BACKLOG) == 0) { "listen() failed" }

            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(fd, address.ptr.reinterpret(), length.ptr)
            port = address.sin_port.fromNetworkOrder()
        }

        writeDiscoveryFile(port ?: 0)

        scope.launch {
            while (true) {
                val client = accept(fd, null, null)
                if (client < 0) break // server socket closed
                memScoped {
                    // send() on a dead peer must fail with an error, not raise SIGPIPE and
                    // kill the host app
                    val enable = alloc<IntVar>().apply { value = 1 }
                    setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, enable.ptr, sizeOf<IntVar>().convert())
                }
                // Direct child of the supervisor scope: one client dying must never take
                // down the accept loop or the other connections
                this@SpaceflightServer.scope.launch { session.serve(PosixWireConnection(client)) }
            }
        }
        return "127.0.0.1:$port"
    }

    public fun stop() {
        stopped = true
        if (serverFd >= 0) platform.posix.close(serverFd)
        serverFd = -1
        discoveryPath?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        scope.cancel()
    }

    private fun writeDiscoveryFile(boundPort: Int) {
        runCatching {
            val pid = NSProcessInfo.processInfo.processIdentifier.toLong()
            val dir = NSTemporaryDirectory() + DISCOVERY_DIR
            NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
            val path = "$dir/$pid.json"
            val json = wireJson.encodeToString(DiscoveryInfo.serializer(), DiscoveryInfo(boundPort, appName, pid))
            @Suppress("CAST_NEVER_SUCCEEDS")
            (json as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            discoveryPath = path
        }
    }

    public companion object {
        public const val DISCOVERY_DIR: String = "orbit-spaceflight"

        private const val BACKLOG = 8
        private val LOOPBACK_NETWORK_ORDER: UInt = 0x0100_007Fu
    }
}

private fun Int.toNetworkOrder(): UShort {
    val value = this and 0xFFFF
    return (((value and 0xFF) shl 8) or ((value ushr 8) and 0xFF)).toUShort()
}

private fun UShort.fromNetworkOrder(): Int {
    val value = toInt()
    return ((value and 0xFF) shl 8) or ((value ushr 8) and 0xFF)
}

/** Line-oriented I/O over a POSIX socket file descriptor. */
internal class PosixWireConnection(private val fd: Int) : WireConnection {

    private val readBuffer = ByteArray(BUFFER_SIZE)
    private var pending = ByteArray(0)

    // recv/send return ssize_t, whose width varies by target (Long on 64-bit, Int on
    // watchosArm32) - convert() is the width-agnostic conversion that keeps every
    // target warning-free where toInt() cannot
    override fun readLine(): String? {
        while (true) {
            val newline = pending.indexOf('\n'.code.toByte())
            if (newline >= 0) {
                val line = pending.copyOfRange(0, newline).decodeToString()
                pending = pending.copyOfRange(newline + 1, pending.size)
                return line
            }
            val received: Int = readBuffer.usePinned { pinned ->
                recv(fd, pinned.addressOf(0), readBuffer.size.convert(), 0)
            }.convert()
            if (received <= 0) return null
            pending += readBuffer.copyOfRange(0, received)
        }
    }

    override fun writeLine(line: String) {
        val bytes = (line + "\n").encodeToByteArray()
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < bytes.size) {
                val sent: Int = send(fd, pinned.addressOf(offset), (bytes.size - offset).convert(), 0).convert()
                if (sent <= 0) throw ConnectionClosedException()
                offset += sent
            }
        }
    }

    override fun close() {
        platform.posix.close(fd)
    }

    private companion object {
        private const val BUFFER_SIZE = 8192
    }
}

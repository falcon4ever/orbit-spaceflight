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

package net.multigesture.spaceflight.missioncontrol

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Socket-name prefix Android apps bind; see `LocalSocketTransport.nameFor`. */
private const val SOCKET_PREFIX = "orbit-spaceflight:"

/**
 * Finds Android apps serving Spaceflight on attached devices.
 *
 * Apps bind an *abstract Unix domain socket* rather than a TCP port (no INTERNET permission,
 * nothing network-reachable), so discovery reads the device's socket table instead of probing
 * ports: every bound abstract socket appears in `/proc/net/unix` as `@orbit-spaceflight:<id>`.
 * Each one found is bridged to the host with `adb forward tcp:0 localabstract:<name>`, reusing
 * an existing forward when there is one.
 */
fun discoverAdbApps(): List<DiscoveredApp> {
    val adb = findAdb() ?: return emptyList()
    val existingForwards = existingForwards(adb)

    return devices(adb).flatMap { serial ->
        socketNames(adb, serial).mapNotNull { name ->
            val hostPort = existingForwards["$serial|$name"]
                ?: runCommand(adb, "-s", serial, "forward", "tcp:0", "localabstract:$name")
                    ?.trim()?.toIntOrNull()
                ?: return@mapNotNull null

            if (probe(hostPort)) {
                val applicationId = name.removePrefix(SOCKET_PREFIX)
                DiscoveredApp(label = "adb $serial — $applicationId", port = hostPort)
            } else {
                runCommand(adb, "-s", serial, "forward", "--remove", "tcp:$hostPort")
                null
            }
        }
    }
}

private fun devices(adb: String): List<String> =
    runCommand(adb, "devices")
        ?.lines()
        ?.drop(1)
        ?.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] == "device") parts[0] else null
        }
        .orEmpty()

/** Abstract socket names bound on the device, read from its socket table. */
private fun socketNames(adb: String, serial: String): List<String> =
    runCommand(adb, "-s", serial, "shell", "cat", "/proc/net/unix")
        ?.lines()
        ?.mapNotNull { line ->
            // "… @orbit-spaceflight:com.example.app" - abstract sockets are prefixed with @
            val at = line.indexOf("@$SOCKET_PREFIX")
            if (at >= 0) line.substring(at + 1).trim() else null
        }
        ?.distinct()
        .orEmpty()

/** Existing `serial|socketName` → host port forwards, so discovery is idempotent. */
private fun existingForwards(adb: String): Map<String, Int> =
    runCommand(adb, "forward", "--list")
        ?.lines()
        ?.mapNotNull { line ->
            // "<serial> tcp:<hostPort> localabstract:<name>"
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 3 && parts[2].startsWith("localabstract:")) {
                val port = parts[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                "${parts[0]}|${parts[2].removePrefix("localabstract:")}" to port
            } else {
                null
            }
        }
        ?.toMap()
        .orEmpty()

private fun findAdb(): String? {
    val fromSdk = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        .filterNotNull()
        .map { File(it, "platform-tools/adb") }
        .firstOrNull { it.canExecute() }
    if (fromSdk != null) return fromSdk.absolutePath

    return System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, "adb") }
        ?.firstOrNull { it.canExecute() }
        ?.absolutePath
}

private fun runCommand(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    // readText() blocks until EOF, which only arrives when the process dies - so the
    // timeout must be enforced by killing the process, not by racing the read. The
    // watchdog also prevents a timed-out process from being leaked.
    Thread {
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }.apply {
        isDaemon = true
        start()
    }
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
}.getOrNull()

private fun probe(port: Int): Boolean = runCatching {
    Socket().use {
        it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MILLIS)
        true
    }
}.getOrDefault(false)

private const val PROBE_TIMEOUT_MILLIS = 400
private const val COMMAND_TIMEOUT_SECONDS = 5L

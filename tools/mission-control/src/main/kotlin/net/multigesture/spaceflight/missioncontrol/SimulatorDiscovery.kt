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
import net.multigesture.spaceflight.protocol.DiscoveryInfo
import net.multigesture.spaceflight.protocol.wireJson

/**
 * Finds iOS **simulator** apps serving Spaceflight.
 *
 * The simulator shares the Mac's network stack and its processes: an app's loopback socket
 * is a real host port, its pid is a real host pid, and its `NSTemporaryDirectory()` lives
 * inside the simulator container on the host filesystem. So discovery is the same
 * mechanism as desktop apps — read the `orbit-spaceflight/<pid>.json` files, check the pid
 * is alive — just under the CoreSimulator container roots:
 *
 * `~/Library/Developer/CoreSimulator/Devices/<udid>/data/Containers/Data/Application/<app>/tmp/`
 *
 * Physical devices are not discoverable this way (their loopback is on-device); that needs
 * usbmux forwarding and is future work.
 */
fun discoverSimulatorApps(): List<DiscoveredApp> {
    val devicesRoot = File(System.getProperty("user.home"), "Library/Developer/CoreSimulator/Devices")
    val deviceDirs = devicesRoot.listFiles { file -> file.isDirectory } ?: return emptyList()

    return deviceDirs.flatMap { device ->
        val containers = File(device, "data/Containers/Data/Application")
            .listFiles { file -> file.isDirectory } ?: return@flatMap emptyList()
        containers.flatMap { container ->
            val discoveryFiles = File(container, "tmp/orbit-spaceflight")
                .listFiles { file -> file.extension == "json" } ?: return@flatMap emptyList()
            discoveryFiles.mapNotNull { file ->
                val info = runCatching {
                    wireJson.decodeFromString(DiscoveryInfo.serializer(), file.readText())
                }.getOrNull() ?: return@mapNotNull null

                // Simulator pids are host pids, so liveness works exactly like desktop
                val alive = ProcessHandle.of(info.pid).map { it.isAlive }.orElse(false)
                if (!alive) {
                    file.delete()
                    return@mapNotNull null
                }
                DiscoveredApp(label = "iOS sim — ${info.app} (pid ${info.pid})", port = info.port)
            }
        }
    }.sortedBy { it.label }
}

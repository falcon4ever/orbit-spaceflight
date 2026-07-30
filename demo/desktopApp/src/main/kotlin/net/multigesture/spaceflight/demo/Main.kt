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

package net.multigesture.spaceflight.demo

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.orbitmvi.orbit.Orbit
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.compositeEventObserver
import net.multigesture.spaceflight.logging.LoggingEventObserver
import net.multigesture.spaceflight.server.SpaceflightServer

fun main() {
    val recorder = OrbitSpaceflight.recorder ?: OrbitSpaceflight.install { capacity = 500 }
    val retrograde = Retrograde(recorder)
    Orbit.configureDefaults {
        eventObserver = compositeEventObserver(recorder.eventObserver, LoggingEventObserver())
        containerDecoration = retrograde.containerDecoration
    }
    // Serve the recording + time travel to Mission Control over loopback
    val address = SpaceflightServer(recorder, retrograde, appName = "spaceflight-demo").start()
    println("spaceflight-demo serving Mission Control on $address")

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Orbit Spaceflight Demo",
            state = rememberWindowState(width = 480.dp, height = 800.dp),
        ) {
            App()
        }
    }
}

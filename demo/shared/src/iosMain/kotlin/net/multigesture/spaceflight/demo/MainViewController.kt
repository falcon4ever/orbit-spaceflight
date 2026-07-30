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

import androidx.compose.ui.window.ComposeUIViewController
import org.orbitmvi.orbit.Orbit
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.compositeEventObserver
import net.multigesture.spaceflight.logging.LoggingEventObserver
import net.multigesture.spaceflight.server.SpaceflightServer
import platform.UIKit.UIViewController

/**
 * The iOS app's entry point, called from Swift. Recording, logging and live attach all work
 * as on the other platforms: on the simulator the POSIX loopback server is a real host port,
 * so Mission Control discovers this app like any desktop process.
 */
@Suppress("FunctionNaming") // iOS convention for the Swift-visible factory
public fun MainViewController(): UIViewController {
    val recorder = OrbitSpaceflight.recorder ?: OrbitSpaceflight.install { capacity = 500 }
    val retrograde = Retrograde(recorder)
    Orbit.configureDefaults {
        eventObserver = compositeEventObserver(recorder.eventObserver, LoggingEventObserver())
        containerDecoration = retrograde.containerDecoration
    }
    // Serve the recording + time travel to Mission Control (simulator: reachable from the
    // host directly; the accept loop lives on the server's own IO dispatcher)
    val address = SpaceflightServer(recorder, retrograde, appName = "spaceflight-demo-ios").start()
    println("spaceflight-demo-ios serving Mission Control on " + address)
    return ComposeUIViewController { App() }
}

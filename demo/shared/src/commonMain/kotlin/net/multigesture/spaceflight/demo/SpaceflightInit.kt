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

import org.orbitmvi.orbit.Orbit
import net.multigesture.spaceflight.FlightRecorder
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.compositeEventObserver
import net.multigesture.spaceflight.logging.LoggingEventObserver

/**
 * Installs the flight recorder and structured logging once, at app startup.
 *
 * The small capacity is deliberate: type into the launchpad search box for a while and the
 * ring evicts, so the gap Diagnostic shows up on the Flight Recorder screen.
 */
fun installSpaceflight(): FlightRecorder =
    OrbitSpaceflight.recorder ?: run {
        val recorder = OrbitSpaceflight.install {
            capacity = 500
        }
        Orbit.configureDefaults {
            eventObserver = compositeEventObserver(
                recorder.eventObserver,
                LoggingEventObserver(),
            )
        }
        recorder
    }

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

import android.app.Application
import android.util.Log
import kotlin.concurrent.thread
import org.orbitmvi.orbit.Orbit
import net.multigesture.spaceflight.android.ServeResult
import net.multigesture.spaceflight.android.serveSpaceflight
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.compositeEventObserver
import net.multigesture.spaceflight.logging.LoggingEventObserver

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DemoAppContext.context = this

        val recorder = OrbitSpaceflight.recorder ?: OrbitSpaceflight.install { capacity = 500 }
        val retrograde = Retrograde(recorder)
        Orbit.configureDefaults {
            eventObserver = compositeEventObserver(
                recorder.eventObserver,
                LoggingEventObserver(sink = { Log.d("Spaceflight", it) }),
            )
            containerDecoration = retrograde.containerDecoration
        }

        // Serve Mission Control over an abstract Unix domain socket - no INTERNET
        // permission, debuggable builds only. Off the main thread; sockets on Android.
        thread(name = "spaceflight-server") {
            when (val result = serveSpaceflight(this, recorder, retrograde)) {
                is ServeResult.Serving ->
                    Log.i("Spaceflight", "serving Mission Control on ${result.address}")
                is ServeResult.NotDebuggable ->
                    Log.i("Spaceflight", "not serving: this build is not debuggable")
                is ServeResult.Failed ->
                    Log.w("Spaceflight", "SpaceflightServer failed to start", result.cause)
            }
        }
    }
}

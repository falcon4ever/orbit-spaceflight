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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Window
import java.io.File
import java.io.FilenameFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.Orbit
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.Retrograde
import net.multigesture.spaceflight.compositeEventObserver
import net.multigesture.spaceflight.demo.App
import net.multigesture.spaceflight.logging.LoggingEventObserver

private const val SESSION_EXTENSION = ".orbitsession"

/** Native open/save dialog filtered to session files. */
private fun chooseSessionFile(owner: Window?, save: Boolean): File? {
    val dialog = FileDialog(owner as? java.awt.Frame, if (save) "Save session" else "Open session")
    dialog.mode = if (save) FileDialog.SAVE else FileDialog.LOAD
    dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(SESSION_EXTENSION) }
    if (save) dialog.file = "recording$SESSION_EXTENSION"
    dialog.isVisible = true

    val name = dialog.file ?: return null
    val chosen = File(dialog.directory ?: ".", name)
    return if (save && !chosen.name.endsWith(SESSION_EXTENSION)) {
        File(chosen.parentFile, chosen.name + SESSION_EXTENSION)
    } else {
        chosen
    }
}

/**
 * Mission Control — attaches to serving apps over the loopback wire protocol.
 *
 * By default it starts standalone: use Connect to attach to any app running a
 * SpaceflightServer (e.g. `./gradlew :demo:desktopApp:run`). Pass `--embedded-demo` to also
 * spawn the demo app in a second window of this JVM, recorded in-process — the dev-harness
 * mode used for iterating on the UI itself.
 */
fun main(args: Array<String>) {
    val embeddedDemo = "--embedded-demo" in args
    val version = System.getProperty("spaceflight.version") ?: "dev"

    val inProcessSource: InProcessTimelineSource? = if (embeddedDemo) {
        val recorder = OrbitSpaceflight.recorder ?: OrbitSpaceflight.install { capacity = 500 }
        val retrograde = Retrograde(recorder)
        Orbit.configureDefaults {
            eventObserver = compositeEventObserver(recorder.eventObserver, LoggingEventObserver())
            containerDecoration = retrograde.containerDecoration
        }
        InProcessTimelineSource(recorder, retrograde, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    } else {
        null
    }

    application {
        if (inProcessSource != null) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Spaceflight Demo (recorded in-process)",
                state = rememberWindowState(width = 420.dp, height = 760.dp),
            ) {
                App()
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Orbit Spaceflight — Mission Control v$version",
            state = rememberWindowState(width = 1460.dp, height = 1120.dp),
        ) {
            var remote by remember { mutableStateOf<TimelineSource?>(null) }
            var status by remember { mutableStateOf<String?>(null) }
            val window = this.window
            val activeSource = remote ?: inProcessSource
            val ioScope = rememberCoroutineScope()

            MissionControlApp(
                source = activeSource,
                onDiscoverApps = { discoverLocalApps() + discoverSimulatorApps() + discoverAdbApps() },
                onSelectApp = { app ->
                    remote?.close()
                    status = null
                    remote = app?.let {
                        SocketTimelineSource(it.host, it.port, label = "${it.label} — ${it.host}:${it.port}")
                    }
                },
                hasInProcessSource = inProcessSource != null,
                onOpenSession = {
                    // The dialog is modal UI; the gunzip+parse is not - keep it off this thread
                    chooseSessionFile(window, save = false)?.let { file ->
                        status = "opening ${file.name}…"
                        ioScope.launch {
                            withContext(Dispatchers.IO) { runCatching { SessionTimelineSource(file) } }
                                .onSuccess { opened ->
                                    remote?.close()
                                    remote = opened
                                    status = null
                                }
                                .onFailure { failure -> status = "could not open: ${failure.message}" }
                        }
                    }
                },
                onSaveSession = activeSource?.let { current ->
                    {
                        chooseSessionFile(window, save = true)?.let { file ->
                            status = "saving ${file.name}…"
                            ioScope.launch {
                                status = withContext(Dispatchers.IO) {
                                    runCatching {
                                        current.saveSessionTo(file)
                                        "saved ${file.name}"
                                    }.getOrElse { failure -> "could not save: ${failure.message}" }
                                }
                            }
                        }
                    }
                },
                statusMessage = status,
            )
        }
    }
}

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

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.session.exportSession
import net.multigesture.spaceflight.session.writeTo

actual suspend fun shareCurrentSession(): String = withContext(Dispatchers.IO) {
    val recorder = OrbitSpaceflight.recorder ?: return@withContext "recorder not installed"
    val now = System.currentTimeMillis()
    val file = File(System.getProperty("user.home"), "spaceflight-$now.orbitsession")

    recorder.exportSession(
        appName = "spaceflight-demo",
        platform = "jvm",
        exportedAtMillis = now,
    ).writeTo(file)

    "Saved ${file.name} to your home folder"
}

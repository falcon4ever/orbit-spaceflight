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

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.multigesture.spaceflight.OrbitSpaceflight
import net.multigesture.spaceflight.session.exportSession
import net.multigesture.spaceflight.session.writeTo

/**
 * Set once from the Application so shared code can share without threading a Context
 * through the UI. A real integration would inject this properly.
 */
object DemoAppContext {
    @Volatile
    var context: Context? = null
}

actual suspend fun shareCurrentSession(): String = withContext(Dispatchers.IO) {
    val context = DemoAppContext.context ?: return@withContext "no context available"
    val recorder = OrbitSpaceflight.recorder ?: return@withContext "recorder not installed"

    val now = System.currentTimeMillis()
    val dir = File(context.cacheDir, "sessions").apply { mkdirs() }
    val file = File(dir, "spaceflight-$now.orbitsession")

    recorder.exportSession(
        appName = "spaceflight-demo-android",
        platform = "android",
        exportedAtMillis = now,
    ).writeTo(file)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.sessions", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/gzip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Orbit Spaceflight session")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(share, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    "Shared ${file.name}"
}

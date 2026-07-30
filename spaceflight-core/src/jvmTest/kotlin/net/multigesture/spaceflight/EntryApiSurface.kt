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

package net.multigesture.spaceflight

import java.io.File
import java.lang.reflect.Modifier

/** Renders a class's public members as stable signature strings, sorted. */
internal fun publicSurfaceOf(clazz: Class<*>): List<String> {
    val methods = clazz.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) }
        .map { method ->
            val params = method.parameterTypes.joinToString(", ") { it.simpleName }
            "fun ${method.name}($params): ${method.returnType.simpleName}"
        }
    val fields = clazz.declaredFields
        .filter { Modifier.isPublic(it.modifiers) }
        .map { field -> "val ${field.name}: ${field.type.simpleName}" }
    return (methods + fields).sorted()
}

/** The single source of truth both artifacts are checked against. */
internal fun expectedEntryApi(): List<String> =
    entryApiFile().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

internal fun writeEntryApi(surface: List<String>) {
    entryApiFile().writeText(
        """
        |# The Spaceflight entry API: the surface orbit-spaceflight and orbit-spaceflight-noop
        |# must share exactly, so release variants can swap the twin in. Regenerate with
        |#   ./gradlew :orbit-spaceflight:jvmTest -Dspaceflight.updateEntryApi=true
        |
        |
        """.trimMargin() + surface.joinToString("\n") + "\n"
    )
}

internal fun entryApiFile(): File {
    // Tests run with the module directory as working directory
    val candidates = listOf(File("../spaceflight-entry-api.txt"), File("spaceflight-entry-api.txt"))
    return candidates.firstOrNull { it.exists() }
        ?: error("spaceflight-entry-api.txt not found (looked in ${candidates.map { it.absolutePath }})")
}

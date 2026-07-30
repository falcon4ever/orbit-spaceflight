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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real artifact's half of the entry-API parity check: this and the identical test in
 * `orbit-spaceflight-noop` both compare [Spaceflight]'s public surface to the single
 * checked-in `spaceflight-entry-api.txt`. Change one side and its test fails until the file
 * is regenerated; the twin's test then fails until it is mirrored — so the two artifacts
 * cannot drift apart silently.
 */
internal class EntryApiParityTest {

    @Test
    fun entry_api_matches_the_declared_surface() {
        val actual = publicSurfaceOf(Spaceflight::class.java)

        // Regenerate with:
        //   ./gradlew :orbit-spaceflight:jvmTest -Dspaceflight.updateEntryApi=true
        if (System.getProperty("spaceflight.updateEntryApi") == "true") {
            writeEntryApi(actual)
            return
        }

        assertEquals(
            expectedEntryApi(),
            actual,
            "Spaceflight's entry API changed - regenerate spaceflight-entry-api.txt and mirror it in orbit-spaceflight-noop",
        )
    }
}

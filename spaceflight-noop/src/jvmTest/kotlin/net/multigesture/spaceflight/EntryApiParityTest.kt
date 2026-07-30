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
 * The no-op twin's half of the entry-API parity check — see the identical test in
 * `orbit-spaceflight`. Both compare [Spaceflight]'s public surface to the single checked-in
 * `spaceflight-entry-api.txt`, so the twin cannot drift from the real artifact silently.
 */
internal class EntryApiParityTest {

    @Test
    fun entry_api_matches_the_declared_surface() {
        assertEquals(
            expectedEntryApi(),
            publicSurfaceOf(Spaceflight::class.java),
            "The no-op twin no longer mirrors the real entry API in spaceflight-entry-api.txt",
        )
    }
}

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

import kotlinx.coroutines.delay

data class Mission(
    val id: Int,
    val name: String,
    val destination: String,
    val description: String,
)

/** Fake data source with artificial latency, so loading states show up in the recorder. */
object MissionRepository {

    val missions: List<Mission> = listOf(
        Mission(1, "Apollo 11", "The Moon", "First crewed lunar landing. Neil Armstrong and Buzz Aldrin walk on the Sea of Tranquility."),
        Mission(2, "Voyager 1", "Interstellar space", "Grand tour of Jupiter and Saturn, now the most distant human-made object."),
        Mission(3, "Cassini-Huygens", "Saturn", "Thirteen years orbiting Saturn and a probe landing on Titan."),
        Mission(4, "New Horizons", "Pluto", "First flyby of Pluto and the Kuiper Belt object Arrokoth."),
        Mission(5, "Rosetta", "Comet 67P", "First spacecraft to orbit a comet and land a probe on its surface."),
        Mission(6, "Artemis II", "The Moon", "First crewed flight of Orion around the Moon since Apollo."),
        Mission(7, "Juno", "Jupiter", "Polar orbiter studying Jupiter's composition, gravity and magnetic field."),
        Mission(8, "Parker Solar Probe", "The Sun", "Fastest spacecraft ever built, flying through the solar corona."),
    )

    suspend fun mission(id: Int): Mission {
        delay(400)
        return missions.first { it.id == id }
    }
}

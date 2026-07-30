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

import kotlin.time.Clock

/**
 * Configuration for a [FlightRecorder].
 *
 * @property capacity maximum number of events retained in the global ring. When exceeded, the
 * oldest unprotected events are evicted. Defaults to [DEFAULT_CAPACITY]; dogfood builds
 * typically use a smaller cap such as [DOGFOOD_CAPACITY].
 * @property minRetainedReductionsPerContainer the newest reductions of each live container that
 * survive global eviction, so chatty containers cannot flush quiet containers' history before
 * a crash. Retention headroom on top of [capacity].
 * @property excludedContainerNames container names (see
 * `SettingsBuilder.containerName`) that are not recorded at all.
 * @property timeSource wall-clock source for [SpaceflightEvent.timeMillis]; injectable for tests.
 */
public class FlightRecorderConfig internal constructor(
    public val capacity: Int,
    public val minRetainedReductionsPerContainer: Int,
    public val excludedContainerNames: Set<String>,
    public val timeSource: () -> Long,
) {

    public class Builder internal constructor() {
        public var capacity: Int = DEFAULT_CAPACITY
        public var minRetainedReductionsPerContainer: Int = DEFAULT_MIN_RETAINED_REDUCTIONS
        public var timeSource: () -> Long = { Clock.System.now().toEpochMilliseconds() }

        private val excludedContainerNames = mutableSetOf<String>()

        /** Excludes containers with the given [names] from recording entirely. */
        public fun exclude(vararg names: String) {
            excludedContainerNames += names
        }

        internal fun build(): FlightRecorderConfig {
            require(capacity > 0) { "capacity must be positive, was $capacity" }
            require(minRetainedReductionsPerContainer >= 0) {
                "minRetainedReductionsPerContainer must not be negative, was $minRetainedReductionsPerContainer"
            }
            return FlightRecorderConfig(
                capacity = capacity,
                minRetainedReductionsPerContainer = minRetainedReductionsPerContainer,
                excludedContainerNames = excludedContainerNames.toSet(),
                timeSource = timeSource,
            )
        }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 2_000
        public const val DOGFOOD_CAPACITY: Int = 300
        public const val DEFAULT_MIN_RETAINED_REDUCTIONS: Int = 25
    }
}

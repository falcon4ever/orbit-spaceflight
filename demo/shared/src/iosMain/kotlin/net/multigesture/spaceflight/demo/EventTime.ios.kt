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

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

private val timeFormatter = NSDateFormatter().apply { dateFormat = "HH:mm:ss.SSS" }
private val dateTimeFormatter = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HH:mm:ss.SSS" }

private fun Long.toNSDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(this / 1000.0)

actual fun formatEventTime(timeMillis: Long): String = timeFormatter.stringFromDate(timeMillis.toNSDate())

actual fun formatEventDateTime(timeMillis: Long): String = dateTimeFormatter.stringFromDate(timeMillis.toNSDate())

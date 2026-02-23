package no.nav.helsemelding.outbound

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Truncate nanoseconds → milliseconds. This avoids precision differences
 * between JVM, Postgres, GitHub Actions, Docker etc when running tests.
 * Works even if Postgres uses microsecond precision internally.
 */
internal fun Instant.truncateToMillis(): Instant {
    val original = toLocalDateTime(TimeZone.currentSystemDefault())
    val converted = LocalDateTime(original.date, original.time.truncateToMillis())
    return converted.toInstant(TimeZone.currentSystemDefault())
}

private fun LocalTime.truncateToMillis(): LocalTime =
    LocalTime.fromNanosecondOfDay(
        toNanosecondOfDay().let { it - it % DateTimeUnit.MILLISECOND.nanoseconds }
    )

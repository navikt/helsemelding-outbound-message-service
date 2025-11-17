package no.nav.emottak.state.util

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.append
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.datetime.KotlinInstantColumnType
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A reusable SQL expression for: NOW() - INTERVAL '<seconds> seconds'
 * Uses `KotlinInstantColumnType` for kotlinx.datetime.Instant.
 */
class NowMinusIntervalExpression(
    private val seconds: Long
) : CustomFunction<Instant>(
    functionName = "NOW_MINUS_INTERVAL",
    columnType = KotlinInstantColumnType()
) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(NOW() - INTERVAL '", seconds.toString(), " seconds')")
    }
}

/**
 * Returns a condition like: column <= NOW() - INTERVAL '<seconds> seconds'
 */
fun Column<Instant?>.olderThanSeconds(seconds: Duration): Op<Boolean> =
    this lessEq NowMinusIntervalExpression(seconds.inWholeSeconds)

fun Expression<*>.toSql(): String = QueryBuilder(true).also { this.toQueryBuilder(it) }.toString()

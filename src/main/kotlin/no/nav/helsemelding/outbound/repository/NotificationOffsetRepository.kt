package no.nav.helsemelding.outbound.repository

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

object NotificationOffsets : Table("notification_offset") {
    val herId = integer("her_id")
    val lastProcessedOffset = long("last_processed_offset")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(herId)
}

/** Stores the last handled notification offset for each her id, allowing consumption to resume after restart. */
interface NotificationOffsetRepository {
    /** Returns the last handled offset, or `0L` when no offset has been stored for [herId]. */
    suspend fun getOffset(herId: Int): Long

    /**
     * Saves [offset] after the notification has been fully handled, including an intentional skip.
     * Receiving a notification alone is not sufficient to advance the stored offset.
     */
    suspend fun saveOffset(herId: Int, offset: Long)
}

class ExposedNotificationOffsetRepository(private val database: Database) : NotificationOffsetRepository {
    override suspend fun getOffset(herId: Int): Long = suspendTransaction(database) {
        NotificationOffsets.selectAll()
            .where { NotificationOffsets.herId eq herId }
            .singleOrNull()
            ?.get(NotificationOffsets.lastProcessedOffset) ?: 0L
    }

    override suspend fun saveOffset(herId: Int, offset: Long) {
        suspendTransaction(database) {
            NotificationOffsets.upsert(NotificationOffsets.herId) {
                it[NotificationOffsets.herId] = herId
                it[lastProcessedOffset] = offset
                it[updatedAt] = CurrentTimestamp
            }
        }
    }
}

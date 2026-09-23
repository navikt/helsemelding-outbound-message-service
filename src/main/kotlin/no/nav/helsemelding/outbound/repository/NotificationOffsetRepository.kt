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

/**
 * Persists the last handled notification offset for each her id so consumption can resume after restart.
 *
 * Offsets are global to the external notification stream and may contain gaps for an individual
 * her id. Each offset identifies a handled notification, rather than a count of notifications.
 */
interface NotificationOffsetRepository {
    /**
     * Retrieves the offset to use when starting notification consumption.
     *
     * @param herId The her id whose notifications are being consumed.
     * @return The last saved offset, or `0L` when no offset exists for [herId].
     */
    suspend fun getOffset(herId: Int): Long

    /**
     * Creates or replaces the offset for [herId] with [offset].
     *
     * The caller must save only after processing has completed successfully, including any required
     * publication and state persistence, or after an intentional skip. A failed notification must
     * not advance the offset. Saves must follow processing order; this repository does not
     * prevent an older offset from replacing a newer one.
     *
     * @param herId The her id whose notifications are being consumed.
     * @param offset The offset of the last fully handled notification.
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

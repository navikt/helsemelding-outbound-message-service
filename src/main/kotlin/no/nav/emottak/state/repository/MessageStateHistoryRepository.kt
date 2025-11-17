package no.nav.emottak.state.repository

import no.nav.emottak.state.model.MessageDeliveryState
import no.nav.emottak.state.model.MessageStateChange
import no.nav.emottak.state.util.UuidTransformer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

object MessageStateHistory : Table("message_state_history") {
    val id = uuid("id").transform(UuidTransformer())

    override val primaryKey = PrimaryKey(id)

    val messageId = uuid("message_id")
        .transform(UuidTransformer())
        .references(Messages.id)

    val oldState = enumerationByName("old_state", 100, MessageDeliveryState::class).nullable()
    val newState = enumerationByName("new_state", 100, MessageDeliveryState::class)
    val changedAt = timestamp("changed_at")
}

interface MessageStateHistoryRepository {
    suspend fun append(
        messageId: Uuid,
        oldState: MessageDeliveryState?,
        newState: MessageDeliveryState,
        changedAt: Instant
    ): List<MessageStateChange>

    suspend fun findAll(messageId: Uuid): List<MessageStateChange>
}

class ExposedMessageStateHistoryRepository(
    private val database: Database
) : MessageStateHistoryRepository {
    override suspend fun append(
        messageId: Uuid,
        oldState: MessageDeliveryState?,
        newState: MessageDeliveryState,
        changedAt: Instant
    ): List<MessageStateChange> {
        MessageStateHistory.insert { insert ->
            insert[MessageStateHistory.messageId] = messageId
            insert[MessageStateHistory.oldState] = oldState
            insert[MessageStateHistory.newState] = newState
            insert[MessageStateHistory.changedAt] = changedAt
        }
        return messageStateChanges(messageId)
    }

    override suspend fun findAll(messageId: Uuid): List<MessageStateChange> =
        suspendTransaction(database) {
            messageStateChanges(messageId)
        }

    private fun messageStateChanges(messageId: Uuid): List<MessageStateChange> =
        MessageStateHistory
            .selectAll()
            .where(MessageStateHistory.messageId eq messageId)
            .map { it.toMessageStateChange() }

    private fun ResultRow.toMessageStateChange(): MessageStateChange =
        MessageStateChange(
            this[MessageStateHistory.id],
            this[MessageStateHistory.messageId],
            this[MessageStateHistory.oldState],
            this[MessageStateHistory.newState],
            this[MessageStateHistory.changedAt]
        )
}

class FakeMessageStateHistoryRepository : MessageStateHistoryRepository {
    private val history = HashMap<Uuid, MutableList<MessageStateChange>>()

    override suspend fun append(
        messageId: Uuid,
        oldState: MessageDeliveryState?,
        newState: MessageDeliveryState,
        changedAt: Instant
    ): List<MessageStateChange> {
        val entry = MessageStateChange(
            id = Uuid.random(),
            messageId = messageId,
            oldState = oldState,
            newState = newState,
            changedAt = changedAt
        )

        history.computeIfAbsent(messageId) { mutableListOf() }.add(entry)
        return history[messageId]!!.toList()
    }

    override suspend fun findAll(messageId: Uuid): List<MessageStateChange> =
        history[messageId]?.toList() ?: emptyList()
}

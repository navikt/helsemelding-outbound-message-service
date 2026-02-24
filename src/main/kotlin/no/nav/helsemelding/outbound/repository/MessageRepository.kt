package no.nav.helsemelding.outbound.repository

import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.MessageType
import no.nav.helsemelding.outbound.model.isAcknowledged
import no.nav.helsemelding.outbound.model.isNull
import no.nav.helsemelding.outbound.model.isUnconfirmed
import no.nav.helsemelding.outbound.repository.Messages.appRecStatus
import no.nav.helsemelding.outbound.repository.Messages.externalDeliveryState
import no.nav.helsemelding.outbound.repository.Messages.lastPolledAt
import no.nav.helsemelding.outbound.util.UrlTransformer
import no.nav.helsemelding.outbound.util.UuidTransformer
import no.nav.helsemelding.outbound.util.olderThanSeconds
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder.ASC_NULLS_FIRST
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.net.URL
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

object Messages : Table("messages") {
    val id = uuid("id").transform(UuidTransformer())
    override val primaryKey = PrimaryKey(id)

    val externalRefId = uuid("external_reference_id")
        .transform(UuidTransformer())
        .uniqueIndex()

    val externalMessageUrl = text("external_message_url")
        .transform(UrlTransformer)
        .uniqueIndex()

    val messageType = enumerationByName("message_type", 100, MessageType::class)

    val externalDeliveryState = enumerationByName("external_delivery_state", 100, ExternalDeliveryState::class)
        .nullable()

    val appRecStatus = enumerationByName("app_rec_status", 100, AppRecStatus::class)
        .nullable()

    val lastStateChange = timestamp("last_state_change")
    val lastPolledAt = timestamp("last_polled_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

interface MessageRepository {
    suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        externalMessageUrl: URL,
        lastStateChange: Instant
    ): MessageState

    suspend fun updateState(
        externalRefId: Uuid,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?,
        lastStateChange: Instant
    ): MessageState

    suspend fun findOrNull(externalRefId: Uuid): MessageState?

    suspend fun findForPolling(): List<MessageState>

    suspend fun markPolled(externalRefIds: List<Uuid>): Int

    suspend fun countByExternalDeliveryState(): Map<ExternalDeliveryState?, Long>
}

class ExposedMessageRepository(private val database: Database) : MessageRepository {
    private val poller = config().poller

    override suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        externalMessageUrl: URL,
        lastStateChange: Instant
    ): MessageState =
        Messages.insertReturning { insert ->
            insert[Messages.id] = id
            insert[Messages.externalRefId] = externalRefId
            insert[Messages.messageType] = messageType
            insert[Messages.externalMessageUrl] = externalMessageUrl
            insert[Messages.externalDeliveryState] = null
            insert[Messages.appRecStatus] = null
            insert[Messages.lastStateChange] = lastStateChange
            insert[Messages.updatedAt] = CurrentTimestamp
        }
            .single()
            .toMessageState()

    override suspend fun updateState(
        externalRefId: Uuid,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?,
        lastStateChange: Instant
    ): MessageState =
        Messages.updateReturning(where = { Messages.externalRefId eq externalRefId }) { upsert ->
            upsert[Messages.externalDeliveryState] = externalDeliveryState
            upsert[Messages.appRecStatus] = appRecStatus
            upsert[Messages.lastStateChange] = lastStateChange
            upsert[Messages.updatedAt] = CurrentTimestamp
        }
            .single()
            .toMessageState()

    override suspend fun findOrNull(externalRefId: Uuid): MessageState? = suspendTransaction(database) {
        Messages
            .selectAll().where { Messages.externalRefId eq externalRefId }
            .singleOrNull()
            ?.toMessageState()
    }

    override suspend fun findForPolling(): List<MessageState> = suspendTransaction(database) {
        Messages
            .selectAll()
            .where {
                (
                    externalDeliveryState.isNull() or
                        externalDeliveryState.inList(listOf(ACKNOWLEDGED, UNCONFIRMED))
                    ) and
                    appRecStatus.isNull() and
                    (lastPolledAt.isNull() or lastPolledAt.olderThanSeconds(poller.minAgeSeconds))
            }
            .orderBy(lastPolledAt to ASC_NULLS_FIRST)
            .limit(poller.fetchLimit)
            .map { it.toMessageState() }
    }

    override suspend fun markPolled(externalRefIds: List<Uuid>): Int = suspendTransaction(database) {
        Messages.update({ Messages.externalRefId inList externalRefIds }) {
            it[lastPolledAt] = CurrentTimestamp
        }
    }

    override suspend fun countByExternalDeliveryState(): Map<ExternalDeliveryState?, Long> = suspendTransaction(database) {
        Messages
            .select(externalDeliveryState, Messages.id.count())
            .groupBy(externalDeliveryState)
            .associate { row ->
                val state = row[externalDeliveryState]
                val count = row[Messages.id.count()]
                state to count
            }
    }

    private fun ResultRow.toMessageState() = MessageState(
        this[Messages.id],
        this[Messages.messageType],
        this[Messages.externalRefId],
        this[Messages.externalMessageUrl],
        this[externalDeliveryState],
        this[appRecStatus],
        this[Messages.lastStateChange],
        this[lastPolledAt],
        this[Messages.createdAt],
        this[Messages.updatedAt]
    )
}

class FakeMessageRepository : MessageRepository {
    private val poller = config().poller

    private val messages = HashMap<Uuid, MessageState>()

    override suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        externalMessageUrl: URL,
        lastStateChange: Instant
    ): MessageState {
        val newMessage = MessageState(
            id = id,
            externalRefId = externalRefId,
            messageType = messageType,
            externalMessageUrl = externalMessageUrl,
            externalDeliveryState = null,
            appRecStatus = null,
            lastStateChange = lastStateChange,
            lastPolledAt = null,
            createdAt = lastStateChange,
            updatedAt = lastStateChange
        )
        messages[externalRefId] = newMessage
        return newMessage
    }

    override suspend fun updateState(
        externalRefId: Uuid,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?,
        lastStateChange: Instant
    ): MessageState {
        val existing = messages[externalRefId]!!
        val updated = existing.copy(
            externalDeliveryState = externalDeliveryState,
            appRecStatus = appRecStatus,
            lastStateChange = lastStateChange,
            updatedAt = lastStateChange
        )
        messages[externalRefId] = updated
        return updated
    }

    override suspend fun findOrNull(externalRefId: Uuid): MessageState? = messages[externalRefId]

    override suspend fun findForPolling(): List<MessageState> =
        messages.values
            .filter { message ->
                (
                    message.externalDeliveryState.isNull() ||
                        message.externalDeliveryState.isAcknowledged() ||
                        message.externalDeliveryState.isUnconfirmed()
                    ) &&
                    message.appRecStatus.isNull() &&
                    (
                        message.lastPolledAt == null ||
                            message.lastPolledAt.plus(poller.minAgeSeconds) < Clock.System.now()
                        )
            }
            .take(poller.fetchLimit)

    override suspend fun markPolled(externalRefIds: List<Uuid>): Int {
        val now = Clock.System.now()
        var count = 0

        externalRefIds.forEach { id ->
            messages[id]?.let { msg ->
                messages[id] = msg.copy(lastPolledAt = now)
                count++
            }
        }
        return count
    }

    override suspend fun countByExternalDeliveryState(): Map<ExternalDeliveryState?, Long> =
        mapOf(
            ACKNOWLEDGED to 123,
            UNCONFIRMED to 234
        )
}

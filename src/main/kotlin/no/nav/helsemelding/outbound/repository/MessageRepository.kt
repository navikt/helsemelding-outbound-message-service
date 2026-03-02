package no.nav.helsemelding.outbound.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.CreateStateResult
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
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
    ): Either<LifecycleError, CreateStateResult>

    suspend fun updateState(
        externalRefId: Uuid,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?,
        lastStateChange: Instant
    ): MessageState

    suspend fun findOrNull(externalRefId: Uuid): MessageState?

    suspend fun findForPolling(): List<MessageState>

    suspend fun markPolled(externalRefIds: List<Uuid>): Int
}

class ExposedMessageRepository(private val database: Database) : MessageRepository {
    private val poller = config().poller

    override suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        externalMessageUrl: URL,
        lastStateChange: Instant
    ): Either<LifecycleError, CreateStateResult> = either {
        findByIdOrNull(id)?.let { existing ->
            return lifecycleId(
                id,
                externalRefId,
                externalMessageUrl,
                existing
            )
        }

        Messages.insertIgnore { insert ->
            insert[Messages.id] = id
            insert[Messages.externalRefId] = externalRefId
            insert[Messages.messageType] = messageType
            insert[Messages.externalMessageUrl] = externalMessageUrl
            insert[Messages.externalDeliveryState] = null
            insert[Messages.appRecStatus] = null
            insert[Messages.lastStateChange] = lastStateChange
            insert[Messages.updatedAt] = CurrentTimestamp
        }

        val created = findByIdOrNull(id) ?: return uniquenessConflict(
            incomingId = id,
            incomingExternalRefId = externalRefId,
            incomingUrl = externalMessageUrl
        )

        CreateStateResult.Created(created)
    }

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

    private fun lifecycleId(
        incomingId: Uuid,
        incomingExternalRefId: Uuid,
        incomingUrl: URL,
        existing: MessageState
    ): Either<LifecycleError, CreateStateResult> {
        val isSameExternalRef = existing.externalRefId == incomingExternalRefId
        val isSameUrl = existing.externalMessageUrl == incomingUrl

        return when (isSameExternalRef && isSameUrl) {
            true -> CreateStateResult.Existing(existing).right()
            else -> LifecycleError.ConflictingLifecycleId(
                messageId = incomingId,
                existingExternalRefId = existing.externalRefId,
                existingExternalUrl = existing.externalMessageUrl,
                newExternalRefId = incomingExternalRefId,
                newExternalUrl = incomingUrl
            )
                .left()
        }
    }

    private suspend fun uniquenessConflict(
        incomingId: Uuid,
        incomingExternalRefId: Uuid,
        incomingUrl: URL
    ): Either<LifecycleError, CreateStateResult> {
        val existingByRef = findOrNull(incomingExternalRefId)
        if (existingByRef != null) {
            return LifecycleError.ConflictingExternalReferenceId(
                externalRefId = incomingExternalRefId,
                existingMessageId = existingByRef.id,
                newMessageId = incomingId
            )
                .left()
        }

        val existingByUrl = findByUrlOrNull(incomingUrl)
        if (existingByUrl != null) {
            return LifecycleError.ConflictingExternalMessageUrl(
                externalUrl = incomingUrl,
                existingMessageId = existingByUrl.id,
                newMessageId = incomingId
            )
                .left()
        }
        return LifecycleError.PersistenceFailure(
            messageId = incomingId,
            reason = "Insert was ignored but no existing row found by id, externalRefId or url"
        )
            .left()
    }

    private suspend fun findByIdOrNull(id: Uuid): MessageState? = suspendTransaction(database) {
        Messages
            .selectAll().where { Messages.id eq id }
            .singleOrNull()
            ?.toMessageState()
    }

    private suspend fun findByUrlOrNull(url: URL): MessageState? = suspendTransaction(database) {
        Messages
            .selectAll().where { Messages.externalMessageUrl eq url }
            .singleOrNull()
            ?.toMessageState()
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
    private val messagesById = mutableMapOf<Uuid, MessageState>()
    private val byExternalRefId = mutableMapOf<Uuid, Uuid>()

    override suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        externalMessageUrl: URL,
        lastStateChange: Instant
    ): Either<LifecycleError, CreateStateResult> {
        val existingById = messagesById[id]
        if (existingById != null) {
            return idConflict(
                incomingId = id,
                incomingExternalRefId = externalRefId,
                incomingUrl = externalMessageUrl,
                existing = existingById
            )
        }

        val existingIdForRef = byExternalRefId[externalRefId]
        if (existingIdForRef != null) {
            val existing = messagesById[existingIdForRef]!!
            return LifecycleError.ConflictingExternalReferenceId(
                externalRefId = externalRefId,
                existingMessageId = existing.id,
                newMessageId = id
            )
                .left()
        }

        val existingByUrl = messagesById.values.firstOrNull { it.externalMessageUrl == externalMessageUrl }
        if (existingByUrl != null) {
            return LifecycleError.ConflictingExternalMessageUrl(
                externalUrl = externalMessageUrl,
                existingMessageId = existingByUrl.id,
                newMessageId = id
            )
                .left()
        }

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

        messagesById[id] = newMessage
        byExternalRefId[externalRefId] = id

        return CreateStateResult.Created(newMessage).right()
    }

    override suspend fun updateState(
        externalRefId: Uuid,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?,
        lastStateChange: Instant
    ): MessageState {
        val messageId = byExternalRefId[externalRefId]!!
        val existing = messagesById[messageId]!!

        val updated = existing.copy(
            externalDeliveryState = externalDeliveryState,
            appRecStatus = appRecStatus,
            lastStateChange = lastStateChange,
            updatedAt = lastStateChange
        )
        messagesById[messageId] = updated
        return updated
    }

    override suspend fun findOrNull(externalRefId: Uuid): MessageState? {
        val messageId = byExternalRefId[externalRefId] ?: return null
        return messagesById[messageId]
    }

    override suspend fun findForPolling(): List<MessageState> =
        messagesById.values
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

        externalRefIds.forEach { externalRefId ->
            val messageId = byExternalRefId[externalRefId]
            if (messageId != null) {
                val msg = messagesById[messageId]
                if (msg != null) {
                    messagesById[messageId] = msg.copy(lastPolledAt = now)
                    count++
                }
            }
        }
        return count
    }

    private fun idConflict(
        incomingId: Uuid,
        incomingExternalRefId: Uuid,
        incomingUrl: URL,
        existing: MessageState
    ): Either<LifecycleError, CreateStateResult> =
        if (existing.externalRefId == incomingExternalRefId &&
            existing.externalMessageUrl == incomingUrl
        ) {
            CreateStateResult.Existing(existing).right()
        } else {
            LifecycleError.ConflictingLifecycleId(
                messageId = incomingId,
                existingExternalRefId = existing.externalRefId,
                existingExternalUrl = existing.externalMessageUrl,
                newExternalRefId = incomingExternalRefId,
                newExternalUrl = incomingUrl
            )
                .left()
        }
}

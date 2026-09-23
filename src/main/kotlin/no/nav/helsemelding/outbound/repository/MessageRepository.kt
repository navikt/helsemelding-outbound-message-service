package no.nav.helsemelding.outbound.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.CreateStateResult
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.MessageType
import no.nav.helsemelding.outbound.repository.Messages.appRecStatus
import no.nav.helsemelding.outbound.repository.Messages.externalDeliveryState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

object Messages : Table("messages") {
    val id = uuid("id")
    override val primaryKey = PrimaryKey(id)

    val externalRefId = uuid("external_reference_id")
        .uniqueIndex()

    val messageType = enumerationByName("message_type", 100, MessageType::class)

    val externalDeliveryState = enumerationByName("external_delivery_state", 100, ExternalDeliveryState::class)
        .nullable()

    val appRecStatus = enumerationByName("app_rec_status", 100, AppRecStatus::class)
        .nullable()

    val lastStateChange = timestamp("last_state_change")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

interface MessageRepository {
    suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        lastStateChange: Instant
    ): Either<LifecycleError, CreateStateResult>

    suspend fun updateState(
        externalRefId: Uuid,
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?,
        lastStateChange: Instant
    ): MessageState

    suspend fun findByExternalReferenceId(externalRefId: Uuid): MessageState?

    suspend fun findById(id: Uuid): MessageState?

    suspend fun countByExternalDeliveryState(): Map<ExternalDeliveryState?, Long>

    suspend fun countByAppRecState(): Map<AppRecStatus?, Long>

    suspend fun countByExternalDeliveryStateAndAppRecStatus(): Map<Pair<ExternalDeliveryState?, AppRecStatus?>, Long>
}

class ExposedMessageRepository(private val database: Database) : MessageRepository {

    override suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        lastStateChange: Instant
    ): Either<LifecycleError, CreateStateResult> = either {
        findById(id)?.let { existing ->
            return lifecycleId(
                id,
                externalRefId,
                existing
            )
        }

        Messages.insertIgnore { insert ->
            insert[Messages.id] = id
            insert[Messages.externalRefId] = externalRefId
            insert[Messages.messageType] = messageType
            insert[Messages.externalDeliveryState] = null
            insert[Messages.appRecStatus] = null
            insert[Messages.lastStateChange] = lastStateChange
            insert[Messages.updatedAt] = CurrentTimestamp
        }

        val created = findById(id) ?: return uniquenessConflict(
            incomingId = id,
            incomingExternalRefId = externalRefId
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

    override suspend fun findByExternalReferenceId(externalRefId: Uuid): MessageState? = suspendTransaction(database) {
        Messages
            .selectAll().where { Messages.externalRefId eq externalRefId }
            .singleOrNull()
            ?.toMessageState()
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

    override suspend fun countByAppRecState(): Map<AppRecStatus?, Long> = suspendTransaction(database) {
        Messages
            .select(appRecStatus, Messages.id.count())
            .groupBy(appRecStatus)
            .associate { row ->
                val state = row[appRecStatus]
                val count = row[Messages.id.count()]
                state to count
            }
    }

    override suspend fun countByExternalDeliveryStateAndAppRecStatus(): Map<Pair<ExternalDeliveryState?, AppRecStatus?>, Long> =
        suspendTransaction(database) {
            Messages
                .select(externalDeliveryState, appRecStatus, Messages.id.count())
                .groupBy(externalDeliveryState, appRecStatus)
                .associate { row ->
                    val deliveryState = row[externalDeliveryState]
                    val appRecState = row[appRecStatus]
                    Pair(deliveryState, appRecState) to row[Messages.id.count()]
                }
        }

    private fun lifecycleId(
        incomingId: Uuid,
        incomingExternalRefId: Uuid,
        existing: MessageState
    ): Either<LifecycleError, CreateStateResult> {
        val isSameExternalRef = existing.externalRefId == incomingExternalRefId

        return when (isSameExternalRef) {
            true -> CreateStateResult.Existing(existing).right()
            else -> LifecycleError.ConflictingLifecycleId(
                messageId = incomingId,
                existingExternalRefId = existing.externalRefId,
                newExternalRefId = incomingExternalRefId
            )
                .left()
        }
    }

    private suspend fun uniquenessConflict(
        incomingId: Uuid,
        incomingExternalRefId: Uuid
    ): Either<LifecycleError, CreateStateResult> {
        val existingByRef = findByExternalReferenceId(incomingExternalRefId)
        if (existingByRef != null) {
            return LifecycleError.ConflictingExternalReferenceId(
                externalRefId = incomingExternalRefId,
                existingMessageId = existingByRef.id,
                newMessageId = incomingId
            )
                .left()
        }

        return LifecycleError.PersistenceFailure(
            messageId = incomingId,
            reason = "Insert was ignored but no existing row found by id or externalRefId"
        )
            .left()
    }

    override suspend fun findById(id: Uuid): MessageState? = suspendTransaction(database) {
        Messages
            .selectAll().where { Messages.id eq id }
            .singleOrNull()
            ?.toMessageState()
    }

    private fun ResultRow.toMessageState() = MessageState(
        this[Messages.id],
        this[Messages.messageType],
        this[Messages.externalRefId],
        this[externalDeliveryState],
        this[appRecStatus],
        this[Messages.lastStateChange],
        this[Messages.createdAt],
        this[Messages.updatedAt]
    )
}

class FakeMessageRepository : MessageRepository {
    private val messagesById = mutableMapOf<Uuid, MessageState>()
    private val byExternalRefId = mutableMapOf<Uuid, Uuid>()
    private var countByExternalDeliveryState = mutableMapOf<ExternalDeliveryState?, Long>()
    private var countByAppRecStatus = mutableMapOf<AppRecStatus?, Long>()
    private var countByExternalDeliveryStateAndAppRecStatus = mutableMapOf<Pair<ExternalDeliveryState?, AppRecStatus?>, Long>()

    override suspend fun createState(
        id: Uuid,
        externalRefId: Uuid,
        messageType: MessageType,
        lastStateChange: Instant
    ): Either<LifecycleError, CreateStateResult> {
        val existingById = messagesById[id]
        if (existingById != null) {
            return idConflict(
                incomingId = id,
                incomingExternalRefId = externalRefId,
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

        val newMessage = MessageState(
            id = id,
            externalRefId = externalRefId,
            messageType = messageType,
            externalDeliveryState = null,
            appRecStatus = null,
            lastStateChange = lastStateChange,
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

    override suspend fun findByExternalReferenceId(externalRefId: Uuid): MessageState? {
        val messageId = byExternalRefId[externalRefId] ?: return null
        return messagesById[messageId]
    }

    override suspend fun findById(id: Uuid): MessageState? {
        return messagesById[id]
    }

    override suspend fun countByExternalDeliveryState(): Map<ExternalDeliveryState?, Long> = countByExternalDeliveryState

    override suspend fun countByAppRecState(): Map<AppRecStatus?, Long> = countByAppRecStatus

    override suspend fun countByExternalDeliveryStateAndAppRecStatus(): Map<Pair<ExternalDeliveryState?, AppRecStatus?>, Long> = countByExternalDeliveryStateAndAppRecStatus

    fun setCountByExternalDeliveryState(values: Map<ExternalDeliveryState?, Long>) {
        countByExternalDeliveryState = values.toMutableMap()
    }

    fun setCountByAppRecState(values: Map<AppRecStatus?, Long>) {
        countByAppRecStatus = values.toMutableMap()
    }

    fun setCountByExternalDeliveryStateAndAppRecStatus(values: Map<Pair<ExternalDeliveryState?, AppRecStatus?>, Long>) {
        countByExternalDeliveryStateAndAppRecStatus = values.toMutableMap()
    }

    private fun idConflict(
        incomingId: Uuid,
        incomingExternalRefId: Uuid,
        existing: MessageState
    ): Either<LifecycleError, CreateStateResult> =
        if (existing.externalRefId == incomingExternalRefId) {
            CreateStateResult.Existing(existing).right()
        } else {
            LifecycleError.ConflictingLifecycleId(
                messageId = incomingId,
                existingExternalRefId = existing.externalRefId,
                newExternalRefId = incomingExternalRefId
            )
                .left()
        }
}

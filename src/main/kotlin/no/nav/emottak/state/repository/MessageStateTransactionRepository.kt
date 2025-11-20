package no.nav.emottak.state.repository

import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.MessageStateSnapshot
import no.nav.emottak.state.model.MessageType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.net.URL
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface MessageStateTransactionRepository {
    suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        occurredAt: Instant
    ): MessageStateSnapshot

    suspend fun recordStateChange(
        messageType: MessageType,
        externalRefId: Uuid,
        oldDeliveryState: ExternalDeliveryState?,
        newDeliveryState: ExternalDeliveryState?,
        oldAppRecStatus: AppRecStatus?,
        newAppRecStatus: AppRecStatus?,
        occurredAt: Instant
    ): MessageStateSnapshot
}

class ExposedMessageStateTransactionRepository(
    private val database: Database,
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository
) : MessageStateTransactionRepository {

    override suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        occurredAt: Instant
    ): MessageStateSnapshot = suspendTransaction(database) {
        val messageState = messageRepository.create(
            messageType = messageType,
            externalRefId = externalRefId,
            externalMessageUrl = externalMessageUrl,
            lastStateChange = occurredAt
        )

        val historyEntries = historyRepository.append(
            messageId = externalRefId,
            oldDeliveryState = null,
            newDeliveryState = null,
            oldAppRecStatus = null,
            newAppRecStatus = null,
            changedAt = occurredAt
        )

        MessageStateSnapshot(messageState, historyEntries)
    }

    override suspend fun recordStateChange(
        messageType: MessageType,
        externalRefId: Uuid,
        oldDeliveryState: ExternalDeliveryState?,
        newDeliveryState: ExternalDeliveryState?,
        oldAppRecStatus: AppRecStatus?,
        newAppRecStatus: AppRecStatus?,
        occurredAt: Instant
    ): MessageStateSnapshot = suspendTransaction(database) {
        val updatedState = messageRepository.updateState(
            externalRefId = externalRefId,
            externalDeliveryState = newDeliveryState,
            appRecStatus = newAppRecStatus,
            lastStateChange = occurredAt
        )

        val historyEntries = historyRepository.append(
            messageId = externalRefId,
            oldDeliveryState = oldDeliveryState,
            newDeliveryState = newDeliveryState,
            oldAppRecStatus = oldAppRecStatus,
            newAppRecStatus = newAppRecStatus,
            changedAt = occurredAt
        )

        MessageStateSnapshot(updatedState, historyEntries)
    }
}

class FakeMessageStateTransactionRepository(
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository
) : MessageStateTransactionRepository {

    override suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        occurredAt: Instant
    ): MessageStateSnapshot {
        val state = messageRepository.create(
            messageType = messageType,
            externalRefId = externalRefId,
            externalMessageUrl = externalMessageUrl,
            lastStateChange = occurredAt
        )

        val history = historyRepository.append(
            messageId = externalRefId,
            oldDeliveryState = null,
            newDeliveryState = null,
            oldAppRecStatus = null,
            newAppRecStatus = null,
            changedAt = occurredAt
        )

        return MessageStateSnapshot(state, history)
    }

    override suspend fun recordStateChange(
        messageType: MessageType,
        externalRefId: Uuid,
        oldDeliveryState: ExternalDeliveryState?,
        newDeliveryState: ExternalDeliveryState?,
        oldAppRecStatus: AppRecStatus?,
        newAppRecStatus: AppRecStatus?,
        occurredAt: Instant
    ): MessageStateSnapshot {
        val state = messageRepository.updateState(
            externalRefId = externalRefId,
            externalDeliveryState = newDeliveryState,
            appRecStatus = newAppRecStatus,
            lastStateChange = occurredAt
        )

        val history = historyRepository.append(
            messageId = externalRefId,
            oldDeliveryState = oldDeliveryState,
            newDeliveryState = newDeliveryState,
            oldAppRecStatus = oldAppRecStatus,
            newAppRecStatus = newAppRecStatus,
            changedAt = occurredAt
        )

        return MessageStateSnapshot(state, history)
    }
}

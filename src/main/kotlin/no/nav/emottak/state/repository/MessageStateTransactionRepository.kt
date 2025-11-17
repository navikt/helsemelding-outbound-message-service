package no.nav.emottak.state.repository

import no.nav.emottak.state.model.MessageDeliveryState
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
        initialState: MessageDeliveryState,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        occurredAt: Instant
    ): MessageStateSnapshot

    suspend fun recordStateChange(
        messageType: MessageType,
        oldState: MessageDeliveryState,
        newState: MessageDeliveryState,
        externalRefId: Uuid,
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
        initialState: MessageDeliveryState,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        occurredAt: Instant
    ): MessageStateSnapshot = suspendTransaction(database) {
        val state = messageRepository.createState(
            messageType = messageType,
            state = initialState,
            externalRefId = externalRefId,
            externalMessageUrl = externalMessageUrl,
            lastStateChange = occurredAt
        )

        val history = historyRepository.append(
            messageId = externalRefId,
            oldState = null,
            newState = initialState,
            changedAt = occurredAt
        )

        MessageStateSnapshot(state, history)
    }

    override suspend fun recordStateChange(
        messageType: MessageType,
        oldState: MessageDeliveryState,
        newState: MessageDeliveryState,
        externalRefId: Uuid,
        occurredAt: Instant
    ): MessageStateSnapshot = suspendTransaction(database) {
        val messageState = messageRepository.updateState(
            messageType = messageType,
            state = newState,
            externalRefId = externalRefId,
            lastStateChange = occurredAt
        )

        val historyEntries = historyRepository.append(
            messageId = externalRefId,
            oldState = oldState,
            newState = newState,
            changedAt = occurredAt
        )

        MessageStateSnapshot(messageState, historyEntries)
    }
}

class FakeMessageStateTransactionRepository(
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository
) : MessageStateTransactionRepository {
    override suspend fun createInitialState(
        messageType: MessageType,
        initialState: MessageDeliveryState,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        occurredAt: Instant
    ): MessageStateSnapshot {
        val state = messageRepository.createState(
            messageType = messageType,
            state = initialState,
            externalRefId = externalRefId,
            externalMessageUrl = externalMessageUrl,
            lastStateChange = occurredAt
        )

        val history = historyRepository.append(
            messageId = externalRefId,
            oldState = null,
            newState = initialState,
            changedAt = occurredAt
        )

        return MessageStateSnapshot(state, history)
    }

    override suspend fun recordStateChange(
        messageType: MessageType,
        oldState: MessageDeliveryState,
        newState: MessageDeliveryState,
        externalRefId: Uuid,
        occurredAt: Instant
    ): MessageStateSnapshot {
        val messageState = messageRepository.updateState(
            messageType = messageType,
            state = newState,
            externalRefId = externalRefId,
            lastStateChange = occurredAt
        )

        val historyEntries = historyRepository.append(
            messageId = externalRefId,
            oldState = oldState,
            newState = newState,
            changedAt = occurredAt
        )

        return MessageStateSnapshot(messageState, historyEntries)
    }
}

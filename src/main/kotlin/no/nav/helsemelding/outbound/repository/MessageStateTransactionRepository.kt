package no.nav.helsemelding.outbound.repository

import arrow.core.Either
import arrow.core.raise.either
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.UpdateState
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

interface MessageStateTransactionRepository {
    suspend fun createInitialState(createState: CreateState): Either<LifecycleError, MessageStateSnapshot>

    suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot
}

class ExposedMessageStateTransactionRepository(
    private val database: Database,
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository
) : MessageStateTransactionRepository {

    override suspend fun createInitialState(
        createState: CreateState
    ): Either<LifecycleError, MessageStateSnapshot> = suspendTransaction(database) {
        either {
            val messageState = messageRepository.createState(
                id = createState.id,
                externalRefId = createState.externalRefId,
                messageType = createState.messageType,
                externalMessageUrl = createState.externalMessageUrl,
                lastStateChange = createState.occurredAt
            )
                .bind()

            val historyEntries = historyRepository.append(
                messageId = createState.externalRefId,
                oldDeliveryState = null,
                newDeliveryState = null,
                oldAppRecStatus = null,
                newAppRecStatus = null,
                changedAt = createState.occurredAt
            )

            MessageStateSnapshot(messageState, historyEntries)
        }
    }

    override suspend fun recordStateChange(
        updateState: UpdateState
    ): MessageStateSnapshot = suspendTransaction(database) {
        val updatedState = messageRepository.updateState(
            externalRefId = updateState.externalRefId,
            externalDeliveryState = updateState.newDeliveryState,
            appRecStatus = updateState.newAppRecStatus,
            lastStateChange = updateState.occurredAt
        )

        val historyEntries = historyRepository.append(
            messageId = updateState.externalRefId,
            oldDeliveryState = updateState.oldDeliveryState,
            newDeliveryState = updateState.newDeliveryState,
            oldAppRecStatus = updateState.oldAppRecStatus,
            newAppRecStatus = updateState.newAppRecStatus,
            changedAt = updateState.occurredAt
        )

        MessageStateSnapshot(updatedState, historyEntries)
    }
}

class FakeMessageStateTransactionRepository(
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository
) : MessageStateTransactionRepository {

    override suspend fun createInitialState(
        createState: CreateState
    ): Either<LifecycleError, MessageStateSnapshot> = either {
        val messageState = messageRepository.createState(
            id = createState.id,
            externalRefId = createState.externalRefId,
            messageType = createState.messageType,
            externalMessageUrl = createState.externalMessageUrl,
            lastStateChange = createState.occurredAt
        )
            .bind()

        val historyEntries = historyRepository.append(
            messageId = createState.externalRefId,
            oldDeliveryState = null,
            newDeliveryState = null,
            oldAppRecStatus = null,
            newAppRecStatus = null,
            changedAt = createState.occurredAt
        )

        MessageStateSnapshot(messageState, historyEntries)
    }

    override suspend fun recordStateChange(
        updateState: UpdateState
    ): MessageStateSnapshot {
        val updatedState = messageRepository.updateState(
            externalRefId = updateState.externalRefId,
            externalDeliveryState = updateState.newDeliveryState,
            appRecStatus = updateState.newAppRecStatus,
            lastStateChange = updateState.occurredAt
        )

        val historyEntries = historyRepository.append(
            messageId = updateState.externalRefId,
            oldDeliveryState = updateState.oldDeliveryState,
            newDeliveryState = updateState.newDeliveryState,
            oldAppRecStatus = updateState.oldAppRecStatus,
            newAppRecStatus = updateState.newAppRecStatus,
            changedAt = updateState.occurredAt
        )

        return MessageStateSnapshot(updatedState, historyEntries)
    }
}

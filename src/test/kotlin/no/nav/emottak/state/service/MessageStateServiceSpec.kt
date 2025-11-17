package no.nav.emottak.state.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.emottak.state.model.MessageDeliveryState.COMPLETED
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.repository.FakeMessageRepository
import no.nav.emottak.state.repository.FakeMessageStateHistoryRepository
import no.nav.emottak.state.repository.FakeMessageStateTransactionRepository
import java.net.URI
import kotlin.uuid.Uuid

private const val MESSAGE1 = "http://exmaple.com/messages/1"
private const val MESSAGE2 = "http://exmaple.com/messages/2"

class MessageStateServiceSpec : StringSpec(
    {
        "Create initial state - creates state and one history entry" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            val snapshot = messageStateService.createInitialState(
                messageType = DIALOG,
                externalRefId = externalRefId,
                externalMessageUrl = externalMessageUrl,
                initialState = NEW
            )

            snapshot.messageState.externalRefId shouldBe externalRefId
            snapshot.messageState.externalMessageUrl shouldBe externalMessageUrl
            snapshot.messageState.currentState shouldBe NEW
            snapshot.messageStateChange.size shouldBe 1
            snapshot.messageStateChange.first().newState shouldBe NEW
            snapshot.messageStateChange.first().oldState shouldBe null
        }

        "Record state change - updates current state and appends history" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            messageStateService.createInitialState(
                DIALOG,
                externalRefId,
                externalMessageUrl,
                NEW
            )
            val updated = messageStateService.recordStateChange(
                DIALOG,
                NEW,
                COMPLETED,
                externalRefId
            )

            updated.messageState.currentState shouldBe COMPLETED
            updated.messageStateChange.size shouldBe 2
            updated.messageStateChange.last().newState shouldBe COMPLETED
        }

        "Get message snapshot  - returns null when missing" {
            val messageStateService = transactionalMessageStateService()
            messageStateService.getMessageSnapshot(Uuid.random()).shouldBeNull()
        }

        "Find pollable messages - returns only NEW messages" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId1 = Uuid.random()
            val externalMessageUrl1 = URI(MESSAGE1).toURL()
            val externalRefId2 = Uuid.random()
            val externalMessageUrl2 = URI(MESSAGE2).toURL()

            messageStateService.createInitialState(
                DIALOG,
                externalRefId1,
                externalMessageUrl1,
                NEW
            )
            messageStateService.createInitialState(
                DIALOG,
                externalRefId2,
                externalMessageUrl2,
                COMPLETED
            )

            val result = messageStateService.findPollableMessages()

            result.size shouldBe 1
            result.first().externalRefId shouldBe externalRefId1
        }

        "Mark as polled - updates last polled at for given messages" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId1 = Uuid.random()
            val externalMessageUrl1 = URI(MESSAGE1).toURL()
            val externalRefId2 = Uuid.random()
            val externalMessageUrl2 = URI(MESSAGE2).toURL()

            // Create two states
            messageStateService.createInitialState(
                DIALOG,
                externalRefId1,
                externalMessageUrl1,
                NEW
            )
            messageStateService.createInitialState(
                DIALOG,
                externalRefId2,
                externalMessageUrl2,
                NEW
            )

            messageStateService
                .getMessageSnapshot(externalRefId1)!!
                .messageState
                .lastPolledAt
                .shouldBeNull()

            messageStateService
                .getMessageSnapshot(externalRefId2)!!
                .messageState
                .lastPolledAt
                .shouldBeNull()

            val updatedCount = messageStateService.markAsPolled(listOf(externalRefId1))

            updatedCount shouldBe 1

            val msg1 = messageStateService.getMessageSnapshot(externalRefId1)!!.messageState
            val msg2 = messageStateService.getMessageSnapshot(externalRefId2)!!.messageState

            msg1.lastPolledAt shouldNotBe null
            msg2.lastPolledAt shouldBe null
        }
    }
)

private fun transactionalMessageStateService(): TransactionalMessageStateService {
    val messageRepository = FakeMessageRepository()
    val historyRepository = FakeMessageStateHistoryRepository()
    val txRepository = FakeMessageStateTransactionRepository(
        messageRepository,
        historyRepository
    )

    return TransactionalMessageStateService(
        messageRepository,
        historyRepository,
        txRepository
    )
}

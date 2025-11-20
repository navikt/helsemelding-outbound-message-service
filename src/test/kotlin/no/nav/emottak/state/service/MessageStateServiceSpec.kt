package no.nav.emottak.state.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.emottak.state.model.CreateState
import no.nav.emottak.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.model.UpdateState
import no.nav.emottak.state.repository.FakeMessageRepository
import no.nav.emottak.state.repository.FakeMessageStateHistoryRepository
import no.nav.emottak.state.repository.FakeMessageStateTransactionRepository
import java.net.URI
import kotlin.uuid.Uuid

private const val MESSAGE1 = "http://exmaple.com/messages/1"
private const val MESSAGE2 = "http://exmaple.com/messages/2"

class MessageStateServiceSpec : StringSpec(
    {

        "Create initial state – creates message with null external states and one baseline history entry" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    messageType = DIALOG,
                    externalRefId = externalRefId,
                    externalMessageUrl = externalMessageUrl
                )
            )

            val messageState = snapshot.messageState

            messageState.externalRefId shouldBe externalRefId
            messageState.externalMessageUrl shouldBe externalMessageUrl

            messageState.externalDeliveryState shouldBe null
            messageState.appRecStatus shouldBe null

            snapshot.messageStateChange.size shouldBe 1
            val history = snapshot.messageStateChange.first()

            history.oldDeliveryState shouldBe null
            history.newDeliveryState shouldBe null
            history.oldAppRecStatus shouldBe null
            history.newAppRecStatus shouldBe null
        }

        "Record state change – updates external state and appends history" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            messageStateService.createInitialState(
                CreateState(
                    messageType = DIALOG,
                    externalRefId = externalRefId,
                    externalMessageUrl = externalMessageUrl
                )
            )

            val updated = messageStateService.recordStateChange(
                UpdateState(
                    messageType = DIALOG,
                    externalRefId = externalRefId,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null
                )
            )

            updated.messageState.externalDeliveryState shouldBe ACKNOWLEDGED

            updated.messageStateChange.size shouldBe 2
            val last = updated.messageStateChange.last()

            last.oldDeliveryState shouldBe null
            last.newDeliveryState shouldBe ACKNOWLEDGED
            last.oldAppRecStatus shouldBe null
            last.newAppRecStatus shouldBe null
        }

        "Get message snapshot – returns null when missing" {
            val messageStateService = transactionalMessageStateService()

            messageStateService.getMessageSnapshot(Uuid.random()).shouldBeNull()
        }

        "Find pollable messages – returns only messages with externalDeliveryState == null" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId1 = Uuid.random()
            val externalMessageUrl1 = URI(MESSAGE1).toURL()

            val externalRefId2 = Uuid.random()
            val externalMessageUrl2 = URI(MESSAGE2).toURL()

            messageStateService.createInitialState(
                CreateState(
                    messageType = DIALOG,
                    externalRefId = externalRefId1,
                    externalMessageUrl = externalMessageUrl1
                )
            )

            messageStateService.createInitialState(
                CreateState(
                    messageType = DIALOG,
                    externalRefId = externalRefId2,
                    externalMessageUrl = externalMessageUrl2
                )
            )

            messageStateService.recordStateChange(
                UpdateState(
                    messageType = DIALOG,
                    externalRefId = externalRefId2,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null
                )
            )

            val result = messageStateService.findPollableMessages()

            result.size shouldBe 1
            result.first().externalRefId shouldBe externalRefId1
        }

        "Mark as polled – updates lastPolledAt only for selected messages" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId1 = Uuid.random()
            val externalMessageUrl1 = URI(MESSAGE1).toURL()
            val externalRefId2 = Uuid.random()
            val externalMessageUrl2 = URI(MESSAGE2).toURL()

            messageStateService.createInitialState(
                CreateState(
                    DIALOG,
                    externalRefId1,
                    externalMessageUrl1
                )
            )
            messageStateService.createInitialState(
                CreateState(
                    DIALOG,
                    externalRefId2,
                    externalMessageUrl2
                )
            )

            messageStateService.getMessageSnapshot(externalRefId1)!!.messageState.lastPolledAt.shouldBeNull()
            messageStateService.getMessageSnapshot(externalRefId2)!!.messageState.lastPolledAt.shouldBeNull()

            val updated = messageStateService.markAsPolled(listOf(externalRefId1))
            updated shouldBe 1

            messageStateService.getMessageSnapshot(externalRefId1)!!.messageState.lastPolledAt shouldNotBe null
            messageStateService.getMessageSnapshot(externalRefId2)!!.messageState.lastPolledAt shouldBe null
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

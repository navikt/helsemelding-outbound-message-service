package no.nav.emottak.state.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.emottak.state.model.ExternalDeliveryState.Acknowledged
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

        "Create initial state – creates message with null external states and one baseline history entry" {
            val messageStateService = transactionalMessageStateService()

            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            val snapshot = messageStateService.createInitialState(
                messageType = DIALOG,
                externalRefId = externalRefId,
                externalMessageUrl = externalMessageUrl
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

            val refId = Uuid.random()
            val url = URI(MESSAGE1).toURL()

            messageStateService.createInitialState(
                messageType = DIALOG,
                externalRefId = refId,
                externalMessageUrl = url
            )

            val updated = messageStateService.recordStateChange(
                messageType = DIALOG,
                externalRefId = refId,
                oldDeliveryState = null,
                newDeliveryState = Acknowledged,
                oldAppRecStatus = null,
                newAppRecStatus = null
            )

            updated.messageState.externalDeliveryState shouldBe Acknowledged

            updated.messageStateChange.size shouldBe 2
            val last = updated.messageStateChange.last()

            last.oldDeliveryState shouldBe null
            last.newDeliveryState shouldBe Acknowledged
            last.oldAppRecStatus shouldBe null
            last.newAppRecStatus shouldBe null
        }

        "Get message snapshot – returns null when missing" {
            val messageStateService = transactionalMessageStateService()

            messageStateService.getMessageSnapshot(Uuid.random()).shouldBeNull()
        }

        "Find pollable messages – returns only messages with externalDeliveryState == null" {
            val messageStateService = transactionalMessageStateService()

            val ref1 = Uuid.random()
            val url1 = URI(MESSAGE1).toURL()

            val ref2 = Uuid.random()
            val url2 = URI(MESSAGE2).toURL()

            messageStateService.createInitialState(
                messageType = DIALOG,
                externalRefId = ref1,
                externalMessageUrl = url1
            )

            messageStateService.createInitialState(
                messageType = DIALOG,
                externalRefId = ref2,
                externalMessageUrl = url2
            )
            messageStateService.recordStateChange(
                messageType = DIALOG,
                externalRefId = ref2,
                oldDeliveryState = null,
                newDeliveryState = Acknowledged,
                oldAppRecStatus = null,
                newAppRecStatus = null
            )

            val result = messageStateService.findPollableMessages()

            result.size shouldBe 1
            result.first().externalRefId shouldBe ref1
        }

        "Mark as polled – updates lastPolledAt only for selected messages" {
            val messageStateService = transactionalMessageStateService()

            val ref1 = Uuid.random()
            val url1 = URI(MESSAGE1).toURL()
            val ref2 = Uuid.random()
            val url2 = URI(MESSAGE2).toURL()

            messageStateService.createInitialState(DIALOG, ref1, url1)
            messageStateService.createInitialState(DIALOG, ref2, url2)

            messageStateService.getMessageSnapshot(ref1)!!.messageState.lastPolledAt.shouldBeNull()
            messageStateService.getMessageSnapshot(ref2)!!.messageState.lastPolledAt.shouldBeNull()

            val updated = messageStateService.markAsPolled(listOf(ref1))
            updated shouldBe 1

            messageStateService.getMessageSnapshot(ref1)!!.messageState.lastPolledAt shouldNotBe null
            messageStateService.getMessageSnapshot(ref2)!!.messageState.lastPolledAt shouldBe null
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

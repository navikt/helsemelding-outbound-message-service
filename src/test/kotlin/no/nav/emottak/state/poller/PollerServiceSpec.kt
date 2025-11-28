package no.nav.emottak.state.poller

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.emottak.state.adapter.EdiAdapterClient
import no.nav.emottak.state.adapter.FakeEdiAdapterClient
import no.nav.emottak.state.evaluator.StateEvaluator
import no.nav.emottak.state.evaluator.StateTransitionValidator
import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.AppRecStatus.REJECTED
import no.nav.emottak.state.model.CreateState
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.emottak.state.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.publisher.FakeDialogMessagePublisher
import no.nav.emottak.state.publisher.MessagePublisher
import no.nav.emottak.state.repository.FakeMessageRepository
import no.nav.emottak.state.repository.FakeMessageStateHistoryRepository
import no.nav.emottak.state.repository.FakeMessageStateTransactionRepository
import no.nav.emottak.state.service.MessageStateService
import no.nav.emottak.state.service.PollerService
import no.nav.emottak.state.service.StateEvaluatorService
import no.nav.emottak.state.service.TransactionalMessageStateService
import java.net.URI
import kotlin.uuid.Uuid

class PollerServiceSpec : StringSpec(
    {
        "No state change → no publish" {
            val ediAdapterClient = FakeEdiAdapterClient()
            val messageStateService = messageStateService()
            val dialogMessagePublisher = FakeDialogMessagePublisher()
            val pollerService = pollerService(
                ediAdapterClient,
                messageStateService,
                dialogMessagePublisher
            )

            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    DIALOG,
                    externalRefId,
                    externalUrl
                )
            )
            ediAdapterClient.setStatus(externalRefId, ACKNOWLEDGED, null)

            pollerService.pollAndProcessMessage(messageSnapshot.messageState)

            val currentMessageSnapshot = messageStateService.getMessageSnapshot(externalRefId)!!
            currentMessageSnapshot.messageState.externalDeliveryState shouldBe ACKNOWLEDGED
            currentMessageSnapshot.messageState.appRecStatus shouldBe null

            dialogMessagePublisher.published shouldBe emptyList()
        }

        "PENDING → COMPLETED publishes update" {
            val ediAdapterClient = FakeEdiAdapterClient()
            val messageStateService = messageStateService()
            val dialogMessagePublisher = FakeDialogMessagePublisher()
            val pollerService = pollerService(
                ediAdapterClient,
                messageStateService,
                dialogMessagePublisher
            )

            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    DIALOG,
                    externalRefId,
                    externalUrl
                )
            )

            ediAdapterClient.setStatus(externalRefId, ACKNOWLEDGED, null)

            val messageState = messageSnapshot.messageState
            pollerService.pollAndProcessMessage(messageState)

            ediAdapterClient.setStatus(externalRefId, ACKNOWLEDGED, AppRecStatus.OK)

            pollerService.pollAndProcessMessage(messageState)

            dialogMessagePublisher.published.size shouldBe 1
            dialogMessagePublisher.published.first().referenceId shouldBe externalRefId
            dialogMessagePublisher.published.first().appRecStatus shouldBe AppRecStatus.OK
        }

        "External REJECTED → publish rejection" {
            val ediAdapterClient = FakeEdiAdapterClient()
            val messageStateService = messageStateService()
            val dialogMessagePublisher = FakeDialogMessagePublisher()
            val pollerService = pollerService(
                ediAdapterClient,
                messageStateService,
                dialogMessagePublisher
            )

            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    DIALOG,
                    externalRefId,
                    externalUrl
                )
            )
            ediAdapterClient.setStatus(externalRefId, ExternalDeliveryState.REJECTED, null)

            pollerService.pollAndProcessMessage(messageSnapshot.messageState)

            dialogMessagePublisher.published.size shouldBe 1
            dialogMessagePublisher.published.first().referenceId shouldBe externalRefId
            dialogMessagePublisher.published.first().appRecStatus shouldBe REJECTED
        }

        "Unresolvable external state → INVALID but not published" {
            val ediAdapterClient = FakeEdiAdapterClient()
            val messageStateService = messageStateService()
            val dialogMessagePublisher = FakeDialogMessagePublisher()
            val pollerService = pollerService(
                ediAdapterClient,
                messageStateService,
                dialogMessagePublisher
            )

            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    DIALOG,
                    externalRefId,
                    externalUrl
                )
            )
            ediAdapterClient.setStatus(externalRefId, UNCONFIRMED, REJECTED)
            val messageState = messageSnapshot.messageState

            pollerService.pollAndProcessMessage(messageState)

            val currentMessageSnapshot = messageStateService.getMessageSnapshot(externalRefId)!!
            currentMessageSnapshot.messageState.externalDeliveryState shouldBe null
            currentMessageSnapshot.messageState.appRecStatus shouldBe null

            dialogMessagePublisher.published shouldBe emptyList()
        }
    }
)

private fun pollerService(
    ediAdapterClient: EdiAdapterClient,
    messageStateService: MessageStateService,
    messagePublisher: MessagePublisher
): PollerService = PollerService(
    ediAdapterClient,
    messageStateService,
    stateEvaluatorService(),
    messagePublisher
)

private fun stateEvaluatorService(): StateEvaluatorService = StateEvaluatorService(
    StateEvaluator(),
    StateTransitionValidator()
)

private fun messageStateService(): TransactionalMessageStateService {
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

package no.nav.helsemelding.state.poller

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.DeliveryState
import no.nav.helsemelding.ediadapter.model.DeliveryState.UNCONFIRMED
import no.nav.helsemelding.state.FakeEdiAdapterClient
import no.nav.helsemelding.state.evaluator.StateEvaluator
import no.nav.helsemelding.state.evaluator.StateTransitionValidator
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.AppRecStatus.REJECTED
import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.publisher.FakeDialogMessagePublisher
import no.nav.helsemelding.state.publisher.MessagePublisher
import no.nav.helsemelding.state.service.FakeTransactionalMessageStateService
import no.nav.helsemelding.state.service.MessageStateService
import no.nav.helsemelding.state.service.PollerService
import no.nav.helsemelding.state.service.StateEvaluatorService
import java.net.URI
import kotlin.uuid.Uuid
import no.nav.helsemelding.ediadapter.model.AppRecStatus as ExternalAppRecStatus

class PollerServiceSpec : StringSpec(
    {
        "Mark polled messages → not pollable on next run" {
            val (ediAdapterClient, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            val id1 = Uuid.random()
            val id2 = Uuid.random()
            val externalRefId1 = Uuid.random()
            val externalRefId2 = Uuid.random()
            val url = URI("http://example.com/1").toURL()

            messageStateService.createInitialState(
                CreateState(
                    id1,
                    externalRefId1,
                    DIALOG,
                    url
                )
            )

            messageStateService.createInitialState(
                CreateState(
                    id2,
                    externalRefId2,
                    DIALOG,
                    url
                )
            )

            ediAdapterClient.givenStatus(externalRefId1, DeliveryState.ACKNOWLEDGED, null)
            ediAdapterClient.givenStatus(externalRefId2, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollMessages()
            pollerService.pollMessages()

            dialogMessagePublisher.published shouldBe emptyList()
            messageStateService.findPollableMessages() shouldBe emptyList()
        }

        "No pollable messages → do nothing" {
            val (_, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            pollerService.pollMessages()

            messageStateService.findPollableMessages() shouldBe emptyList()

            dialogMessagePublisher.published shouldBe emptyList()
        }

        "No status list → no state change and no publish" {
            val (ediAdapterClient, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            )

            ediAdapterClient.givenStatusList(externalRefId, emptyList())

            pollerService.pollAndProcessMessage(snapshot.messageState)

            val current = messageStateService.getMessageSnapshot(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe null
            current.messageState.appRecStatus shouldBe null
            dialogMessagePublisher.published shouldBe emptyList()
        }

        "No state change → no publish" {
            val (ediAdapterClient, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            )
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollAndProcessMessage(messageSnapshot.messageState)

            val currentMessageSnapshot = messageStateService.getMessageSnapshot(externalRefId)!!
            currentMessageSnapshot.messageState.externalDeliveryState shouldBe ACKNOWLEDGED
            currentMessageSnapshot.messageState.appRecStatus shouldBe null

            dialogMessagePublisher.published shouldBe emptyList()
        }

        "PENDING → COMPLETED publishes update" {
            val (ediAdapterClient, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            )

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, null)

            val messageState = messageSnapshot.messageState
            pollerService.pollAndProcessMessage(messageState)

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, ExternalAppRecStatus.OK)

            pollerService.pollAndProcessMessage(messageState)

            dialogMessagePublisher.published.size shouldBe 1
            dialogMessagePublisher.published.first().referenceId shouldBe externalRefId
            dialogMessagePublisher.published.first().appRecStatus shouldBe OK
        }

        "External REJECTED → publish rejection" {
            val (ediAdapterClient, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            )
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.REJECTED, null)

            pollerService.pollAndProcessMessage(messageSnapshot.messageState)

            dialogMessagePublisher.published.size shouldBe 1
            dialogMessagePublisher.published.first().referenceId shouldBe externalRefId
            dialogMessagePublisher.published.first().appRecStatus shouldBe REJECTED
        }

        "Unresolvable external state → INVALID but not published" {
            val (ediAdapterClient, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            val messageSnapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            )

            ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, ExternalAppRecStatus.REJECTED)

            val messageState = messageSnapshot.messageState

            pollerService.pollAndProcessMessage(messageState)

            val currentMessageSnapshot = messageStateService.getMessageSnapshot(externalRefId)!!
            currentMessageSnapshot.messageState.externalDeliveryState shouldBe null
            currentMessageSnapshot.messageState.appRecStatus shouldBe null

            dialogMessagePublisher.published shouldBe emptyList()
        }
    }
)

private data class Fixture(
    val ediAdapterClient: FakeEdiAdapterClient,
    val messageStateService: FakeTransactionalMessageStateService,
    val dialogMessagePublisher: FakeDialogMessagePublisher,
    val pollerService: PollerService
)

private fun fixture(): Fixture {
    val ediAdapterClient = FakeEdiAdapterClient()
    val messageStateService = FakeTransactionalMessageStateService()
    val dialogMessagePublisher = FakeDialogMessagePublisher()

    return Fixture(
        ediAdapterClient,
        messageStateService,
        dialogMessagePublisher,
        pollerService(
            ediAdapterClient,
            messageStateService,
            dialogMessagePublisher
        )
    )
}

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

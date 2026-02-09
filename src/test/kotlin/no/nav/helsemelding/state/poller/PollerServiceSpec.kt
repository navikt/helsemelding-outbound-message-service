package no.nav.helsemelding.state.poller

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.AppRecStatus.OK
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import no.nav.helsemelding.ediadapter.model.DeliveryState
import no.nav.helsemelding.ediadapter.model.DeliveryState.UNCONFIRMED
import no.nav.helsemelding.state.FakeEdiAdapterClient
import no.nav.helsemelding.state.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.state.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.state.evaluator.TransportStatusTranslator
import no.nav.helsemelding.state.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.state.model.ApprecStatusMessage
import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.model.TransportStatusMessage
import no.nav.helsemelding.state.model.UpdateState
import no.nav.helsemelding.state.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.state.publisher.MessagePublisher
import no.nav.helsemelding.state.service.FakeTransactionalMessageStateService
import no.nav.helsemelding.state.service.MessageStateService
import no.nav.helsemelding.state.service.PollerService
import no.nav.helsemelding.state.service.StateEvaluatorService
import java.net.URI
import kotlin.time.Clock
import kotlin.uuid.Uuid
import no.nav.helsemelding.ediadapter.model.AppRecStatus as ExternalAppRecStatus

class PollerServiceSpec : StringSpec(
    {
        "Mark polled messages → not pollable on next run" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

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

            statusMessagePublisher.published shouldBe emptyList()
            messageStateService.findPollableMessages() shouldBe emptyList()
        }

        "No pollable messages → do nothing" {
            val (_, messageStateService, dialogMessagePublisher, pollerService) = fixture()

            pollerService.pollMessages()

            messageStateService.findPollableMessages() shouldBe emptyList()

            dialogMessagePublisher.published shouldBe emptyList()
        }

        "No status list → no state change and no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

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
            statusMessagePublisher.published shouldBe emptyList()
        }

        "No state change → no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

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

            statusMessagePublisher.published shouldBe emptyList()
        }

        "PENDING → COMPLETED publish update" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

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

            val updatedMessageSnapshot = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId,
                    DIALOG,
                    ACKNOWLEDGED,
                    ACKNOWLEDGED,
                    null,
                    null,
                    Clock.System.now()
                )
            )

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            ediAdapterClient.givenApprecInfoSingle(externalRefId, ApprecInfo(1, OK))

            val updatedMessageState = updatedMessageSnapshot.messageState
            pollerService.pollAndProcessMessage(updatedMessageState)

            statusMessagePublisher.published.size shouldBe 1

            val bytes = statusMessagePublisher.published.single()
            val apprectStatus = Json.decodeFromString<ApprecStatusMessage>(String(bytes))

            // apprectStatus.messageId shouldBe id
            // apprectStatus.source shouldBe "apprec"
            // apprectStatus.apprec.appRecStatus shouldBe OK
        }

        "External REJECTED → publish rejection" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

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

            statusMessagePublisher.published.size shouldBe 1

            val bytes = statusMessagePublisher.published.single()
            val transportStatus = Json.decodeFromString<TransportStatusMessage>(String(bytes))

            transportStatus.messageId shouldBe id
            transportStatus.source shouldBe "transport"
            transportStatus.error.code shouldBe "REJECTED"
        }

        "Unresolvable external state → INVALID but not published" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

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

            statusMessagePublisher.published shouldBe emptyList()
        }
    }
)

private data class Fixture(
    val ediAdapterClient: FakeEdiAdapterClient,
    val messageStateService: FakeTransactionalMessageStateService,
    val dialogMessagePublisher: FakeStatusMessagePublisher,
    val pollerService: PollerService
)

private fun fixture(): Fixture {
    val ediAdapterClient = FakeEdiAdapterClient()
    val messageStateService = FakeTransactionalMessageStateService()
    val dialogMessagePublisher = FakeStatusMessagePublisher()

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
    TransportStatusTranslator(),
    StateTransitionEvaluator(
        TransportTransitionEvaluator(),
        AppRecTransitionEvaluator()
    )
)

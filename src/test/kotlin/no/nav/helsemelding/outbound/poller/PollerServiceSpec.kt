package no.nav.helsemelding.outbound.poller

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.AppRecStatus.OK
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import no.nav.helsemelding.ediadapter.model.DeliveryState
import no.nav.helsemelding.ediadapter.model.DeliveryState.UNCONFIRMED
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.outbound.model.AppRecStatus.REJECTED
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatus.PENDING_APPREC
import no.nav.helsemelding.outbound.model.MessageStatus.PENDING_TRANSPORT
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.outbound.publisher.MessagePublisher
import no.nav.helsemelding.outbound.service.FakeTransactionalMessageStateService
import no.nav.helsemelding.outbound.service.MessageStateService
import no.nav.helsemelding.outbound.service.PollerService
import no.nav.helsemelding.outbound.service.StateEvaluatorService
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
            val url1 = URI("http://example.com/1").toURL()
            val url2 = URI("http://example.com/2").toURL()

            messageStateService.createInitialState(
                CreateState(
                    id1,
                    externalRefId1,
                    DIALOG,
                    url1
                )
            ).shouldBeRight()

            messageStateService.createInitialState(
                CreateState(
                    id2,
                    externalRefId2,
                    DIALOG,
                    url2
                )
            ).shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId1, DeliveryState.ACKNOWLEDGED, null)
            ediAdapterClient.givenStatus(externalRefId2, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollMessages()
            val publishedAfterFirstRun = statusMessagePublisher.published.size

            pollerService.pollMessages()

            statusMessagePublisher.published.size shouldBe publishedAfterFirstRun
            messageStateService.findPollableMessages() shouldBe emptyList()
        }

        "No pollable messages → do nothing" {
            val (_, messageStateService, statusMessagePublisher, pollerService) = fixture()

            pollerService.pollMessages()

            messageStateService.findPollableMessages() shouldBe emptyList()
            statusMessagePublisher.published shouldBe emptyList()
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
            ).shouldBeRight()

            ediAdapterClient.givenStatusList(externalRefId, emptyList())

            pollerService.pollAndProcessMessage(snapshot.messageState)

            val current = messageStateService.getMessageSnapshotByExternalRefId(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe null
            current.messageState.appRecStatus shouldBe null
            statusMessagePublisher.published shouldBe emptyList()
        }

        "No state change → no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            ).shouldBeRight()

            val updatedSnapshot = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null,
                    occurredAt = Clock.System.now()
                )
            )

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollAndProcessMessage(updatedSnapshot.messageState)

            val current = messageStateService.getMessageSnapshotByExternalRefId(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe ACKNOWLEDGED
            current.messageState.appRecStatus shouldBe null
            statusMessagePublisher.published shouldBe emptyList()
        }

        "NEW → PENDING_TRANSPORT publishes status" {
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
            ).shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.UNCONFIRMED, null)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = decodeStatusEvent(statusMessagePublisher.published.single())
            event.messageId shouldBe id
            event.status shouldBe PENDING_TRANSPORT
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "NEW → PENDING_APPREC publishes status" {
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
            ).shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = decodeStatusEvent(statusMessagePublisher.published.single())
            event.messageId shouldBe id
            event.status shouldBe PENDING_APPREC
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "PENDING_APPREC → COMPLETED publishes completed status with apprec payload" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            ).shouldBeRight()

            val pendingSnapshot = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null,
                    occurredAt = Clock.System.now()
                )
            )

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            ediAdapterClient.givenApprecInfoSingle(externalRefId, ApprecInfo(1, OK))

            pollerService.pollAndProcessMessage(pendingSnapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = decodeStatusEvent(statusMessagePublisher.published.single())
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.COMPLETED
            event.error shouldBe null
            event.apprec shouldNotBe null
            event.apprec!!.receiverHerId shouldBe 1
            event.apprec.appRecStatus shouldBe OK.toString()
            event.apprec.appRecErrorList shouldBe emptyList()
        }

        "External REJECTED publishes rejected transport status" {
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
            ).shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.REJECTED, null)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = decodeStatusEvent(statusMessagePublisher.published.single())
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.apprec shouldBe null
            event.error shouldNotBe null
            event.error!!.code shouldBe "REJECTED_TRANSPORT"
        }

        "Apprec REJECTED publishes rejected apprec status with apprec payload" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalUrl = URI("http://example.com/1").toURL()

            messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG,
                    externalUrl
                )
            ).shouldBeRight()

            val pendingSnapshot = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null,
                    occurredAt = Clock.System.now()
                )
            )

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, ExternalAppRecStatus.REJECTED)
            ediAdapterClient.givenApprecInfoSingle(externalRefId, ApprecInfo(1, ExternalAppRecStatus.REJECTED))

            pollerService.pollAndProcessMessage(pendingSnapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = decodeStatusEvent(statusMessagePublisher.published.single())
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_APPREC
            event.error shouldBe null
            event.apprec shouldNotBe null
            event.apprec!!.receiverHerId shouldBe 1
            event.apprec.appRecStatus shouldBe REJECTED.toString()
        }

        "Unresolvable external state → INVALID publishes invalid status" {
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
            ).shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, ExternalAppRecStatus.REJECTED)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = decodeStatusEvent(statusMessagePublisher.published.single())
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.INVALID
            event.apprec shouldBe null
            event.error shouldNotBe null
            event.error!!.code shouldBe "INVALID_STATE"
        }
    }
)

private data class Fixture(
    val ediAdapterClient: FakeEdiAdapterClient,
    val messageStateService: FakeTransactionalMessageStateService,
    val statusMessagePublisher: FakeStatusMessagePublisher,
    val pollerService: PollerService
)

private fun fixture(): Fixture {
    val ediAdapterClient = FakeEdiAdapterClient()
    val messageStateService = FakeTransactionalMessageStateService()
    val statusMessagePublisher = FakeStatusMessagePublisher()

    return Fixture(
        ediAdapterClient = ediAdapterClient,
        messageStateService = messageStateService,
        statusMessagePublisher = statusMessagePublisher,
        pollerService = pollerService(
            ediAdapterClient,
            messageStateService,
            statusMessagePublisher
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

private fun decodeStatusEvent(bytes: ByteArray): MessageStatusEvent = Json.decodeFromString(String(bytes))

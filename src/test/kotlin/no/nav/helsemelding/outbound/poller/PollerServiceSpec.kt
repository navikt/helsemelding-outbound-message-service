package no.nav.helsemelding.outbound.poller

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.ediadapter.model.v3.AppRecError
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus.OK
import no.nav.helsemelding.ediadapter.model.v3.ApprecInfo
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState.UNCONFIRMED
import no.nav.helsemelding.ediadapter.model.v3.StatusInfo
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
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.outbound.publisher.MessagePublisher
import no.nav.helsemelding.outbound.service.FakeTransactionalMessageStateService
import no.nav.helsemelding.outbound.service.MessageStateService
import no.nav.helsemelding.outbound.service.PollerService
import no.nav.helsemelding.outbound.service.StateEvaluatorService
import kotlin.time.Clock
import kotlin.uuid.Uuid
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus as ExternalAppRecStatus

class PollerServiceSpec : StringSpec(
    {
        "mark polled messages → not pollable on next run" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id1 = Uuid.random()
            val id2 = Uuid.random()
            val externalRefId1 = Uuid.random()
            val externalRefId2 = Uuid.random()

            messageStateService.createInitialState(
                CreateState(
                    id1,
                    externalRefId1,
                    DIALOG
                )
            )
                .shouldBeRight()

            messageStateService.createInitialState(
                CreateState(
                    id2,
                    externalRefId2,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId1, DeliveryState.ACKNOWLEDGED, null)
            ediAdapterClient.givenStatus(externalRefId2, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollMessages()
            val publishedAfterFirstRun = statusMessagePublisher.published.size

            pollerService.pollMessages()

            statusMessagePublisher.published.size shouldBe publishedAfterFirstRun
            messageStateService.findPollableMessages() shouldBe emptyList()
        }

        "publish failure does not stop processing subsequent messages" {
            val (client, states, publisher, poller) = fixture()
            val firstRef = Uuid.random()
            val secondRef = Uuid.random()
            val first = states.createInitialState(CreateState(Uuid.random(), firstRef, DIALOG)).shouldBeRight()
            val second = states.createInitialState(CreateState(Uuid.random(), secondRef, DIALOG)).shouldBeRight()
            client.givenStatus(firstRef, DeliveryState.ACKNOWLEDGED, OK)
            client.givenStatus(secondRef, DeliveryState.ACKNOWLEDGED, OK)
            publisher.failNext = true

            poller.pollAndProcessMessage(first.messageState)
            poller.pollAndProcessMessage(second.messageState)

            publisher.published.single().messageId shouldBe second.messageState.id
            states.getMessageSnapshotByExternalRefId(firstRef)!!.messageState.appRecStatus shouldBe
                no.nav.helsemelding.outbound.model.AppRecStatus.OK
            client.statusRequests shouldBe listOf(firstRef, secondRef)
        }

        "no pollable messages → do nothing" {
            val (_, messageStateService, statusMessagePublisher, pollerService) = fixture()

            pollerService.pollMessages()

            messageStateService.findPollableMessages() shouldBe emptyList()
            statusMessagePublisher.published shouldBe emptyList()
        }

        "no status list → no state change and no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            ).shouldBeRight()

            ediAdapterClient.givenStatusList(externalRefId, emptyList())

            pollerService.pollAndProcessMessage(snapshot.messageState)

            val current = messageStateService.getMessageSnapshotByExternalRefId(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe null
            current.messageState.appRecStatus shouldBe null
            statusMessagePublisher.published shouldBe emptyList()
        }

        "null status list and fetch failure leave state unchanged" {
            val (client, states, publisher, poller) = fixture()
            val ref = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatusList(ref, null)
            poller.pollAndProcessMessage(snapshot.messageState)
            client.givenStatusError(ref, EdiAdapterError.Api(503))
            poller.pollAndProcessMessage(snapshot.messageState)
            states.getMessageSnapshotByExternalRefId(ref) shouldBe snapshot
            publisher.published shouldBe emptyList()
        }

        "single dynamic receiver determines status and apprec errors in one request" {
            val (client, states, publisher, poller) = fixture()
            val ref = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatusList(
                ref,
                listOf(
                    StatusInfo(
                        700,
                        DeliveryState.ACKNOWLEDGED,
                        true,
                        ApprecInfo(
                            ExternalAppRecStatus.REJECTED,
                            listOf(AppRecError("E10", "Specific detail", "Description", "oid"))
                        )
                    )
                )
            )
            poller.pollAndProcessMessage(snapshot.messageState)
            val event = publisher.published.single()

            event.status shouldBe MessageStatus.REJECTED_APPREC
            event.apprec!!.receiverHerId shouldBe 700
            event.apprec.errorList.single().code shouldBe "E10"
            event.apprec.errorList.single().details shouldBe "Specific detail"
            event.apprec.errorList.single().description shouldBe "Description"
            event.apprec.errorList.single().oid shouldBe "oid"
            client.statusRequests shouldBe listOf(ref)
        }

        "missing or multiple receiver statuses leave state unchanged" {
            val (client, states, publisher, poller) = fixture()
            val ref = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            val status = StatusInfo(8142520, DeliveryState.ACKNOWLEDGED, true, ApprecInfo(OK))
            for (statuses in listOf(emptyList(), listOf(status, status.copy(receiverHerId = 999)), listOf(status, status))) {
                client.givenStatusList(ref, statuses)
                poller.pollAndProcessMessage(snapshot.messageState)
            }

            states.getMessageSnapshotByExternalRefId(ref) shouldBe snapshot
            publisher.published shouldBe emptyList()
        }

        "abandoned transport is rejected and partial apprec acceptance completes" {
            for ((transport, apprec, expected) in listOf(
                Triple(DeliveryState.ABANDONED, null, MessageStatus.REJECTED_TRANSPORT),
                Triple(DeliveryState.ACKNOWLEDGED, ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART, MessageStatus.COMPLETED)
            )) {
                val (client, states, publisher, poller) = fixture()
                val ref = Uuid.random()
                val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
                client.givenStatus(ref, transport, apprec)
                poller.pollAndProcessMessage(snapshot.messageState)

                publisher.published.single().status shouldBe expected
            }
        }

        "no state change → no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

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

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, null)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe PENDING_TRANSPORT
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "NEW → PENDING_APPREC publishes status" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, null)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe PENDING_APPREC
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "PENDING_APPREC → COMPLETED publishes completed status with apprec payload" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

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

            pollerService.pollAndProcessMessage(pendingSnapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.COMPLETED
            event.error shouldBe null
            event.apprec shouldNotBe null
            event.apprec!!.receiverHerId shouldBe 8142520
            event.apprec.status shouldBe OK.toString()
            event.apprec.errorList shouldBe emptyList()
        }

        "external REJECTED publishes rejected transport status" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.REJECTED, null)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.apprec shouldBe null
            event.error shouldNotBe null
            event.error!!.code shouldBe "REJECTED_TRANSPORT"
        }

        "apprec REJECTED publishes rejected apprec status with apprec payload" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

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

            pollerService.pollAndProcessMessage(pendingSnapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_APPREC
            event.error shouldBe null
            event.apprec shouldNotBe null
            event.apprec!!.receiverHerId shouldBe 8142520
            event.apprec.status shouldBe REJECTED.toString()
        }

        "unresolvable external state → INVALID publishes invalid status" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, pollerService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, ExternalAppRecStatus.REJECTED)

            pollerService.pollAndProcessMessage(snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.INVALID
            event.apprec!!.status shouldBe ExternalAppRecStatus.REJECTED.name
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

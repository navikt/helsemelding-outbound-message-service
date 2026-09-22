package no.nav.helsemelding.outbound.service

import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.ediadapter.model.v3.AppRecError
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus.OK
import no.nav.helsemelding.ediadapter.model.v3.ApprecInfo
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState.UNCONFIRMED
import no.nav.helsemelding.ediadapter.model.v3.Notification
import no.nav.helsemelding.ediadapter.model.v3.NotificationType
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.MESSAGE_APPREC_INFO_UPDATED
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.MESSAGE_DELIVERY_STATE_UPDATED
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.MESSAGE_SENT_STATE_UPDATED
import no.nav.helsemelding.ediadapter.model.v3.StatusInfo
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.AppRecStatus.REJECTED
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ABANDONED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatus.PENDING_APPREC
import no.nav.helsemelding.outbound.model.MessageStatus.PENDING_TRANSPORT
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.outbound.publisher.MessagePublisher
import no.nav.helsemelding.outbound.repository.NotificationOffsetRepository
import kotlin.time.Clock
import kotlin.time.Instant.Companion.fromEpochSeconds
import kotlin.uuid.Uuid
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus as ExternalAppRecStatus
import no.nav.helsemelding.outbound.util.coroutineScope as resourceCoroutineScope

class NotificationServiceSpec : StringSpec(
    {
        val senderHerId = config().ediAdapter.senderHerId.value

        "collection resumes from the stored offset and advances after intentional skips" {
            val notificationOffsetRepository = FakeNotificationOffsetRepository()
            notificationOffsetRepository.saveOffset(senderHerId, 100L)
            notificationOffsetRepository.saved.clear()
            val (client, _, _, service) = fixture(notificationOffsetRepository)
            client.notifications = flowOf(
                notification(null, NotificationType.NEW_MESSAGE).copy(offset = 110L).right(),
                notification(null, NotificationType.REFUSED_MESSAGE).copy(offset = 120L).right(),
                notification(null).copy(offset = 130L).right(),
                notification(Uuid.random()).copy(offset = 140L).right()
            )

            service.processNotifications(this).join()

            client.notificationRequests shouldBe listOf(listOf(senderHerId) to 100L)
            notificationOffsetRepository.saved.map { it.second } shouldBe listOf(110L, 120L, 130L, 140L)
            val (restartedClient, _, _, restartedService) = fixture(notificationOffsetRepository)
            restartedService.processNotifications(this).join()
            restartedClient.notificationRequests shouldBe listOf(listOf(senderHerId) to 140L)
        }

        "offset is saved after publication and persistence" {
            val (client, states, publisher, service, offsets) = fixture()
            val externalRefId = Uuid.random()
            states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            client.notifications = flowOf(notification(externalRefId).right())
            offsets.beforeSave = {
                publisher.published.single().status shouldBe MessageStatus.COMPLETED
                states.getMessageSnapshotByExternalRefId(externalRefId)!!.messageState.appRecStatus shouldBe AppRecStatus.OK
            }

            service.processNotifications(this).join()

            offsets.getOffset(senderHerId) shouldBe 43L
        }

        "status fetch failure stops collection before advancing past the failed notification" {
            val (client, states, publisher, service, offsets) = fixture()
            val ref = Uuid.random()
            states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatusError(ref, EdiAdapterError.Api(503))
            client.notifications = flowOf(
                notification(null, NotificationType.NEW_MESSAGE).copy(offset = 41L).right(),
                notification(ref).copy(offset = 42L).right(),
                notification(null).copy(offset = 43L).right()
            )

            shouldThrow<NotificationProcessingException> {
                coroutineScope { service.processNotifications(this).join() }
            }

            offsets.getOffset(senderHerId) shouldBe 41L
            publisher.published shouldBe emptyList()
        }

        "failed publication can be replayed without skipping a locally terminal state" {
            val (client, states, publisher, service, offsets) = fixture()
            val ref = Uuid.random()
            val initial = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatus(ref, DeliveryState.ACKNOWLEDGED, OK)
            client.notifications = flowOf(notification(ref).right(), notification(null).copy(offset = 44L).right())
            publisher.failNext = true

            shouldThrow<NotificationProcessingException> {
                coroutineScope { service.processNotifications(this).join() }
            }

            offsets.getOffset(senderHerId) shouldBe 0L
            states.getMessageSnapshotByExternalRefId(ref) shouldBe initial
            val restarted = notificationService(client, states, publisher, offsets)
            restarted.processNotifications(this).join()
            publisher.published.single().status shouldBe MessageStatus.COMPLETED
            offsets.getOffset(senderHerId) shouldBe 44L
        }

        "terminal messages advance the stored offset across gaps" {
            val (client, states, _, service, offsets) = fixture()
            val ref = Uuid.random()
            states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            states.recordStateChange(UpdateState(ref, DIALOG, null, ACKNOWLEDGED, null, AppRecStatus.OK))
            client.notifications = flowOf(notification(ref).right(), notification(ref).copy(offset = 50L).right())

            service.processNotifications(this).join()

            offsets.getOffset(senderHerId) shouldBe 50L
            client.statusRequests shouldBe emptyList()
        }

        "outgoing notifications refresh the referenced message" {
            for (type in listOf(
                MESSAGE_SENT_STATE_UPDATED,
                MESSAGE_APPREC_INFO_UPDATED,
                MESSAGE_DELIVERY_STATE_UPDATED
            )) {
                val (client, states, publisher, notificationService) = fixture()
                val ref = Uuid.random()
                val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
                client.givenStatus(ref, DeliveryState.ACKNOWLEDGED, OK)
                client.notifications = flowOf(notification(ref, type).right())

                notificationService.processNotifications(this).join()

                client.notificationRequests shouldBe listOf(listOf(senderHerId) to 0L)
                client.statusRequests shouldBe listOf(ref)
                publisher.published.single().messageId shouldBe snapshot.messageState.id
                publisher.published.single().status shouldBe MessageStatus.COMPLETED
            }
        }

        "duplicate notifications do not publish the same transition twice" {
            val (client, states, publisher, notificationService) = fixture()
            val ref = Uuid.random()
            states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatus(ref, DeliveryState.ACKNOWLEDGED, OK)
            val event = notification(ref).right()
            client.notifications = flowOf(event, event)

            notificationService.processNotifications(this).join()

            client.notificationRequests shouldBe listOf(listOf(senderHerId) to 0L)
            publisher.published.size shouldBe 1
            client.statusRequests shouldBe listOf(ref)
        }

        "terminal messages skip old notifications without fetching or changing state" {
            val terminalStatuses = listOf(
                ACKNOWLEDGED to AppRecStatus.OK,
                ACKNOWLEDGED to AppRecStatus.OK_ERROR_IN_MESSAGE_PART,
                ACKNOWLEDGED to REJECTED,
                ExternalDeliveryState.REJECTED to null,
                ABANDONED to null
            )
            for ((transport, apprec) in terminalStatuses) {
                val (client, states, publisher, service) = fixture()
                val ref = Uuid.random()
                states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
                states.recordStateChange(UpdateState(ref, DIALOG, null, transport, null, apprec))
                val before = states.getMessageSnapshotByExternalRefId(ref)
                client.givenStatus(ref, UNCONFIRMED, null)
                client.notifications = flowOf(notification(ref).copy(createdAt = fromEpochSeconds(0)).right())

                service.processNotifications(this).join()

                client.statusRequests shouldBe emptyList()
                publisher.published shouldBe emptyList()
                states.getMessageSnapshotByExternalRefId(ref) shouldBe before
            }
        }

        "incoming and untracked notifications do not fetch statuses" {
            val (client, states, publisher, notificationService) = fixture()
            val ref = Uuid.random()
            states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.notifications = flowOf(
                notification(ref, NotificationType.NEW_MESSAGE).right(),
                notification(ref, NotificationType.REFUSED_MESSAGE).right(),
                notification(null).right(),
                notification(Uuid.random()).right()
            )

            notificationService.processNotifications(this).join()

            client.statusRequests shouldBe emptyList()
            publisher.published shouldBe emptyList()
        }

        "terminal stream failures cancel other processes in the application scope" {
            val (client, _, _, service, offsets) = fixture()
            client.notifications = flowOf(EdiAdapterError.Api(401).left())
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()

            shouldThrow<NotificationProcessingException> {
                coroutineScope {
                    resourceScope {
                        val scope = resourceCoroutineScope(coroutineContext)
                        scope.launch {
                            try {
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                        started.await()
                        service.processNotifications(scope)
                        awaitCancellation()
                    }
                }
            }

            stopped.isCompleted shouldBe true
            offsets.getOffset(senderHerId) shouldBe 0L
            client.statusRequests shouldBe emptyList()
        }

        "cancelling consumption closes the notification flow" {
            val (client, _, _, notificationService) = fixture()
            val started = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            client.notifications = flow {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    closed.complete(Unit)
                }
            }
            val job = notificationService.processNotifications(this)
            started.await()
            job.cancelAndJoin()
            closed.isCompleted shouldBe true
        }

        "publish failure leaves local state unchanged for replay" {
            val (client, states, publisher, notificationService) = fixture()
            val firstRef = Uuid.random()
            val secondRef = Uuid.random()
            val first = states.createInitialState(CreateState(Uuid.random(), firstRef, DIALOG)).shouldBeRight()
            val second = states.createInitialState(CreateState(Uuid.random(), secondRef, DIALOG)).shouldBeRight()
            client.givenStatus(firstRef, DeliveryState.ACKNOWLEDGED, OK)
            client.givenStatus(secondRef, DeliveryState.ACKNOWLEDGED, OK)
            publisher.failNext = true

            shouldThrow<NotificationProcessingException> {
                coroutineScope { notificationService.processMessage(this, client, first.messageState) }
            }
            notificationService.processMessage(this, client, second.messageState)

            publisher.published.single().messageId shouldBe second.messageState.id
            states.getMessageSnapshotByExternalRefId(firstRef) shouldBe first
            client.statusRequests shouldBe listOf(firstRef, secondRef)
        }

        "empty notification stream does not publish" {
            val (_, _, statusMessagePublisher, notificationService) = fixture()

            notificationService.processNotifications(this).join()

            statusMessagePublisher.published shouldBe emptyList()
        }

        "no status list → no state change and no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            ediAdapterClient.givenStatusList(externalRefId, emptyList())

            shouldThrow<NotificationProcessingException> {
                coroutineScope { notificationService.processMessage(this, ediAdapterClient, snapshot.messageState) }
            }

            val current = messageStateService.getMessageSnapshotByExternalRefId(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe null
            current.messageState.appRecStatus shouldBe null
            statusMessagePublisher.published shouldBe emptyList()
        }

        "null status list and fetch failure leave state unchanged" {
            val (client, states, publisher, notificationService) = fixture()
            val ref = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatusList(ref, null)
            shouldThrow<NotificationProcessingException> {
                coroutineScope { notificationService.processMessage(this, client, snapshot.messageState) }
            }
            client.givenStatusError(ref, EdiAdapterError.Api(503))
            shouldThrow<NotificationProcessingException> {
                coroutineScope { notificationService.processMessage(this, client, snapshot.messageState) }
            }
            states.getMessageSnapshotByExternalRefId(ref) shouldBe snapshot
            publisher.published shouldBe emptyList()
        }

        "single dynamic receiver determines status and apprec errors in one request" {
            val (client, states, publisher, notificationService) = fixture()
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
            notificationService.processMessage(this, client, snapshot.messageState)
            val event = publisher.published.single()

            event.status shouldBe MessageStatus.REJECTED_APPREC
            event.apprec!!.receiverHerId shouldBe 700

            val appRecErrorMessage = event.apprec.errorList.single()

            appRecErrorMessage.code shouldBe "E10"
            appRecErrorMessage.details shouldBe "Specific detail"
            appRecErrorMessage.description shouldBe "Description"
            appRecErrorMessage.oid shouldBe "oid"
            client.statusRequests shouldBe listOf(ref)
        }

        "missing or multiple receiver statuses leave state unchanged" {
            val (client, states, publisher, notificationService) = fixture()
            val ref = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            val status = StatusInfo(8142520, DeliveryState.ACKNOWLEDGED, true, ApprecInfo(OK))
            for (statuses in listOf(
                emptyList(),
                listOf(status, status.copy(receiverHerId = 999)),
                listOf(status, status)
            )) {
                client.givenStatusList(ref, statuses)
                shouldThrow<NotificationProcessingException> {
                    coroutineScope { notificationService.processMessage(this, client, snapshot.messageState) }
                }
            }

            states.getMessageSnapshotByExternalRefId(ref) shouldBe snapshot
            publisher.published shouldBe emptyList()
        }

        "abandoned transport preserves the NHN outcome and publishes a distinct error code" {
            val (client, states, publisher, service) = fixture()
            val ref = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatus(ref, DeliveryState.ABANDONED, null)

            service.processMessage(this, client, snapshot.messageState)

            val event = publisher.published.single()
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.error!!.code shouldBe "TRANSPORT_ABANDONED"
            event.error.details shouldBe "NHN abandoned transport after failed sending attempts for messageId=${snapshot.messageState.id}"
            event.apprec shouldBe null
            val stored = states.getMessageSnapshotByExternalRefId(ref)!!
            stored.messageState.externalDeliveryState shouldBe ABANDONED
            stored.messageStateChanges.last().newDeliveryState shouldBe ABANDONED
        }

        "abandoned transport is rejected and partial apprec acceptance completes" {
            for ((transport, apprec, expected) in listOf(
                Triple(DeliveryState.ABANDONED, null, MessageStatus.REJECTED_TRANSPORT),
                Triple(
                    DeliveryState.ACKNOWLEDGED,
                    ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART,
                    MessageStatus.COMPLETED
                )
            )) {
                val (client, states, publisher, notificationService) = fixture()
                val ref = Uuid.random()
                val snapshot = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
                client.givenStatus(ref, transport, apprec)
                notificationService.processMessage(this, client, snapshot.messageState)

                publisher.published.single().status shouldBe expected
            }
        }

        "no state change → no publish" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, updatedSnapshot.messageState)

            val current = messageStateService.getMessageSnapshotByExternalRefId(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe ACKNOWLEDGED
            current.messageState.appRecStatus shouldBe null
            statusMessagePublisher.published shouldBe emptyList()
        }

        "NEW → PENDING_TRANSPORT publishes status" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe PENDING_TRANSPORT
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "NEW → PENDING_APPREC publishes status" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe PENDING_APPREC
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "PENDING_APPREC → COMPLETED publishes completed status with apprec payload" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, pendingSnapshot.messageState)

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
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusMessagePublisher.published.size shouldBe 1

            val event = statusMessagePublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.apprec shouldBe null
            event.error shouldNotBe null
            event.error!!.code shouldBe "REJECTED_TRANSPORT"
        }

        "apprec REJECTED publishes rejected apprec status with apprec payload" {
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, pendingSnapshot.messageState)

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
            val (ediAdapterClient, messageStateService, statusMessagePublisher, notificationService) = fixture()

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

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

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
    val notificationService: NotificationService,
    val offsets: FakeNotificationOffsetRepository
)

private fun fixture(offsets: FakeNotificationOffsetRepository = FakeNotificationOffsetRepository()): Fixture {
    val ediAdapterClient = FakeEdiAdapterClient()
    val messageStateService = FakeTransactionalMessageStateService()
    val statusMessagePublisher = FakeStatusMessagePublisher()

    return Fixture(
        ediAdapterClient = ediAdapterClient,
        messageStateService = messageStateService,
        statusMessagePublisher = statusMessagePublisher,
        notificationService = notificationService(
            ediAdapterClient,
            messageStateService,
            statusMessagePublisher,
            offsets
        ),
        offsets = offsets
    )
}

private fun notificationService(
    ediAdapterClient: EdiAdapterClient,
    messageStateService: MessageStateService,
    messagePublisher: MessagePublisher,
    offsets: NotificationOffsetRepository
): NotificationService = NotificationService(
    ediAdapterClient,
    messageStateService,
    stateEvaluatorService(),
    messagePublisher,
    offsets
)

private fun stateEvaluatorService(): StateEvaluatorService = StateEvaluatorService(
    TransportStatusTranslator(),
    StateTransitionEvaluator(
        TransportTransitionEvaluator(),
        AppRecTransitionEvaluator()
    )
)

private fun notification(
    ref: Uuid?,
    type: NotificationType = MESSAGE_DELIVERY_STATE_UPDATED
): Notification = Notification(
    notificationId = Uuid.random(),
    relatedMessageId = ref,
    type = type,
    notificationReceiverHerId = config().ediAdapter.senderHerId.value,
    offset = 43L
)

private suspend fun NotificationService.processMessage(
    scope: CoroutineScope,
    client: FakeEdiAdapterClient,
    message: MessageState
) {
    client.notifications = flowOf(notification(message.externalRefId).right())
    processNotifications(scope).join()
}

private class FakeNotificationOffsetRepository : NotificationOffsetRepository {
    private val offsets = mutableMapOf<Int, Long>()
    val saved = mutableListOf<Pair<Int, Long>>()
    var beforeSave: suspend () -> Unit = {}

    override suspend fun getOffset(herId: Int): Long = offsets[herId] ?: 0L

    override suspend fun saveOffset(herId: Int, offset: Long) {
        beforeSave()
        offsets[herId] = offset
        saved += herId to offset
    }
}

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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.ediadapter.model.v3.AppRecError
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus.OK
import no.nav.helsemelding.ediadapter.model.v3.ApprecInfo
import no.nav.helsemelding.ediadapter.model.v3.DeleteNotificationsRequest
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState.UNCONFIRMED
import no.nav.helsemelding.ediadapter.model.v3.NotificationType
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.MESSAGE_APPREC_INFO_UPDATED
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.MESSAGE_DELIVERY_STATE_UPDATED
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.MESSAGE_SENT_STATE_UPDATED
import no.nav.helsemelding.ediadapter.model.v3.StatusInfo
import no.nav.helsemelding.ediadapter.model.v3.UnreadNotification
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
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatus.PENDING_APPREC
import no.nav.helsemelding.outbound.model.MessageStatus.PENDING_TRANSPORT
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.outbound.publisher.MessagePublisher
import kotlin.time.Clock
import kotlin.time.Instant.Companion.fromEpochSeconds
import kotlin.uuid.Uuid
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus as ExternalAppRecStatus
import no.nav.helsemelding.outbound.util.coroutineScope as resourceCoroutineScope

class NotificationServiceSpec : StringSpec(
    {
        val senderHerId = config().ediAdapter.senderHerId.value

        "incoming notifications are preserved and unknown outgoing status notifications are deleted" {
            val (client, states, publisher, service) = fixture()
            val knownRef = Uuid.random()
            val initial = states.createInitialState(CreateState(Uuid.random(), knownRef, DIALOG)).shouldBeRight()
            val incoming = listOf(null, knownRef).flatMap { ref ->
                listOf(notification(ref, NotificationType.NEW_MESSAGE), notification(ref, NotificationType.REFUSED_MESSAGE))
            }
            val outgoing = listOf(MESSAGE_SENT_STATE_UPDATED, MESSAGE_APPREC_INFO_UPDATED, MESSAGE_DELIVERY_STATE_UPDATED)
                .flatMap { type -> listOf(notification(null, type), notification(Uuid.random(), type)) }
            client.notifications = (incoming + outgoing).map { it.right() }.asFlow()

            service.processNotifications(this).join()

            client.notificationRequests shouldBe listOf(listOf(senderHerId))
            client.deleteRequests shouldBe outgoing.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
            client.statusRequests shouldBe emptyList()
            publisher.published shouldBe emptyList()
            states.getMessageSnapshotByExternalRefId(knownRef) shouldBe initial
        }

        "apprec is marked as downloaded after state change and before notification deletion" {
            val (client, states, publisher, service) = fixture()
            val externalRefId = Uuid.random()
            val appRecId = Uuid.random()
            val notification = notification(externalRefId)
            states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK, appRecId)
            client.notifications = flowOf(notification.right())
            client.beforeMarkDownloaded = {
                publisher.published.single().status shouldBe MessageStatus.COMPLETED
                val messageStateSnapshot = states.getMessageSnapshotByExternalRefId(externalRefId)!!
                messageStateSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
                client.deleteRequests shouldBe emptyList()
            }
            client.beforeDeleteNotifications = {
                client.downloadedRequests.single().first shouldBe appRecId
                val messageStateSnapshot = states.getMessageSnapshotByExternalRefId(externalRefId)!!
                messageStateSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
            }

            service.processNotifications(this).join()

            client.downloadedRequests.single().first shouldBe appRecId
            client.downloadedRequests.single().second.receiverHerId shouldBe senderHerId
            client.deleteRequests shouldBe listOf(DeleteNotificationsRequest(listOf(notification.notificationId)))
        }

        "failure to mark apprec as downloaded does not block state updates or notification deletion" {
            val (client, states, publisher, service) = fixture()
            val externalRefId = Uuid.random()
            val appRecId = Uuid.random()
            val notification = notification(externalRefId)
            states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK, appRecId)
            client.notifications = flowOf(notification.right())
            client.downloadError = EdiAdapterError.Api(503)

            service.processNotifications(this).join()

            client.deleteRequests shouldBe listOf(DeleteNotificationsRequest(listOf(notification.notificationId)))
            val messageStateSnapshot = states.getMessageSnapshotByExternalRefId(externalRefId)!!
            messageStateSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
            publisher.published.size shouldBe 1
            client.downloadedRequests.single().first shouldBe appRecId
        }

        "status fetch failure stops collection without deleting the failed notification" {
            val (client, states, publisher, service) = fixture()
            val externalRefId = Uuid.random()
            val skipped = notification(null, NotificationType.NEW_MESSAGE)
            val initial = states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatusError(externalRefId, EdiAdapterError.Api(503))
            client.notifications = flowOf(
                skipped.right(),
                notification(externalRefId).right(),
                notification(null).right()
            )

            shouldThrow<NotificationProcessingException> {
                coroutineScope { service.processNotifications(this).join() }
            }

            states.getMessageSnapshotByExternalRefId(externalRefId) shouldBe initial
            client.deleteRequests shouldBe emptyList()
            client.statusRequests shouldBe listOf(externalRefId)
            publisher.published shouldBe emptyList()
        }

        "failed publication leaves state unchanged and replay processes the remaining notifications" {
            val (client, states, publisher, service) = fixture()
            val refs = List(2) { Uuid.random() }
            val initial = refs.map { ref ->
                states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            }
            val notifications = refs.map { notification(it) }
            refs.forEach { client.givenStatus(it, DeliveryState.ACKNOWLEDGED, OK) }
            client.notifications = notifications.map { it.right() }.asFlow()
            publisher.failNext = true

            shouldThrow<NotificationProcessingException> {
                coroutineScope { service.processNotifications(this).join() }
            }

            refs.map { states.getMessageSnapshotByExternalRefId(it) } shouldBe initial
            client.statusRequests shouldBe listOf(refs.first())
            client.deleteRequests shouldBe emptyList()
            publisher.published shouldBe emptyList()

            notificationService(client, states, publisher).processNotifications(this).join()

            publisher.published.map { it.messageId } shouldBe initial.map { it.messageState.id }
            publisher.published.map { it.status } shouldBe List(2) { MessageStatus.COMPLETED }
            client.statusRequests shouldBe listOf(refs.first()) + refs
            client.deleteRequests shouldBe notifications.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
        }

        "notification deletion failure does not stop processing and replay does not republish" {
            val (client, states, publisher, service) = fixture()
            val externalRefIds = listOf(Uuid.random(), Uuid.random())
            for (externalRefId in externalRefIds) {
                states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
                client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            }
            val notifications = externalRefIds.map { notification(it) }
            val deleteRequests = notifications.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
            client.notifications = notifications.map { it.right() }.asFlow()
            client.deleteError = EdiAdapterError.Api(503)

            service.processNotifications(this).join()

            publisher.published.map { it.status } shouldBe listOf(MessageStatus.COMPLETED, MessageStatus.COMPLETED)
            client.statusRequests shouldBe externalRefIds
            client.deleteRequests shouldBe deleteRequests
            for (externalRefId in externalRefIds) {
                val messageSnapshot = states.getMessageSnapshotByExternalRefId(externalRefId)!!
                messageSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
            }

            client.deleteError = null
            val restarted = notificationService(client, states, publisher)
            restarted.processNotifications(this).join()

            publisher.published.size shouldBe 2
            client.statusRequests shouldBe externalRefIds
            client.deleteRequests shouldBe deleteRequests + deleteRequests
        }

        "state persistence failure leaves the notification undeleted" {
            val (client, states, publisher) = fixture()
            val externalRefId = Uuid.random()
            val initial = states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK, Uuid.random())
            client.notifications = flowOf(notification(externalRefId).right(), notification(null).right())
            val failure = IllegalStateException("State persistence failed")
            val failingStates = object : MessageStateService by states {
                override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot = throw failure
            }
            val service = notificationService(client, failingStates, publisher)

            shouldThrow<IllegalStateException> {
                coroutineScope { service.processNotifications(this).join() }
            } shouldBe failure

            publisher.published.single().status shouldBe MessageStatus.COMPLETED
            states.getMessageSnapshotByExternalRefId(externalRefId) shouldBe initial
            client.downloadedRequests shouldBe emptyList()
            client.deleteRequests shouldBe emptyList()
        }

        "outgoing notifications refresh the referenced message" {
            for (type in listOf(
                MESSAGE_SENT_STATE_UPDATED,
                MESSAGE_APPREC_INFO_UPDATED,
                MESSAGE_DELIVERY_STATE_UPDATED
            )) {
                val (client, states, publisher, notificationService) = fixture()
                val externalRefId = Uuid.random()
                val snapshot =
                    states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
                client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
                client.notifications = flowOf(notification(externalRefId, type).right())

                notificationService.processNotifications(this).join()

                client.notificationRequests shouldBe listOf(listOf(senderHerId))
                client.statusRequests shouldBe listOf(externalRefId)
                publisher.published.single().messageId shouldBe snapshot.messageState.id
                publisher.published.single().status shouldBe MessageStatus.COMPLETED
            }
        }

        "duplicate notifications do not publish the same transition twice" {
            val (client, states, publisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            val event = notification(externalRefId).right()
            client.notifications = flowOf(event, event)

            notificationService.processNotifications(this).join()

            client.notificationRequests shouldBe listOf(listOf(senderHerId))
            publisher.published.size shouldBe 1
            client.statusRequests shouldBe listOf(externalRefId)
        }

        "terminal message notifications are deleted without fetching or changing state" {
            val terminalStatuses = listOf(
                ACKNOWLEDGED to AppRecStatus.OK,
                ACKNOWLEDGED to AppRecStatus.OK_ERROR_IN_MESSAGE_PART,
                ACKNOWLEDGED to REJECTED,
                ExternalDeliveryState.REJECTED to null,
                ABANDONED to null
            )
            for ((transport, apprec) in terminalStatuses) {
                val (client, states, publisher, service) = fixture()
                val externalRefId = Uuid.random()
                states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
                states.recordStateChange(UpdateState(externalRefId, DIALOG, null, transport, null, apprec))
                val before = states.getMessageSnapshotByExternalRefId(externalRefId)
                val notifications = List(2) { notification(externalRefId).copy(createdAt = fromEpochSeconds(0)) }
                client.givenStatus(externalRefId, UNCONFIRMED, null)
                client.notifications = notifications.map { it.right() }.asFlow()

                service.processNotifications(this).join()

                client.deleteRequests shouldBe notifications.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
                client.statusRequests shouldBe emptyList()
                publisher.published shouldBe emptyList()
                states.getMessageSnapshotByExternalRefId(externalRefId) shouldBe before
            }
        }

        "terminal stream failures cancel other processes in the application scope" {
            val (client, _, _, service) = fixture()
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
            client.deleteRequests shouldBe emptyList()
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

        "empty notification stream does not publish" {
            val (_, _, statusMessagePublisher, notificationService) = fixture()

            notificationService.processNotifications(this).join()

            statusMessagePublisher.published shouldBe emptyList()
        }

        "single dynamic receiver determines status and apprec errors in one request" {
            val (client, states, publisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatusList(
                externalRefId,
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
            client.statusRequests shouldBe listOf(externalRefId)
        }

        "missing or multiple receiver statuses leave state unchanged" {
            val status = StatusInfo(8142520, DeliveryState.ACKNOWLEDGED, true, ApprecInfo(OK))
            for (statuses in listOf(null, emptyList(), listOf(status, status.copy(receiverHerId = 999)), listOf(status, status))) {
                val (client, states, publisher, service) = fixture()
                val ref = Uuid.random()
                val initial = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
                client.givenStatusList(ref, statuses)

                shouldThrow<NotificationProcessingException> {
                    coroutineScope { service.processMessage(this, client, initial.messageState) }
                }

                states.getMessageSnapshotByExternalRefId(ref) shouldBe initial
                client.statusRequests shouldBe listOf(ref)
                client.downloadedRequests shouldBe emptyList()
                client.deleteRequests shouldBe emptyList()
                publisher.published shouldBe emptyList()
            }
        }

        "abandoned transport preserves the NHN outcome and publishes a distinct error code" {
            val (client, states, publisher, service) = fixture()
            val externalRefId = Uuid.random()
            val snapshot = states.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            client.givenStatus(externalRefId, DeliveryState.ABANDONED, null)

            service.processMessage(this, client, snapshot.messageState)

            val event = publisher.published.single()
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.error!!.code shouldBe "TRANSPORT_ABANDONED"
            event.error.details shouldBe "Transport abandoned after failed sending attempts for messageId: ${snapshot.messageState.id}"
            event.apprec shouldBe null
            client.downloadedRequests shouldBe emptyList()
            val stored = states.getMessageSnapshotByExternalRefId(externalRefId)!!
            stored.messageState.externalDeliveryState shouldBe ABANDONED
            stored.messageStateChanges.last().newDeliveryState shouldBe ABANDONED
        }

        "partial apprec acceptance completes delivery" {
            val (client, states, publisher, service) = fixture()
            val ref = Uuid.random()
            val initial = states.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            client.givenStatus(ref, DeliveryState.ACKNOWLEDGED, ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART)

            service.processMessage(this, client, initial.messageState)

            publisher.published.single().status shouldBe MessageStatus.COMPLETED
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
            ediAdapterClient.downloadedRequests shouldBe emptyList()
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
            event.error!!.code shouldBe "TRANSPORT_REJECTED"
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
    val notificationService: NotificationService
)

private fun fixture(): Fixture {
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
            statusMessagePublisher
        )
    )
}

private fun notificationService(
    ediAdapterClient: EdiAdapterClient,
    messageStateService: MessageStateService,
    messagePublisher: MessagePublisher
): NotificationService = NotificationService(
    ediAdapterClient,
    messageStateService,
    MessageStatusService(messageStateService, stateEvaluatorService(), messagePublisher)
)

private fun stateEvaluatorService(): StateEvaluatorService = StateEvaluatorService(
    TransportStatusTranslator(),
    StateTransitionEvaluator(
        TransportTransitionEvaluator(),
        AppRecTransitionEvaluator()
    )
)

private fun notification(
    relatedMessageId: Uuid?,
    type: NotificationType = MESSAGE_DELIVERY_STATE_UPDATED
): UnreadNotification = UnreadNotification(
    notificationId = Uuid.random(),
    relatedMessageId = relatedMessageId,
    type = type,
    notificationReceiverHerId = config().ediAdapter.senderHerId.value
)

private suspend fun NotificationService.processMessage(
    scope: CoroutineScope,
    client: FakeEdiAdapterClient,
    message: MessageState
) {
    client.notifications = flowOf(notification(message.externalRefId).right())
    processNotifications(scope).join()
}

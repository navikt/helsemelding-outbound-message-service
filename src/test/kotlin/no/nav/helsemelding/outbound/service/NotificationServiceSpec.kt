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
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val knownExternalRef = Uuid.random()
            val initial = messageService.createInitialState(
                CreateState(
                    Uuid.random(),
                    knownExternalRef,
                    DIALOG
                )
            )
                .shouldBeRight()

            val incoming = listOf(null, knownExternalRef).flatMap { ref ->
                listOf(
                    notification(ref, NotificationType.NEW_MESSAGE),
                    notification(ref, NotificationType.REFUSED_MESSAGE)
                )
            }
            val outgoing = listOf(
                MESSAGE_SENT_STATE_UPDATED,
                MESSAGE_APPREC_INFO_UPDATED,
                MESSAGE_DELIVERY_STATE_UPDATED
            )
                .flatMap { type -> listOf(notification(null, type), notification(Uuid.random(), type)) }

            ediAdapterClient.notifications = (incoming + outgoing).map { it.right() }.asFlow()

            notificationService.processNotifications(this).join()

            ediAdapterClient.notificationRequests shouldBe listOf(listOf(senderHerId))
            ediAdapterClient.deleteRequests shouldBe outgoing.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
            ediAdapterClient.statusRequests shouldBe emptyList()
            statusPublisher.published shouldBe emptyList()
            messageService.getMessageSnapshotByExternalRefId(knownExternalRef) shouldBe initial
        }

        "apprec is marked as downloaded after state change and before notification deletion" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            val appRecId = Uuid.random()
            val notification = notification(externalRefId)
            messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK, appRecId)
            ediAdapterClient.notifications = flowOf(notification.right())
            ediAdapterClient.beforeMarkDownloaded = {
                statusPublisher.published.single().status shouldBe MessageStatus.COMPLETED
                val messageStateSnapshot = messageService.getMessageSnapshotByExternalRefId(externalRefId)!!
                messageStateSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
                ediAdapterClient.deleteRequests shouldBe emptyList()
            }
            ediAdapterClient.beforeDeleteNotifications = {
                ediAdapterClient.downloadedRequests.single().first shouldBe appRecId
                val messageStateSnapshot = messageService.getMessageSnapshotByExternalRefId(externalRefId)!!
                messageStateSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
            }

            notificationService.processNotifications(this).join()

            ediAdapterClient.downloadedRequests.single().first shouldBe appRecId
            ediAdapterClient.downloadedRequests.single().second.receiverHerId shouldBe senderHerId
            ediAdapterClient.deleteRequests shouldBe listOf(DeleteNotificationsRequest(listOf(notification.notificationId)))
        }

        "failure to mark apprec as downloaded does not block state updates or notification deletion" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            val appRecId = Uuid.random()
            val notification = notification(externalRefId)
            messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK, appRecId)
            ediAdapterClient.notifications = flowOf(notification.right())
            ediAdapterClient.downloadError = EdiAdapterError.Api(503)

            notificationService.processNotifications(this).join()

            ediAdapterClient.deleteRequests shouldBe listOf(DeleteNotificationsRequest(listOf(notification.notificationId)))
            val messageStateSnapshot = messageService.getMessageSnapshotByExternalRefId(externalRefId)!!
            messageStateSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
            statusPublisher.published.size shouldBe 1
            ediAdapterClient.downloadedRequests.single().first shouldBe appRecId
        }

        "status fetch failure stops collection without deleting the failed notification" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            val skipped = notification(null, NotificationType.NEW_MESSAGE)
            val initial =
                messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatusError(externalRefId, EdiAdapterError.Api(503))
            ediAdapterClient.notifications = flowOf(
                skipped.right(),
                notification(externalRefId).right(),
                notification(null).right()
            )

            shouldThrow<NotificationProcessingException> {
                coroutineScope { notificationService.processNotifications(this).join() }
            }

            messageService.getMessageSnapshotByExternalRefId(externalRefId) shouldBe initial
            ediAdapterClient.deleteRequests shouldBe emptyList()
            ediAdapterClient.statusRequests shouldBe listOf(externalRefId)
            statusPublisher.published shouldBe emptyList()
        }

        "failed publication leaves state unchanged and replay processes the remaining notifications" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val refs = List(2) { Uuid.random() }
            val initial = refs.map { ref ->
                messageService.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            }
            val notifications = refs.map { notification(it) }
            refs.forEach { ediAdapterClient.givenStatus(it, DeliveryState.ACKNOWLEDGED, OK) }
            ediAdapterClient.notifications = notifications.map { it.right() }.asFlow()
            statusPublisher.failNext = true

            shouldThrow<NotificationProcessingException> {
                coroutineScope { notificationService.processNotifications(this).join() }
            }

            refs.map { messageService.getMessageSnapshotByExternalRefId(it) } shouldBe initial
            ediAdapterClient.statusRequests shouldBe listOf(refs.first())
            ediAdapterClient.deleteRequests shouldBe emptyList()
            statusPublisher.published shouldBe emptyList()

            createNotificationService(ediAdapterClient, messageService, statusPublisher)
                .processNotifications(this).join()

            statusPublisher.published.map { it.messageId } shouldBe initial.map { it.messageState.id }
            statusPublisher.published.map { it.status } shouldBe List(2) { MessageStatus.COMPLETED }
            ediAdapterClient.statusRequests shouldBe listOf(refs.first()) + refs
            ediAdapterClient.deleteRequests shouldBe notifications.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
        }

        "notification deletion failure does not stop processing and replay does not republish" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefIds = listOf(Uuid.random(), Uuid.random())
            for (externalRefId in externalRefIds) {
                messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
                ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            }
            val notifications = externalRefIds.map { notification(it) }
            val deleteRequests = notifications.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
            ediAdapterClient.notifications = notifications.map { it.right() }.asFlow()
            ediAdapterClient.deleteError = EdiAdapterError.Api(503)

            notificationService.processNotifications(this).join()

            statusPublisher.published.map { it.status } shouldBe listOf(
                MessageStatus.COMPLETED,
                MessageStatus.COMPLETED
            )
            ediAdapterClient.statusRequests shouldBe externalRefIds
            ediAdapterClient.deleteRequests shouldBe deleteRequests
            for (externalRefId in externalRefIds) {
                val messageSnapshot = messageService.getMessageSnapshotByExternalRefId(externalRefId)!!
                messageSnapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
            }

            ediAdapterClient.deleteError = null
            val restarted = createNotificationService(ediAdapterClient, messageService, statusPublisher)
            restarted.processNotifications(this).join()

            statusPublisher.published.size shouldBe 2
            ediAdapterClient.statusRequests shouldBe externalRefIds
            ediAdapterClient.deleteRequests shouldBe deleteRequests + deleteRequests
        }

        "state persistence failure leaves the notification undeleted" {
            val (ediAdapterClient, messageService, statusPublisher) = fixture()
            val externalRefId = Uuid.random()
            val initial =
                messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK, Uuid.random())
            ediAdapterClient.notifications = flowOf(notification(externalRefId).right(), notification(null).right())
            val failure = IllegalStateException("State persistence failed")
            val failingMessageService = object : MessageStateService by messageService {
                override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot = throw failure
            }
            val notificationService = createNotificationService(
                ediAdapterClient,
                failingMessageService,
                statusPublisher
            )

            shouldThrow<IllegalStateException> {
                coroutineScope { notificationService.processNotifications(this).join() }
            } shouldBe failure

            statusPublisher.published.single().status shouldBe MessageStatus.COMPLETED
            messageService.getMessageSnapshotByExternalRefId(externalRefId) shouldBe initial
            ediAdapterClient.downloadedRequests shouldBe emptyList()
            ediAdapterClient.deleteRequests shouldBe emptyList()
        }

        "outgoing notifications refresh the referenced message" {
            for (type in listOf(
                MESSAGE_SENT_STATE_UPDATED,
                MESSAGE_APPREC_INFO_UPDATED,
                MESSAGE_DELIVERY_STATE_UPDATED
            )) {
                val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
                val externalRefId = Uuid.random()
                val snapshot =
                    messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
                ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
                ediAdapterClient.notifications = flowOf(notification(externalRefId, type).right())

                notificationService.processNotifications(this).join()

                ediAdapterClient.notificationRequests shouldBe listOf(listOf(senderHerId))
                ediAdapterClient.statusRequests shouldBe listOf(externalRefId)
                statusPublisher.published.single().messageId shouldBe snapshot.messageState.id
                statusPublisher.published.single().status shouldBe MessageStatus.COMPLETED
            }
        }

        "duplicate notifications do not publish the same transition twice" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, OK)
            val event = notification(externalRefId).right()
            ediAdapterClient.notifications = flowOf(event, event)

            notificationService.processNotifications(this).join()

            ediAdapterClient.notificationRequests shouldBe listOf(listOf(senderHerId))
            statusPublisher.published.size shouldBe 1
            ediAdapterClient.statusRequests shouldBe listOf(externalRefId)
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
                val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
                val externalRefId = Uuid.random()
                messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
                messageService.recordStateChange(UpdateState(externalRefId, DIALOG, null, transport, null, apprec))
                val before = messageService.getMessageSnapshotByExternalRefId(externalRefId)
                val notifications = List(2) { notification(externalRefId).copy(createdAt = fromEpochSeconds(0)) }
                ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, null)
                ediAdapterClient.notifications = notifications.map { it.right() }.asFlow()

                notificationService.processNotifications(this).join()

                ediAdapterClient.deleteRequests shouldBe notifications.map { DeleteNotificationsRequest(listOf(it.notificationId)) }
                ediAdapterClient.statusRequests shouldBe emptyList()
                statusPublisher.published shouldBe emptyList()
                messageService.getMessageSnapshotByExternalRefId(externalRefId) shouldBe before
            }
        }

        "terminal stream failures cancel other processes in the application scope" {
            val (ediAdapterClient, _, _, notificationService) = fixture()
            ediAdapterClient.notifications = flowOf(EdiAdapterError.Api(401).left())
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
                        notificationService.processNotifications(scope)
                        awaitCancellation()
                    }
                }
            }

            stopped.isCompleted shouldBe true
            ediAdapterClient.deleteRequests shouldBe emptyList()
            ediAdapterClient.statusRequests shouldBe emptyList()
        }

        "cancelling consumption closes the notification flow" {
            val (ediAdapterClient, _, _, notificationService) = fixture()
            val started = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            ediAdapterClient.notifications = flow {
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
            val (_, _, statusPublisher, notificationService) = fixture()

            notificationService.processNotifications(this).join()

            statusPublisher.published shouldBe emptyList()
        }

        "single dynamic receiver determines status and apprec errors in one request" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            val snapshot =
                messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatusList(
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
            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)
            val event = statusPublisher.published.single()

            event.status shouldBe MessageStatus.REJECTED_APPREC
            event.apprec!!.receiverHerId shouldBe 700

            val appRecErrorMessage = event.apprec.errorList.single()

            appRecErrorMessage.code shouldBe "E10"
            appRecErrorMessage.details shouldBe "Specific detail"
            appRecErrorMessage.description shouldBe "Description"
            appRecErrorMessage.oid shouldBe "oid"
            ediAdapterClient.statusRequests shouldBe listOf(externalRefId)
        }

        "missing or multiple receiver statuses leave state unchanged" {
            val status = StatusInfo(8142520, DeliveryState.ACKNOWLEDGED, true, ApprecInfo(OK))
            for (statuses in listOf(
                null,
                emptyList(),
                listOf(status, status.copy(receiverHerId = 999)),
                listOf(status, status)
            )) {
                val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
                val ref = Uuid.random()
                val initial = messageService.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
                ediAdapterClient.givenStatusList(ref, statuses)

                shouldThrow<NotificationProcessingException> {
                    coroutineScope { notificationService.processMessage(this, ediAdapterClient, initial.messageState) }
                }

                messageService.getMessageSnapshotByExternalRefId(ref) shouldBe initial
                ediAdapterClient.statusRequests shouldBe listOf(ref)
                ediAdapterClient.downloadedRequests shouldBe emptyList()
                ediAdapterClient.deleteRequests shouldBe emptyList()
                statusPublisher.published shouldBe emptyList()
            }
        }

        "abandoned transport preserves the NHN outcome and publishes a distinct error code" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val externalRefId = Uuid.random()
            val snapshot =
                messageService.createInitialState(CreateState(Uuid.random(), externalRefId, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ABANDONED, null)

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            val event = statusPublisher.published.single()
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.error!!.code shouldBe "TRANSPORT_ABANDONED"
            event.error.details shouldBe "Transport abandoned after failed sending attempts for messageId: ${snapshot.messageState.id}"
            event.apprec shouldBe null
            ediAdapterClient.downloadedRequests shouldBe emptyList()
            val stored = messageService.getMessageSnapshotByExternalRefId(externalRefId)!!
            stored.messageState.externalDeliveryState shouldBe ABANDONED
            stored.messageStateChanges.last().newDeliveryState shouldBe ABANDONED
        }

        "partial apprec acceptance completes delivery" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()
            val ref = Uuid.random()
            val initial = messageService.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()
            ediAdapterClient.givenStatus(ref, DeliveryState.ACKNOWLEDGED, ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART)

            notificationService.processMessage(this, ediAdapterClient, initial.messageState)

            statusPublisher.published.single().status shouldBe MessageStatus.COMPLETED
        }

        "no state change → no publish" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            val updatedSnapshot = messageService.recordStateChange(
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

            val current = messageService.getMessageSnapshotByExternalRefId(externalRefId)!!
            current.messageState.externalDeliveryState shouldBe ACKNOWLEDGED
            current.messageState.appRecStatus shouldBe null
            statusPublisher.published shouldBe emptyList()
        }

        "NEW → PENDING_TRANSPORT publishes status" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, null)

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusPublisher.published.size shouldBe 1

            val event = statusPublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe PENDING_TRANSPORT
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "NEW → PENDING_APPREC publishes status" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.ACKNOWLEDGED, null)

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusPublisher.published.size shouldBe 1

            val event = statusPublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe PENDING_APPREC
            event.apprec shouldBe null
            event.error shouldBe null
        }

        "PENDING_APPREC → COMPLETED publishes completed status with apprec payload" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            val pendingSnapshot = messageService.recordStateChange(
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

            statusPublisher.published.size shouldBe 1

            val event = statusPublisher.published.single()
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
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, DeliveryState.REJECTED, null)

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusPublisher.published.size shouldBe 1

            val event = statusPublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_TRANSPORT
            event.apprec shouldBe null
            event.error shouldNotBe null
            event.error!!.code shouldBe "TRANSPORT_REJECTED"
        }

        "apprec REJECTED publishes rejected apprec status with apprec payload" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            val pendingSnapshot = messageService.recordStateChange(
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

            statusPublisher.published.size shouldBe 1

            val event = statusPublisher.published.single()
            event.messageId shouldBe id
            event.status shouldBe MessageStatus.REJECTED_APPREC
            event.error shouldBe null
            event.apprec shouldNotBe null
            event.apprec!!.receiverHerId shouldBe 8142520
            event.apprec.status shouldBe REJECTED.toString()
        }

        "unresolvable external state → INVALID publishes invalid status" {
            val (ediAdapterClient, messageService, statusPublisher, notificationService) = fixture()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageService.createInitialState(
                CreateState(
                    id,
                    externalRefId,
                    DIALOG
                )
            )
                .shouldBeRight()

            ediAdapterClient.givenStatus(externalRefId, UNCONFIRMED, ExternalAppRecStatus.REJECTED)

            notificationService.processMessage(this, ediAdapterClient, snapshot.messageState)

            statusPublisher.published.size shouldBe 1

            val event = statusPublisher.published.single()
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
    val messageService: FakeTransactionalMessageStateService,
    val statusPublisher: FakeStatusMessagePublisher,
    val notificationService: NotificationService
)

private fun fixture(): Fixture {
    val ediAdapterClient = FakeEdiAdapterClient()
    val messageService = FakeTransactionalMessageStateService()
    val statusPublisher = FakeStatusMessagePublisher()

    return Fixture(
        ediAdapterClient = ediAdapterClient,
        messageService = messageService,
        statusPublisher = statusPublisher,
        notificationService = createNotificationService(
            ediAdapterClient,
            messageService,
            statusPublisher
        )
    )
}

private fun createNotificationService(
    ediAdapterClient: EdiAdapterClient,
    messageService: MessageStateService,
    messagePublisher: MessagePublisher
): NotificationService = NotificationService(
    ediAdapterClient,
    messageService,
    MessageStatusService(messageService, stateEvaluatorService(), messagePublisher)
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
    ediAdapterClient: FakeEdiAdapterClient,
    message: MessageState
) {
    ediAdapterClient.notifications = flowOf(notification(message.externalRefId).right())
    processNotifications(scope).join()
}

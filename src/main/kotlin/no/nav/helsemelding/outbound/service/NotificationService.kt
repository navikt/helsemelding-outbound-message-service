package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.v3.DeleteNotificationsRequest
import no.nav.helsemelding.ediadapter.model.v3.MarkAsDownloadedRequest
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.NEW_MESSAGE
import no.nav.helsemelding.ediadapter.model.v3.NotificationType.REFUSED_MESSAGE
import no.nav.helsemelding.ediadapter.model.v3.UnreadNotification
import no.nav.helsemelding.outbound.FetchStatusError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecPayload
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.formatExternal
import no.nav.helsemelding.outbound.model.logPrefix
import no.nav.helsemelding.outbound.util.translate
import no.nav.helsemelding.outbound.util.withSpan
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("NotificationService")

class NotificationService(
    private val ediAdapterClient: EdiAdapterClient,
    private val messageStateService: MessageStateService,
    private val messageStatusService: MessageStatusService
) {
    fun processNotifications(scope: CoroutineScope): Job {
        val senderHerId = config().ediAdapter.senderHerId.value
        log.info { "Starting unread notification stream for herId: $senderHerId" }
        return ediAdapterClient.streamUnreadNotifications(senderHerId)
            .onEach { either ->
                either
                    .onLeft { failure ->
                        throw NotificationProcessingException("Notification stream failed for herId: $senderHerId failure: $failure")
                    }
                    .onRight { notification -> processNotification(notification) }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)
    }

    private suspend fun processNotification(notification: UnreadNotification) {
        when (notification.type) {
            NEW_MESSAGE, REFUSED_MESSAGE -> Unit
            else -> {
                notification.relatedMessageId
                    ?.let { messageStateService.getMessageSnapshotByExternalRefId(it) }
                    ?.takeIf { it.messageState.transport.isEdi20() }
                    ?.takeUnless { messageStatusService.isTerminal(it.messageState) }
                    ?.run {
                        tracer.withSpan("Refresh message status") {
                            log.info {
                                "${messageState.logPrefix()} Processing notification with " +
                                    "id: ${notification.notificationId} and type: ${notification.type}"
                            }
                            fetchExternalStatus(messageState)
                                .onLeft { failure ->
                                    throw NotificationProcessingException("${messageState.logPrefix()} Failed fetching status: $failure")
                                }
                                .onRight { processStatus(messageState, it) }
                        }
                    }
                deleteNotification(notification.notificationId)
            }
        }
    }

    private suspend fun deleteNotification(notificationId: Uuid) {
        ediAdapterClient.deleteNotifications(DeleteNotificationsRequest(listOf(notificationId)))
            .onLeft { failure ->
                log.error { "Failed deleting notification with id: $notificationId failure: $failure" }
            }
            .onRight { log.info { "Successfully deleted notification with id: $notificationId" } }
    }

    private suspend fun processStatus(message: MessageState, external: ExternalStatus) {
        val event = try {
            messageStatusService.processStatus(message, external)
        } catch (error: StatusProcessingException) {
            throw NotificationProcessingException(error.message.orEmpty(), error)
        }
        if (event != null) markApprecMessageAsDownloaded(event.apprec, message)
    }

    private suspend fun fetchExternalStatus(message: MessageState): Either<FetchStatusError, ExternalStatus> = either {
        log.debug { "${message.logPrefix()} Fetching status from edi-adapter" }
        val response = ediAdapterClient.getMessageStatus(message.externalRefId)
            .mapLeft { FetchStatusError.FetchFailure(it) }
            .bind()
        val statuses = response.statusList.orEmpty()
            .also { log.debug { "${message.logPrefix()} Received ${it.size} statuses" } }
        ensure(statuses.size == 1) { FetchStatusError.UnexpectedReceiverCount(statuses.size) }

        statuses.single().translate()
            .also { log.debug { message.formatExternal(it.deliveryState, it.appRecStatus) } }
    }

    private suspend fun markApprecMessageAsDownloaded(apprecPayload: AppRecPayload?, message: MessageState) {
        if (apprecPayload?.id != null) {
            val senderHerId = config().ediAdapter.senderHerId.value
            ediAdapterClient.markMessageAsDownloaded(
                apprecPayload.id,
                MarkAsDownloadedRequest(senderHerId)
            )
                .onLeft { error ->
                    log.error { "${message.logPrefix()} Failed marking apprec with id: ${apprecPayload.id} as downloaded: $error" }
                }
                .onRight { log.info { "${message.logPrefix()} Marked apprec with id: ${apprecPayload.id} as downloaded" } }
        }
    }
}

internal class NotificationProcessingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

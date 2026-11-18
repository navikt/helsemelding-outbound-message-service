package no.nav.helsemelding.outbound.processor

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nav.helsemelding.outbound.EdiStatusError
import no.nav.helsemelding.outbound.model.AppRecPayload
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.EdiStatus
import no.nav.helsemelding.outbound.model.EdiStatusEvent
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.outbound.receiver.EdiMessageReceiver
import no.nav.helsemelding.outbound.service.MessageStateService
import no.nav.helsemelding.outbound.service.MessageStatusService
import no.nav.helsemelding.outbound.service.StatusProcessingException
import kotlin.uuid.Uuid

class EdiStatusProcessor(
    private val ediMessageReceiver: EdiMessageReceiver,
    private val messageStateService: MessageStateService,
    private val messageStatusService: MessageStatusService
) {
    fun processStatuses(scope: CoroutineScope): Job =
        ediMessageReceiver.receiveMessages()
            .onEach { message ->
                processStatus(message.messageId, message.status)
                    .onLeft { error -> throw StatusProcessingException("Failed processing EDI status: $error") }
                    .onRight { message.acknowledge() }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

    suspend fun processStatus(messageId: Uuid, status: EdiStatusEvent): Either<EdiStatusError, Unit> = either {
        val message = ensureNotNull(messageStateService.getMessageSnapshotById(messageId)) {
            EdiStatusError.MessageNotFound(messageId)
        }.messageState
        ensure(message.transport.isEdi10()) {
            EdiStatusError.UnexpectedTransport(messageId, message.transport)
        }
        messageStatusService.processStatus(message, status.toExternalStatus())
    }

    private fun EdiStatusEvent.toExternalStatus(): ExternalStatus =
        when (status) {
            EdiStatus.ACKNOWLEDGED -> toApplicationStatus(AppRecStatus.OK)
            EdiStatus.REJECTED -> toApplicationStatus(AppRecStatus.REJECTED)
            EdiStatus.REJECTED_TRANSPORT -> ExternalStatus(
                deliveryState = ExternalDeliveryState.REJECTED,
                appRecStatus = null
            )
        }

    private fun EdiStatusEvent.toApplicationStatus(appRecStatus: AppRecStatus): ExternalStatus =
        ExternalStatus(
            deliveryState = ExternalDeliveryState.ACKNOWLEDGED,
            appRecStatus = appRecStatus,
            apprec = AppRecPayload(
                receiverHerId = receiverHerId,
                status = appRecStatus.name,
                errorList = errorList
            )
        )
}

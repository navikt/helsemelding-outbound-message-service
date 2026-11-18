package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.v3.PostMessageRequest
import no.nav.helsemelding.messageconverter.MetadataExtractor
import no.nav.helsemelding.messageconverter.MsgHeadMessageConverter
import no.nav.helsemelding.messageconverter.model.MessageMetadata
import no.nav.helsemelding.outbound.EdiAdapterError.SendFailure
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.LifecycleError.EdiFailure
import no.nav.helsemelding.outbound.metrics.ErrorTypeTag
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.MessageTransport
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.publisher.EdiMessagePublisher
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

private const val BASE64_ENCODING = "base64"

interface MessageLifecycleService {
    /**
     * Routes and sends XML through the EDI adapter or Kafka, tracking it under [lifecycleId].
     * EDI 1.0 messages are registered before publication to allow immediate status responses.
     *
     * Returns an existing published snapshot without sending again. An EDI 1.0 message whose
     * publication was not confirmed may be republished with the same correlation id.
     * Sending and persistence are not atomic: concurrent registrations or retries after
     * a successful send followed by failed persistence may send the message again.
     */
    suspend fun registerOutgoingMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot>
}

class MessageLifecycleOrchestratorService(
    private val messageStateService: MessageStateService,
    private val ediAdapterClient: EdiAdapterClient,
    private val ediPublisher: EdiMessagePublisher,
    private val transportRouter: MessageTransportRouter,
    private val metrics: Metrics,
    private val metadataExtractor: MetadataExtractor = MsgHeadMessageConverter()
) : MessageLifecycleService {

    override suspend fun registerOutgoingMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot> = either {
        when (val existing = messageStateService.getMessageSnapshotById(lifecycleId)) {
            null -> registerNewMessage(lifecycleId, payload)
            else -> registerExistingMessage(existing, payload)
        }
    }
        .onLeft { reportFailure(lifecycleId, it) }

    private suspend fun Raise<LifecycleError>.registerExistingMessage(
        existing: MessageStateSnapshot,
        payload: ByteArray
    ): MessageStateSnapshot {
        val message = existing.messageState
        return when {
            message.needsEdiPublication() -> publishToEdi(message.id, payload)
            else -> existing.also { logExistingState(message.id) }
        }
    }

    private suspend fun Raise<LifecycleError>.registerNewMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): MessageStateSnapshot {
        val metadata = extractMetadata(lifecycleId, payload)
        return when (transportRouter.route(metadata.receiverHerIds)) {
            MessageTransport.EDI_1_0 -> registerEdiMessage(lifecycleId, payload)
            MessageTransport.EDI_2_0 -> registerEdiAdapterMessage(lifecycleId, metadata, payload)
        }
    }

    private suspend fun Raise<LifecycleError>.registerEdiMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): MessageStateSnapshot {
        initializeState(lifecycleId, lifecycleId, MessageTransport.EDI_1_0)
        return publishToEdi(lifecycleId, payload)
    }

    private suspend fun Raise<LifecycleError>.registerEdiAdapterMessage(
        lifecycleId: Uuid,
        metadata: MessageMetadata,
        payload: ByteArray
    ): MessageStateSnapshot {
        val externalRefId = sendMessage(lifecycleId, metadata.toRequest(payload))
        return initializeState(lifecycleId, externalRefId, MessageTransport.EDI_2_0)
    }

    private suspend fun Raise<LifecycleError>.publishToEdi(
        lifecycleId: Uuid,
        payload: ByteArray
    ): MessageStateSnapshot {
        ediPublisher
            .publish(lifecycleId, payload)
            .mapLeft { LifecycleError.EdiPublishFailure(lifecycleId, it) }
            .bind()

        messageStateService.markPublished(lifecycleId, Clock.System.now()).bind()
        log.info { "messageId=$lifecycleId Successfully published EDI 1.0 message" }
        return ensureNotNull(messageStateService.getMessageSnapshotById(lifecycleId)) {
            LifecycleError.MessageNotFound(lifecycleId)
        }
    }

    private fun Raise<LifecycleError>.extractMetadata(
        lifecycleId: Uuid,
        payload: ByteArray
    ): MessageMetadata =
        metadataExtractor.extractMetadata(payload.decodeToString())
            .mapLeft { LifecycleError.MetadataExtractionFailure(lifecycleId, it) }
            .bind()

    private fun MessageMetadata.toRequest(payload: ByteArray): PostMessageRequest =
        PostMessageRequest(
            businessDocument = Base64.encode(payload),
            contentType = ContentType.Application.Xml.toString(),
            contentTransferEncoding = BASE64_ENCODING,
            senderHerId = senderHerId,
            receiverHerIds = receiverHerIds,
            messageTypeIdentificator = messageTypeIdentificator
        )

    private suspend fun Raise<LifecycleError>.sendMessage(
        lifecycleId: Uuid,
        request: PostMessageRequest
    ): Uuid {
        val (response, duration) = measureTimedValue {
            ediAdapterClient.postMessage(request)
                .mapLeft { EdiFailure(SendFailure(lifecycleId, it)) }
                .bind()
        }
        metrics.registerPostMessageDuration(duration.inWholeNanoseconds)

        return ensureNotNull(response.id) {
            LifecycleError.MissingExternalReferenceId(lifecycleId)
        }
            .also { logMessageSent(lifecycleId, it) }
    }

    private suspend fun Raise<LifecycleError>.initializeState(
        lifecycleId: Uuid,
        externalRefId: Uuid,
        transport: MessageTransport
    ): MessageStateSnapshot =
        messageStateService.createInitialState(
            CreateState(
                id = lifecycleId,
                externalRefId = externalRefId,
                messageType = DIALOG,
                transport = transport,
                publishedAt = if (transport.isEdi20()) Clock.System.now() else null
            )
        )
            .bind()
            .also { logStateInitialized(lifecycleId, externalRefId) }

    private fun MessageState.needsEdiPublication(): Boolean =
        transport.isEdi10() && publishedAt == null && externalDeliveryState == null && appRecStatus == null

    private fun logExistingState(lifecycleId: Uuid) {
        log.debug { "messageId=$lifecycleId Returning existing lifecycle state" }
    }

    private fun logMessageSent(lifecycleId: Uuid, externalRefId: Uuid) {
        log.info { "messageId=$lifecycleId externalRefId=$externalRefId Successfully sent message to edi adapter" }
    }

    private fun logStateInitialized(lifecycleId: Uuid, externalRefId: Uuid) {
        log.info { "messageId=$lifecycleId externalRefId=$externalRefId State initialized" }
    }

    private fun reportFailure(lifecycleId: Uuid, error: LifecycleError) {
        val errorType = when (error) {
            is LifecycleError.MetadataExtractionFailure -> ErrorTypeTag.METADATA_EXTRACTION_FAILED
            is EdiFailure -> ErrorTypeTag.SENDING_TO_EDI_ADAPTER_FAILED
            is LifecycleError.EdiPublishFailure -> ErrorTypeTag.SENDING_TO_EDI_FAILED
            is LifecycleError.MissingExternalReferenceId -> ErrorTypeTag.EXTERNAL_REFERENCE_VALIDATION_FAILED
            is LifecycleError.MessageNotFound -> ErrorTypeTag.STATE_LOOKUP_FAILED
            is LifecycleError.Conflict, is LifecycleError.PersistenceFailure -> ErrorTypeTag.STATE_INITIALIZATION_FAILED
        }
        log.error { "messageId=$lifecycleId Failed registering message ($errorType): $error" }
        metrics.registerOutgoingMessageFailed(errorType)
    }
}

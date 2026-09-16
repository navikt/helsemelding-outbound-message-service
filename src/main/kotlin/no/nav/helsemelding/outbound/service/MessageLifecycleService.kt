package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
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
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import kotlin.io.encoding.Base64
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

private const val BASE64_ENCODING = "base64"

interface MessageLifecycleService {
    /**
     * Extracts metadata from [payload], sends the XML through the EDI adapter,
     * and stores the initial lifecycle state under [lifecycleId].
     *
     * Returns an existing snapshot without sending again when [lifecycleId] is already stored.
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
    private val metrics: Metrics,
    private val metadataExtractor: MetadataExtractor = MsgHeadMessageConverter()
) : MessageLifecycleService {

    override suspend fun registerOutgoingMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot> =
        messageStateService.getMessageSnapshotById(lifecycleId)
            ?.also { logExistingState(lifecycleId) }
            ?.right()
            ?: registerNewMessage(lifecycleId, payload)

    private suspend fun registerNewMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot> = either {
        val metadata = extractMetadata(lifecycleId, payload)
        val externalRefId = sendMessage(lifecycleId, metadata.toRequest(payload))
        initializeState(lifecycleId, externalRefId)
    }
        .onLeft { reportFailure(lifecycleId, it) }

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

        val responseId = ensureNotNull(response.id) {
            LifecycleError.MissingExternalReferenceId(lifecycleId)
        }
        return ensureNotNull(Uuid.parseOrNull(responseId)) {
            LifecycleError.InvalidExternalReferenceId(lifecycleId, responseId)
        }
            .also { logMessageSent(lifecycleId, it) }
    }

    private suspend fun Raise<LifecycleError>.initializeState(
        lifecycleId: Uuid,
        externalRefId: Uuid
    ): MessageStateSnapshot =
        messageStateService.createInitialState(
            CreateState(
                id = lifecycleId,
                externalRefId = externalRefId,
                messageType = DIALOG
            )
        )
            .bind()
            .also { logStateInitialized(lifecycleId, externalRefId) }

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
            is LifecycleError.MissingExternalReferenceId,
            is LifecycleError.InvalidExternalReferenceId -> ErrorTypeTag.EXTERNAL_REFERENCE_VALIDATION_FAILED
            is LifecycleError.Conflict, is LifecycleError.PersistenceFailure -> ErrorTypeTag.STATE_INITIALIZATION_FAILED
        }
        log.error { "messageId=$lifecycleId Failed registering message ($errorType): $error" }
        metrics.registerOutgoingMessageFailed(errorType)
    }
}

package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.ediadapter.model.PostMessageRequest
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.LifecycleError.ConflictingMessageId
import no.nav.helsemelding.outbound.LifecycleError.SendMessageFailure
import no.nav.helsemelding.outbound.LifecycleError.SigningFailure
import no.nav.helsemelding.outbound.metrics.ErrorTypeTag
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.payloadsigning.client.PayloadSigningClient
import no.nav.helsemelding.payloadsigning.model.Direction.OUT
import no.nav.helsemelding.payloadsigning.model.PayloadRequest
import no.nav.helsemelding.payloadsigning.model.PayloadResponse
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.system.measureNanoTime
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

const val BASE64_ENCODING = "base64"

interface MessageLifecycleService {
    suspend fun registerOutgoingMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot>
}

class MessageLifecycleOrchestratorService(
    private val messageStateService: MessageStateService,
    private val ediAdapterClient: EdiAdapterClient,
    private val payloadSigningClient: PayloadSigningClient,
    private val metrics: Metrics
) : MessageLifecycleService {

    override suspend fun registerOutgoingMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot> {
        return messageStateService
            .getMessageSnapshotById(lifecycleId)
            ?.let { Left(ConflictingMessageId(lifecycleId, "Id has already been registered")) }
            ?: registerNewMessage(lifecycleId, payload)
    }

    private suspend fun registerNewMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, MessageStateSnapshot> = either {
        val signedXml = signXml(lifecycleId, payload).bind()
        val metadata = sendMessage(lifecycleId, signedXml).bind()
        initializeState(metadata, lifecycleId).bind()
    }

    private suspend fun signXml(
        lifecycleId: Uuid,
        payload: ByteArray
    ): Either<LifecycleError, ByteArray> = either {
        log.info { "messageId=$lifecycleId Processing started" }

        var payloadResponse: PayloadResponse
        val durationNanos = measureNanoTime {
            payloadResponse = payloadSigningClient.signPayload(PayloadRequest(OUT, payload)).bind()
        }
        metrics.registerMessageSigningDuration(durationNanos)

        log.info { "messageId=$lifecycleId Successfully signed" }
        payloadResponse.bytes
    }
        .mapLeft { messageSigningError ->
            SigningFailure(lifecycleId, messageSigningError.message)
        }
        .onLeft { messageSigningError ->
            log.error { "messageId=$lifecycleId Failed signing message: $messageSigningError" }
            metrics.registerOutgoingMessageFailed(ErrorTypeTag.PAYLOAD_SIGNING_FAILED)
        }

    private suspend fun sendMessage(lifecycleId: Uuid, payload: ByteArray): Either<LifecycleError, Metadata> = either {
        val postMessageRequest = PostMessageRequest(
            businessDocument = Base64.encode(payload),
            contentType = ContentType.Application.Xml.toString(),
            contentTransferEncoding = BASE64_ENCODING
        )

        var metadata: Metadata
        val durationNanos = measureNanoTime {
            metadata = ediAdapterClient.postMessage(postMessageRequest).bind()
        }
        metrics.registerPostMessageDuration(durationNanos)
        val externalRefId = metadata.id
        log.info {
            "externalRefId=$externalRefId Successfully sent message (messageId=$lifecycleId) to edi adapter"
        }
        metadata
    }
        .mapLeft { errorMessage ->
            SendMessageFailure(lifecycleId, errorMessage.error ?: "Calling edi adapter failed")
        }
        .onLeft { error ->
            log.error { "messageId=$lifecycleId Failed sending message to edi adapter: $error" }
            metrics.registerOutgoingMessageFailed(ErrorTypeTag.SENDING_TO_EDI_ADAPTER_FAILED)
        }

    private suspend fun initializeState(
        metadata: Metadata,
        messageId: Uuid
    ): Either<LifecycleError, MessageStateSnapshot> = either {
        val snapshot = messageStateService.createInitialState(
            CreateState(
                id = messageId,
                externalRefId = metadata.id,
                messageType = DIALOG,
                externalMessageUrl = URI.create(metadata.location).toURL()
            )
        )
            .bind()
        val externalRefId = snapshot.messageState.externalRefId
        log.info { "externalRefId=$externalRefId State initialized (messageId=$messageId)" }
        snapshot
    }
        .onLeft { lifecycleError ->
            log.error { "messageId=$messageId Failed initializing state: $lifecycleError" }
            metrics.registerOutgoingMessageFailed(ErrorTypeTag.STATE_INITIALIZATION_FAILED)
        }
}

package no.nav.helsemelding.outbound.service

import arrow.core.Either
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.opentelemetry.api.GlobalOpenTelemetry
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.ediadapter.model.PostMessageRequest
import no.nav.helsemelding.outbound.metrics.ErrorTypeTag
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.payloadsigning.client.PayloadSigningClient
import no.nav.helsemelding.payloadsigning.model.Direction.OUT
import no.nav.helsemelding.payloadsigning.model.MessageSigningError
import no.nav.helsemelding.payloadsigning.model.PayloadRequest
import no.nav.helsemelding.payloadsigning.model.PayloadResponse
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.system.measureNanoTime
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("MessageProcessor")

const val BASE64_ENCODING = "base64"

interface MessageLifecycleService {
    suspend fun registerOutgoingMessage(lifecycleId: Uuid, payload: ByteArray): MessageStateSnapshot?
}

// TODO: Rename?
class ExposedMessageLifecycleService(
    private val messageStateService: MessageStateService,
    private val ediAdapterClient: EdiAdapterClient,
    private val payloadSigningClient: PayloadSigningClient,
    private val metrics: Metrics
) : MessageLifecycleService {

    // TODO: Fix return type
    override suspend fun registerOutgoingMessage(lifecycleId: Uuid, payload: ByteArray): MessageStateSnapshot? {
        return messageStateService
            .getMessageSnapshot(lifecycleId)
            ?.withLogging()
            ?: processAndSendMessage(lifecycleId, payload).let {
                messageStateService.getMessageSnapshot(lifecycleId)
            }
    }

    private fun MessageStateSnapshot.withLogging(): MessageStateSnapshot = also {
        log.info { "messageId=${this.messageState.id} already registered" }
    }

    // TODO: Test registerIncomingMessage instead?
    internal suspend fun processAndSendMessage(
        lifecycleId: Uuid,
        payload: ByteArray
    ) {
        log.info { "messageId=${lifecycleId} Processing started" }

        var result: Either<MessageSigningError, PayloadResponse>
        val durationNanos = measureNanoTime {
            result = payloadSigningClient.signPayload(PayloadRequest(OUT, payload))
        }
        metrics.registerMessageSigningDuration(durationNanos)

        result
            .onRight { payloadResponse ->
                log.info { "messageId=${lifecycleId} Successfully signed" }
                val signedXml = payloadResponse.bytes
                postMessage(lifecycleId, signedXml)
            }
            .onLeft { error ->
                log.error {
                    "messageId=${lifecycleId} Failed signing message: $error"
                }
                metrics.registerOutgoingMessageFailed(ErrorTypeTag.PAYLOAD_SIGNING_FAILED)
            }
    }

    private suspend fun postMessage(lifecycleId: Uuid, payload: ByteArray) {
        val postMessageRequest = PostMessageRequest(
            businessDocument = Base64.encode(payload),
            contentType = ContentType.Application.Xml.toString(),
            contentTransferEncoding = BASE64_ENCODING
        )

        var result: Either<ErrorMessage, Metadata>
        val durationNanos = measureNanoTime {
            result = ediAdapterClient.postMessage(postMessageRequest)
        }
        metrics.registerPostMessageDuration(durationNanos)

        result
            .onRight { metadata ->
                val externalRefId = metadata.id
                log.info {
                    "externalRefId=$externalRefId Successfully sent message (messageId=${lifecycleId}) to edi adapter"
                }
                initializeState(metadata, lifecycleId)
            }
            .onLeft { error ->
                log.error {
                    "messageId=${lifecycleId} Failed sending message to edi adapter: $error"
                }
                metrics.registerOutgoingMessageFailed(ErrorTypeTag.SENDING_TO_EDI_ADAPTER_FAILED)
            }
    }

    private suspend fun initializeState(metadata: Metadata, messageId: Uuid) = either {
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
        log.info {
            "externalRefId=$externalRefId State initialized (messageId=$messageId)"
        }
    }
        .onLeft { error ->
            log.error {
                "messageId=$messageId Failed initializing state: $error"
            }
            metrics.registerOutgoingMessageFailed(ErrorTypeTag.STATE_INITIALIZATION_FAILED)
        }
}

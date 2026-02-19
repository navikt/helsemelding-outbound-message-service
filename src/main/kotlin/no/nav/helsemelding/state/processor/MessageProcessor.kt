package no.nav.helsemelding.state.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.ediadapter.model.PostMessageRequest
import no.nav.helsemelding.payloadsigning.client.PayloadSigningClient
import no.nav.helsemelding.payloadsigning.model.Direction.OUT
import no.nav.helsemelding.payloadsigning.model.PayloadRequest
import no.nav.helsemelding.state.metrics.ErrorTypeTag
import no.nav.helsemelding.state.metrics.Metrics
import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.DialogMessage
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.receiver.MessageReceiver
import no.nav.helsemelding.state.service.MessageStateService
import no.nav.helsemelding.state.util.withSpan
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.system.measureNanoTime
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("MessageProcessor")

const val BASE64_ENCODING = "base64"

class MessageProcessor(
    private val messageReceiver: MessageReceiver,
    private val messageStateService: MessageStateService,
    private val ediAdapterClient: EdiAdapterClient,
    private val payloadSigningClient: PayloadSigningClient,
    private val metrics: Metrics
) {
    fun processMessages(scope: CoroutineScope): Job =
        messageFlow()
            .onEach { message -> processAndSendMessage(message) }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

    private fun messageFlow(): Flow<DialogMessage> = messageReceiver.receiveMessages()

    internal suspend fun processAndSendMessage(dialogMessage: DialogMessage) {
        tracer.withSpan("Process and send message") {
            log.info { "dialogMessageId=${dialogMessage.id} Processing started" }
            payloadSigningClient.signPayload(PayloadRequest(OUT, dialogMessage.payload))
                .onRight { payloadResponse ->
                    log.info { "dialogMessageId=${dialogMessage.id} Successfully signed" }
                    val signedXml = payloadResponse.bytes
                    postMessage(dialogMessage.copy(payload = signedXml))
                }
                .onLeft { error ->
                    log.error {
                        "dialogMessageId=${dialogMessage.id} Failed signing message: $error"
                    }
                    metrics.registerOutgoingMessageFailed(ErrorTypeTag.PAYLOAD_SIGNING_FAILED)
                }
        }
    }

    private suspend fun postMessage(dialogMessage: DialogMessage) {
        val postMessageRequest = PostMessageRequest(
            businessDocument = Base64.encode(dialogMessage.payload),
            contentType = ContentType.Application.Xml.toString(),
            contentTransferEncoding = BASE64_ENCODING
        )

        val durationNanos = measureNanoTime {
            ediAdapterClient.postMessage(postMessageRequest)
                .onRight { metadata ->
                    val externalRefId = metadata.id
                    log.info {
                        "externalRefId=$externalRefId Successfully sent dialog message (dialogMessageId=${dialogMessage.id}) to edi adapter"
                    }
                    initializeState(metadata, dialogMessage.id)
                }
                .onLeft { error ->
                    log.error {
                        "dialogMessageId=${dialogMessage.id} Failed sending message to edi adapter: $error"
                    }
                    metrics.registerOutgoingMessageFailed(ErrorTypeTag.SENDING_TO_EDI_ADAPTER_FAILED)
                }
        }

        metrics.registerPostMessageDuration(durationNanos)
    }

    private suspend fun initializeState(metadata: Metadata, dialogMessageId: Uuid) {
        try {
            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id = dialogMessageId,
                    externalRefId = metadata.id,
                    messageType = DIALOG,
                    externalMessageUrl = URI.create(metadata.location).toURL()
                )
            )

            val externalRefId = snapshot.messageState.externalRefId

            log.info {
                "externalRefId=$externalRefId State initialized (dialogMessageId=$dialogMessageId)"
            }
        } catch (error: Throwable) {
            log.error {
                "dialogMessageId=$dialogMessageId Failed initializing state: ${error.stackTraceToString()}"
            }
            metrics.registerOutgoingMessageFailed(ErrorTypeTag.STATE_INITIALIZATION_FAILED)
        }
    }
}

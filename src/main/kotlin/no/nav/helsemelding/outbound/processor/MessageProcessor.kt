package no.nav.helsemelding.outbound.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nav.helsemelding.jsonschema.core.model.ErrorCategory
import no.nav.helsemelding.jsonschema.core.model.ErrorCode
import no.nav.helsemelding.jsonschema.core.model.ErrorMessage
import no.nav.helsemelding.jsonschema.core.model.ProcessingError
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.DialogMessage
import no.nav.helsemelding.outbound.model.logPrefix
import no.nav.helsemelding.outbound.publisher.ErrorMessagePublisher
import no.nav.helsemelding.outbound.receiver.MessageReceiver
import no.nav.helsemelding.outbound.service.MessageLifecycleService
import no.nav.helsemelding.outbound.util.withSpan
import kotlin.system.measureNanoTime
import kotlin.time.Clock

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("MessageProcessor")

class MessageProcessor(
    private val messageReceiver: MessageReceiver,
    private val messageLifecycleService: MessageLifecycleService,
    private val metrics: Metrics,
    private val errorMessagePublisher: ErrorMessagePublisher
) {
    fun processMessages(scope: CoroutineScope): Job =
        messageFlow()
            .onEach { metrics.registerOutgoingMessageReceived() }
            .onEach { message ->
                val durationNanos = measureNanoTime {
                    processMessage(message)
                }
                metrics.registerOutgoingMessageProcessingDuration(durationNanos)
            }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

    private fun messageFlow(): Flow<DialogMessage> = messageReceiver.receiveMessages()

    internal suspend fun processMessage(dialogMessage: DialogMessage) {
        tracer.withSpan("Process and send message") {
            messageLifecycleService.registerOutgoingMessage(dialogMessage.id, dialogMessage.payload)
                .onLeft { error ->
                    log.error { "Registering message failed: messageId=${dialogMessage.id}, error=$error" }

                    if (error is LifecycleError.MetadataExtractionFailure) {
                        publishMetadataExtractionError(dialogMessage)
                    }
                }
                .onRight { snapshot ->
                    log.info {
                        "${snapshot.messageState.logPrefix()} Registering message succeeded: messageId=${dialogMessage.id}"
                    }
                }
        }
    }

    private suspend fun publishMetadataExtractionError(dialogMessage: DialogMessage) {
        val errorMessage = ErrorMessage(
            version = 1,
            processedAt = Clock.System.now(),
            sourceSystem = dialogMessage.sourceSystem,
            messageId = dialogMessage.id,
            errors = listOf(
                ProcessingError(
                    category = ErrorCategory.DELIVERY,
                    code = ErrorCode.METADATA_EXTRACTION_ERROR,
                    message = "Unable to extract message metadata"
                )
            ),
            originalMessage = null
        )

        errorMessagePublisher.publish(errorMessage)
            .onLeft { error ->
                log.error { "Published metadata extraction error has failed: messageId=${dialogMessage.id}" }
                when (error) {
                    is PublishError.Failure -> throw error.cause
                }
            }
            .onRight { log.info { "Published metadata extraction error: messageId=${dialogMessage.id}" } }
    }
}

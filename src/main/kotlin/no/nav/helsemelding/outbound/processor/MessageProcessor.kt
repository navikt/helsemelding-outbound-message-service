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
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.DialogMessage
import no.nav.helsemelding.outbound.receiver.MessageReceiver
import no.nav.helsemelding.outbound.service.MessageLifecycleService
import no.nav.helsemelding.outbound.util.withSpan
import kotlin.system.measureNanoTime

private val log = KotlinLogging.logger {}
private val tracer = GlobalOpenTelemetry.getTracer("MessageProcessor")

class MessageProcessor(
    private val messageReceiver: MessageReceiver,
    private val messageLifecycleService: MessageLifecycleService,
    private val metrics: Metrics
) {
    fun processMessages(scope: CoroutineScope): Job =
        messageFlow()
            .onEach { message ->
                val durationNanos = measureNanoTime {
                    processAndSendMessage(message)
                }
                metrics.registerOutgoingMessageProcessingDuration(durationNanos)
            }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

    private fun messageFlow(): Flow<DialogMessage> = messageReceiver.receiveMessages()

    internal suspend fun processAndSendMessage(dialogMessage: DialogMessage) {
        tracer.withSpan("Process and send message") {
            messageLifecycleService.registerOutgoingMessage(dialogMessage.id, dialogMessage.payload)
            // TODO: Log?
        }
    }
}

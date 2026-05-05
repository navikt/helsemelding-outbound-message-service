package no.nav.helsemelding.outbound.handler

import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.helsemelding.outbound.metrics.ErrorTypeTag
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.ErrorCategory
import no.nav.helsemelding.outbound.model.ErrorCode
import no.nav.helsemelding.outbound.model.ErrorInfo
import no.nav.helsemelding.outbound.model.MessageErrorEvent
import no.nav.helsemelding.outbound.model.MessageRecord
import no.nav.helsemelding.outbound.model.OriginalMessage
import no.nav.helsemelding.outbound.publisher.ErrorMessagePublisher
import no.nav.helsemelding.outbound.receiver.RecordKeyValidation
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

interface MessageHandler {
    suspend fun handleInvalidKafkaKey(
        record: MessageRecord,
        validation: RecordKeyValidation.Invalid
    )
}

class MessageErrorHandler(
    private val metrics: Metrics,
    private val errorMessagePublisher: ErrorMessagePublisher
) : MessageHandler {

    override suspend fun handleInvalidKafkaKey(
        record: MessageRecord,
        validation: RecordKeyValidation.Invalid
    ) {
        log.error {
            "Invalid Kafka key. Key: ${record.key}, offset: ${record.offset}. Reason: ${validation.reason}"
        }

        metrics.registerOutgoingMessageFailed(ErrorTypeTag.INVALID_KAFKA_KEY)

        errorMessagePublisher.publish(
            Uuid.random(),
            toMessageErrorEvent(validation, record)
        )
    }

    private fun toMessageErrorEvent(
        validation: RecordKeyValidation.Invalid,
        record: MessageRecord
    ): MessageErrorEvent =
        MessageErrorEvent(
            processedAt = Clock.System.now(),
            error = ErrorInfo(
                category = ErrorCategory.VALIDATION,
                code = ErrorCode.INVALID_KAFKA_KEY,
                message = validation.reason
            ),
            originalMessage = OriginalMessage(
                key = record.key,
                payload = record.payload.decodeToString()
            )
        )
}

class FakeMessageHandler : MessageHandler {
    val invalidKafkaKeys = mutableListOf<InvalidKafkaKey>()

    override suspend fun handleInvalidKafkaKey(
        record: MessageRecord,
        validation: RecordKeyValidation.Invalid
    ) {
        invalidKafkaKeys += InvalidKafkaKey(record, validation)
    }

    data class InvalidKafkaKey(
        val record: MessageRecord,
        val validation: RecordKeyValidation.Invalid
    )
}

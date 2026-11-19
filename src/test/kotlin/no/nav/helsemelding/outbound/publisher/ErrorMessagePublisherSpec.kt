package no.nav.helsemelding.outbound.publisher

import app.cash.turbine.test
import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import no.nav.helsemelding.jsonschema.core.model.ErrorCategory
import no.nav.helsemelding.jsonschema.core.model.ErrorCode
import no.nav.helsemelding.jsonschema.core.model.ErrorMessage
import no.nav.helsemelding.jsonschema.core.model.ProcessingError
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ErrorMessagePublisherSpec : KafkaSpec({
    "should publish ErrorMessage to error topic" {
        resourceScope {
            val topics = config().kafka.topics
            val publisher = KafkaErrorMessagePublisher(topics, kafkaPublisher())
            val error = buildErrorMessage()

            publisher.publish(error).shouldBeRight().topic() shouldBe topics.errorMessage
            kafkaReceiver()
                .receive(topics.errorMessage)
                .test(timeout = kafkaTimeout) {
                    val record = awaitItem()
                    record.key() shouldBe null
                    Json.decodeFromString<ErrorMessage>(record.value().decodeToString()) shouldBe error
                }
        }
    }
})

private fun buildErrorMessage(): ErrorMessage {
    return ErrorMessage(
        version = 1,
        processedAt = Instant.parse("2026-11-06T10:00:00Z"),
        sourceSystem = "test_system",
        messageId = Uuid.random(),
        errors = listOf(
            ProcessingError(
                ErrorCategory.DELIVERY,
                ErrorCode.METADATA_EXTRACTION_ERROR,
                "Unable to extract message metadata"
            )
        ),
        originalMessage = null
    )
}

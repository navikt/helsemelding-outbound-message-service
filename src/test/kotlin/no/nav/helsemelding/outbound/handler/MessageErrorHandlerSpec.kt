package no.nav.helsemelding.outbound.handler

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.metrics.FakeMetrics
import no.nav.helsemelding.outbound.model.ErrorCategory
import no.nav.helsemelding.outbound.model.ErrorCode
import no.nav.helsemelding.outbound.model.ErrorInfo
import no.nav.helsemelding.outbound.model.MessageRecord
import no.nav.helsemelding.outbound.model.OriginalMessage
import no.nav.helsemelding.outbound.publisher.FakeErrorMessagePublisher
import no.nav.helsemelding.outbound.receiver.RecordKeyValidation
import kotlin.time.Clock

class MessageErrorHandlerSpec : StringSpec(
    {
        lateinit var metrics: FakeMetrics
        lateinit var publisher: FakeErrorMessagePublisher
        lateinit var handler: MessageErrorHandler

        beforeTest {
            metrics = FakeMetrics()
            publisher = FakeErrorMessagePublisher()
            handler = MessageErrorHandler(
                metrics = metrics,
                errorMessagePublisher = publisher
            )
        }

        "should publish message error event for invalid kafka key" {
            val record = MessageRecord(
                key = "not-a-uuid",
                payload = """{"foo":"bar"}""".encodeToByteArray(),
                offset = 42L,
                createdAt = Clock.System.now().epochSeconds
            )

            val validation = RecordKeyValidation.Invalid(
                reason = "Kafka record key is not a valid UUID"
            )

            handler.handleInvalidKafkaKey(record, validation)

            publisher.published shouldHaveSize 1

            val (_, event) = publisher.published.single()

            event.error shouldBe ErrorInfo(
                category = ErrorCategory.VALIDATION,
                code = ErrorCode.INVALID_KAFKA_KEY,
                message = "Kafka record key is not a valid UUID"
            )

            event.originalMessage shouldBe OriginalMessage(
                createdAt = record.createdAt,
                key = "not-a-uuid",
                payload = """{"foo":"bar"}"""
            )
        }

        "should include null key in original message when kafka key is missing" {
            val record = MessageRecord(
                key = null,
                payload = """{"foo":"bar"}""".encodeToByteArray(),
                offset = 42L,
                createdAt = Clock.System.now().epochSeconds
            )

            val validation = RecordKeyValidation.Invalid(
                reason = "Kafka record key is null"
            )

            handler.handleInvalidKafkaKey(record, validation)

            publisher.published shouldHaveSize 1

            val (_, event) = publisher.published.single()

            event.originalMessage shouldBe OriginalMessage(
                createdAt = record.createdAt,
                key = null,
                payload = """{"foo":"bar"}"""
            )

            event.error.message shouldBe "Kafka record key is null"
        }
    }
)

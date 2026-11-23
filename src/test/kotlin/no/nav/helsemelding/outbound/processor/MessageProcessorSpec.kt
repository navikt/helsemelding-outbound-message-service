package no.nav.helsemelding.outbound.processor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.jsonschema.core.model.ErrorCategory
import no.nav.helsemelding.jsonschema.core.model.ErrorCode
import no.nav.helsemelding.jsonschema.core.model.ErrorMessage
import no.nav.helsemelding.outbound.EdiAdapterError.SendFailure
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.FakeEdiMessagePublisher
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.MessageProcessingError
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.metrics.FakeMetrics
import no.nav.helsemelding.outbound.model.DialogMessage
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.publisher.ErrorMessagePublisher
import no.nav.helsemelding.outbound.receiver.MessageReceiver
import no.nav.helsemelding.outbound.service.FakeTransactionalMessageStateService
import no.nav.helsemelding.outbound.service.MessageLifecycleOrchestratorService
import no.nav.helsemelding.outbound.service.MessageLifecycleService
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import kotlin.time.Clock
import kotlin.uuid.Uuid

class MessageProcessorSpec : StringSpec({
    val kafka = config().kafka
    val receiver = MessageReceiver(
        kafka.topics.dialogMessageOut,
        KafkaReceiver(kafka.toReceiverSettings(kafka, AutoOffsetReset.Latest))
    )
    lateinit var stateService: FakeTransactionalMessageStateService
    lateinit var adapter: FakeEdiAdapterClient
    lateinit var lifecycle: MessageLifecycleService
    lateinit var publisher: RecordingErrorMessagePublisher

    beforeEach {
        stateService = FakeTransactionalMessageStateService()
        adapter = FakeEdiAdapterClient()
        lifecycle = MessageLifecycleOrchestratorService(
            messageStateService = stateService,
            ediAdapterClient = adapter,
            ediPublisher = FakeEdiMessagePublisher(),
            metrics = FakeMetrics()
        )
        publisher = RecordingErrorMessagePublisher()
    }

    fun processor(service: MessageLifecycleService = lifecycle) =
        MessageProcessor(receiver, service, FakeMetrics(), publisher)

    "should send ErrorMessage to Kafka if extracting metadata fails" {
        val messageId = Uuid.random()
        val input = DialogMessage(
            messageId,
            "<invalid".encodeToByteArray(),
            "test-system"
        )
        val before = Clock.System.now()
        processor().processMessage(input).shouldBeRight()
        val after = Clock.System.now()

        val error = publisher.published.single()
        error.version shouldBe 1
        (error.processedAt in before..after) shouldBe true
        error.messageId shouldBe messageId
        error.sourceSystem shouldBe "test-system"
        error.originalMessage.shouldBeNull()
        error.errors.single().category shouldBe ErrorCategory.DELIVERY
        error.errors.single().code shouldBe ErrorCode.METADATA_EXTRACTION_ERROR
        error.errors.single().message shouldBe "Unable to extract message metadata"
        Json.encodeToString(error).contains("<invalid") shouldBe false
        adapter.sentMessages shouldBe emptyList()
        stateService.getMessageSnapshotById(messageId).shouldBeNull()
    }

    "should publish an error for an unknown EDI version" {
        val messageId = Uuid.random()
        val message = DialogMessage(messageId, validXml.encodeToByteArray(), "test-system", ediVersion = "EDI_3.0")

        processor().processMessage(message).shouldBeRight()

        val error = publisher.published.single()
        error.messageId shouldBe messageId
        error.sourceSystem shouldBe "test-system"
        error.errors.single().code shouldBe ErrorCode.INVALID_KAFKA_VALUE
        error.errors.single().message shouldBe "Invalid ediVersion header. Expected EDI_1.0 or EDI_2.0."
        adapter.sentMessages shouldBe emptyList()
        stateService.getMessageSnapshotById(messageId).shouldBeNull()
    }

    "should send ErrorMessage for adapter and Edi send failures" {
        val messageId = Uuid.random()
        val failures = listOf(
            LifecycleError.EdiFailure(SendFailure(messageId, EdiAdapterError.Api(503))),
            LifecycleError.EdiPublishFailure(
                messageId,
                PublishError.Failure(
                    messageId,
                    config().edi.dialogMessageOutTopic,
                    IllegalStateException("broker unavailable")
                )
            )
        )
        for (failure in failures) {
            publisher.published.clear()
            val before = Clock.System.now()
            processor(service = failingLifecycle(failure))
                .processMessage(DialogMessage(messageId, validXml.encodeToByteArray(), "test-system"))
                .shouldBeRight()
            val after = Clock.System.now()

            val error = publisher.published.single()
            error.version shouldBe 1
            (error.processedAt in before..after) shouldBe true
            error.messageId shouldBe messageId
            error.sourceSystem shouldBe "test-system"
            error.originalMessage.shouldBeNull()
            error.errors.single().category shouldBe ErrorCategory.DELIVERY
            error.errors.single().code shouldBe ErrorCode.SEND_ERROR
            error.errors.single().message shouldBe "Unable to send message"
            Json.encodeToString(error).contains("MsgHead") shouldBe false
            stateService.getMessageSnapshotById(messageId).shouldBeNull()
        }
    }

    "should return lifecycle failures without publishing ErrorMessage" {
        val messageId = Uuid.random()
        for (error in unhandledLifecycleErrors(messageId)) {
            processor(service = failingLifecycle(error))
                .processMessage(DialogMessage(messageId, validXml.encodeToByteArray(), "test-system")) shouldBe
                MessageProcessingError.LifecycleFailure(error).left()
        }
        publisher.published shouldBe emptyList()
    }

    "should return an error when ErrorMessage publication fails" {
        val id = Uuid.random()
        val failure = IllegalStateException("error topic unavailable")
        publisher.failure = failure

        processor().processMessage(DialogMessage(id, "<invalid".toByteArray(), "test-system")) shouldBe
            MessageProcessingError.ErrorPublicationFailure(PublishError.Failure(id, errorTopic, failure)).left()

        publisher.published shouldBe emptyList()
    }
})

private class RecordingErrorMessagePublisher : ErrorMessagePublisher {
    val published = mutableListOf<ErrorMessage>()
    var failure: Throwable? = null

    override suspend fun publish(message: ErrorMessage): Either<PublishError, RecordMetadata> {
        failure?.let { return PublishError.Failure(requireNotNull(message.messageId), errorTopic, it).left() }
        published += message
        return RecordMetadata(TopicPartition(errorTopic, 0), 0L, 0, 0L, 0, 0).right()
    }
}

private fun unhandledLifecycleErrors(id: Uuid): List<LifecycleError> = listOf(
    LifecycleError.PersistenceFailure(id, "persistence failure"),
    LifecycleError.ConflictingLifecycleId(id, Uuid.random(), Uuid.random()),
    LifecycleError.ConflictingExternalReferenceId(Uuid.random(), id, Uuid.random()),
    LifecycleError.MissingExternalReferenceId(id),
    LifecycleError.MessageNotFound(id)
)

private fun failingLifecycle(error: LifecycleError): MessageLifecycleService = object : MessageLifecycleService {
    override suspend fun registerOutgoingMessage(
        lifecycleId: Uuid,
        payload: ByteArray,
        ediVersion: String?
    ): Either<LifecycleError, MessageStateSnapshot> = error.left()
}

private val errorTopic = config().kafka.topics.errorMessage

private val validXml = requireNotNull(MessageProcessorSpec::class.java.getResource("/valid-xml.xml")).readText()

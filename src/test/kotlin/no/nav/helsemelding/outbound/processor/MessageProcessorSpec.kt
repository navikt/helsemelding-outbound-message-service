package no.nav.helsemelding.outbound.processor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.ediadapter.model.v3.PostMessageResponse
import no.nav.helsemelding.jsonschema.core.model.ErrorCategory
import no.nav.helsemelding.jsonschema.core.model.ErrorCode
import no.nav.helsemelding.jsonschema.core.model.ErrorMessage
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.PublishError
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
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import kotlin.time.Clock
import kotlin.uuid.Uuid

class MessageProcessorSpec : StringSpec({
    lateinit var stateService: FakeTransactionalMessageStateService
    lateinit var adapter: FakeEdiAdapterClient
    lateinit var lifecycle: MessageLifecycleService
    lateinit var publisher: RecordingErrorMessagePublisher

    beforeEach {
        stateService = FakeTransactionalMessageStateService()
        adapter = FakeEdiAdapterClient()
        lifecycle = MessageLifecycleOrchestratorService(stateService, adapter, FakeMetrics())
        publisher = RecordingErrorMessagePublisher()
    }

    fun processor(receiver: MessageReceiver = receiverFor(), service: MessageLifecycleService = lifecycle) =
        MessageProcessor(receiver, service, FakeMetrics(), publisher)

    "should send ErrorMessage to Kafka if extracting metadata fails" {
        val messageId = Uuid.random()
        val input = DialogMessage(
            messageId,
            "<invalid".encodeToByteArray(),
            "test-system"
        )
        val before = Clock.System.now()
        processor().processMessage(input)
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

    "should create state without ErrorMessage if sending succeeds" {
        val messageId = Uuid.random()
        adapter.givenPostMessage(PostMessageResponse(Uuid.random()).right())

        processor().processMessage(DialogMessage(messageId, validXml.encodeToByteArray(), "test-system"))

        adapter.sentMessages.size shouldBe 1
        (stateService.getMessageSnapshotById(messageId) != null) shouldBe true
        publisher.published shouldBe emptyList()
    }

    "should not send ErrorMessage if sending fails" {
        val messageId = Uuid.random()
        adapter.givenPostMessage(EdiAdapterError.Api(503).left())

        processor().processMessage(DialogMessage(messageId, validXml.encodeToByteArray(), "test-system"))

        adapter.sentMessages.size shouldBe 1
        stateService.getMessageSnapshotById(messageId).shouldBeNull()
        publisher.published shouldBe emptyList()
    }

    "should not send ErrorMessage if external reference ID is missing" {
        val messageId = Uuid.random()
        adapter.givenPostMessage(PostMessageResponse(null).right())

        processor().processMessage(DialogMessage(messageId, validXml.encodeToByteArray(), "test-system"))

        adapter.sentMessages.size shouldBe 1
        stateService.getMessageSnapshotById(messageId).shouldBeNull()
        publisher.published shouldBe emptyList()
    }

    "should not send ErrorMessage if saving state fails or conflicts" {
        val messageId = Uuid.random()
        val errors = listOf(
            LifecycleError.PersistenceFailure(messageId, "persistence failure"),
            LifecycleError.ConflictingLifecycleId(messageId, Uuid.random(), Uuid.random()),
            LifecycleError.ConflictingExternalReferenceId(Uuid.random(), messageId, Uuid.random())
        )

        for (error in errors) {
            val service = object : MessageLifecycleService {
                override suspend fun registerOutgoingMessage(
                    lifecycleId: Uuid,
                    payload: ByteArray
                ): Either<LifecycleError, MessageStateSnapshot> = error.left()
            }
            processor(service = service).processMessage(DialogMessage(messageId, validXml.encodeToByteArray(), "test-system"))
        }

        publisher.published shouldBe emptyList()
    }
})

private class RecordingErrorMessagePublisher : ErrorMessagePublisher {
    val published = mutableListOf<ErrorMessage>()
    var failure: Throwable? = null
    var beforePublish: suspend () -> Unit = {}

    override suspend fun publish(message: ErrorMessage): Either<PublishError, RecordMetadata> {
        beforePublish()
        failure?.let { return PublishError.Failure(requireNotNull(message.messageId), errorTopic, it).left() }
        published += message
        return RecordMetadata(TopicPartition(errorTopic, 0), 0L, 0, 0L, 0, 0).right()
    }
}

private fun receiverFor(records: Flow<ReceiverRecord<String, ByteArray>> = flowOf()): MessageReceiver {
    val unusedReceiver = KafkaReceiver(
        ReceiverSettings(
            bootstrapServers = "localhost:9092",
            keyDeserializer = StringDeserializer(),
            valueDeserializer = ByteArrayDeserializer(),
            groupId = "processor-unit-test"
        )
    )
    return MessageReceiver(
        "input",
        object : KafkaReceiver<String, ByteArray> by unusedReceiver {
            override fun receive(topicName: String): Flow<ReceiverRecord<String, ByteArray>> = records
        }
    )
}

private const val errorTopic = "helsemelding.dialog.out.error"

private val validXml = requireNotNull(MessageProcessorSpec::class.java.getResource("/valid-dialog-message.xml")).readText()

package no.nav.helsemelding.outbound.publisher

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.MessageErrorEvent
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import no.nav.helsemelding.outbound.util.toEither
import no.nav.helsemelding.outbound.util.toJson
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import kotlin.uuid.Uuid

typealias StatusMessagePublisher = MessagePublisher<MessageStatusEvent>
typealias ErrorMessagePublisher = MessagePublisher<MessageErrorEvent>

interface MessagePublisher<T> {
    suspend fun publish(referenceId: Uuid, message: T): Either<PublishError, RecordMetadata>
}

fun statusMessagePublisher(
    kafkaPublisher: KafkaPublisher<String, ByteArray>
): StatusMessagePublisher =
    genericMessagePublisher(
        kafkaPublisher = kafkaPublisher,
        topic = config().kafka.topics.statusMessage
    )

fun errorMessagePublisher(
    kafkaPublisher: KafkaPublisher<String, ByteArray>
): ErrorMessagePublisher =
    genericMessagePublisher(
        kafkaPublisher = kafkaPublisher,
        topic = config().kafka.topics.errorMessage
    )

private inline fun <reified T> genericMessagePublisher(
    kafkaPublisher: KafkaPublisher<String, ByteArray>,
    topic: String
): MessagePublisher<T> =
    GenericMessagePublisher(
        kafkaPublisher = kafkaPublisher,
        topic = topic,
        serialize = { message -> message.toJson().toByteArray() }
    )

private class GenericMessagePublisher<T>(
    private val kafkaPublisher: KafkaPublisher<String, ByteArray>,
    private val topic: String,
    private val serialize: (T) -> ByteArray
) : MessagePublisher<T> {
    override suspend fun publish(referenceId: Uuid, message: T): Either<PublishError, RecordMetadata> =
        kafkaPublisher.publishScope {
            publishCatching(
                ProducerRecord(
                    topic,
                    referenceId.toString(),
                    serialize(message)
                )
            )
        }
            .toEither { t -> PublishError.Failure(referenceId, topic, t) }
}

class FakeStatusMessagePublisher(
    private val topic: String = config().kafka.topics.statusMessage
) : MessagePublisher<MessageStatusEvent> {

    val published = mutableListOf<MessageStatusEvent>()
    var failNext = false

    override suspend fun publish(
        referenceId: Uuid,
        message: MessageStatusEvent
    ): Either<PublishError, RecordMetadata> {
        if (failNext) {
            failNext = false
            return PublishError.Failure(
                messageId = referenceId,
                topic = topic,
                cause = RuntimeException("Publish failure")
            ).left()
        }

        published += message

        val bytes = message.toJson().toByteArray()

        val md = RecordMetadata(
            TopicPartition(topic, 0),
            0L,
            0,
            System.currentTimeMillis(),
            referenceId.toByteArray().size,
            bytes.size
        )

        return md.right()
    }
}

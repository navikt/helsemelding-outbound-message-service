package no.nav.helsemelding.state.publisher

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.helsemelding.state.PublishError
import no.nav.helsemelding.state.config
import no.nav.helsemelding.state.util.ExtendedLogger
import no.nav.helsemelding.state.util.toEither
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import kotlin.uuid.Uuid

private val log = ExtendedLogger(KotlinLogging.logger {})

typealias DialogMessagePublisher = MessagePublisher
typealias StatusMessagePublisher = MessagePublisher

interface MessagePublisher {
    suspend fun publish(referenceId: Uuid, message: String): Either<PublishError, RecordMetadata>
}

fun dialogMessagePublisher(
    kafkaPublisher: KafkaPublisher<String, ByteArray>
): DialogMessagePublisher =
    GenericMessagePublisher(
        kafkaPublisher = kafkaPublisher,
        topic = config().kafka.topics.dialogMessageIn
    )

fun statusMessagePublisher(
    kafkaPublisher: KafkaPublisher<String, ByteArray>
): StatusMessagePublisher =
    GenericMessagePublisher(
        kafkaPublisher = kafkaPublisher,
        topic = config().kafka.topics.statusMessage
    )

private class GenericMessagePublisher(
    private val kafkaPublisher: KafkaPublisher<String, ByteArray>,
    private val topic: String
) : MessagePublisher {
    override suspend fun publish(referenceId: Uuid, message: String): Either<PublishError, RecordMetadata> =
        kafkaPublisher.publishScope {
            publishCatching(
                ProducerRecord(
                    topic,
                    referenceId.toString(),
                    message.toByteArray()
                )
            )
        }
            .toEither { t -> PublishError.Failure(referenceId, topic, t) }
}

class FakeStatusMessagePublisher(
    private val topic: String = config().kafka.topics.statusMessage
) : MessagePublisher {

    val published = mutableListOf<ByteArray>()
    var failNext = false

    override suspend fun publish(referenceId: Uuid, message: String): Either<PublishError, RecordMetadata> {
        if (failNext) {
            failNext = false
            return PublishError.Failure(
                messageId = referenceId,
                topic = topic,
                cause = RuntimeException("Publish failure")
            )
                .left()
        }

        published += message.toByteArray()

        val md = RecordMetadata(
            TopicPartition(topic, 0),
            0L,
            0,
            System.currentTimeMillis(),
            referenceId.toByteArray().size,
            published.single().size
        )

        return md.right()
    }
}

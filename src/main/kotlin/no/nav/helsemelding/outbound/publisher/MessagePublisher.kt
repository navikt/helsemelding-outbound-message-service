package no.nav.helsemelding.outbound.publisher

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.config.Topics
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import no.nav.helsemelding.outbound.util.toEither
import no.nav.helsemelding.outbound.util.toJson
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

interface MessagePublisher {
    suspend fun publish(message: MessageStatusEvent): Either<PublishError, RecordMetadata>
}

class StatusMessagePublisher(
    private val topics: Topics,
    private val kafkaPublisher: KafkaPublisher<String, ByteArray>,
    private val json: Json = Json
) : MessagePublisher {
    override suspend fun publish(message: MessageStatusEvent): Either<PublishError, RecordMetadata> =
        publish(
            topic = topics.statusMessage,
            key = message.messageId,
            payload = json.encodeToString(message)
        )

    private suspend fun publish(
        topic: String,
        key: Uuid,
        payload: String
    ): Either<PublishError, RecordMetadata> =
        kafkaPublisher.publishScope {
            publishCatching(
                ProducerRecord(
                    topic,
                    key.toString(),
                    payload.encodeToByteArray()
                )
            )
        }
            .toEither { error -> PublishError.Failure(key, topic, error) }
            .onRight { metadata -> metadata.logPublished(topic, key) }
}

private fun RecordMetadata.logPublished(topic: String, key: Uuid) {
    log.info {
        "Published message: key=$key topic=$topic partition=${partition()} offset=${offset()}"
    }
}

class FakeStatusMessagePublisher(
    private val topic: String = config().kafka.topics.statusMessage
) : MessagePublisher {
    val published = mutableListOf<MessageStatusEvent>()
    var failNext = false

    override suspend fun publish(
        message: MessageStatusEvent
    ): Either<PublishError, RecordMetadata> {
        if (failNext) {
            failNext = false
            return PublishError.Failure(
                messageId = message.messageId,
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
            message.messageId.toString().encodeToByteArray().size,
            bytes.size
        )

        return md.right()
    }
}
